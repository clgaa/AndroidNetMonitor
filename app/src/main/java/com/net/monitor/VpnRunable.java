package com.net.monitor;

import android.util.Log;

import com.net.monitor.util.AppUtils;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by didi on 16/9/6.
 */
public class VpnRunable implements Runnable{
        private static final String TAG = VpnRunable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VpnRunable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }


        @Override
        public void run() {
            Log.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataReceived;
                while (!Thread.interrupted()) {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();

                    // TODO: Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        Packet packet = new Packet(bufferToNetwork);
                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            Log.w(TAG, "Unknown packet type");
                            Log.w(TAG, packet.ip4Header.toString());
                            dataSent = false;
                        }
                    } else {
                        dataSent = false;
                    }

                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining())
                            vpnOutput.write(bufferFromNetwork);
                        dataReceived = true;

                        ByteBufferPool.release(bufferFromNetwork);
                    } else {
                        dataReceived = false;
                    }

                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataReceived)
                        Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Log.i(TAG, "Stopping");
            } catch (IOException e) {
                Log.w(TAG, e.toString(), e);
            } finally {
                AppUtils.closeResources(vpnInput, vpnOutput);
            }
    }
}
