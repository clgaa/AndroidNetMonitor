package com.net.monitor;

import android.util.Log;

import com.net.monitor.util.AppUtils;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by didi on 16/9/6.
 */
public class VpnRead implements Runnable {
    private static final String TAG = VpnRead.class.getSimpleName();
    private FileDescriptor vpnFileDescriptor;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;

    public VpnRead(FileDescriptor vpnFileDescriptor,
                   ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                   ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue) {
        this.vpnFileDescriptor = vpnFileDescriptor;
        this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
        this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
    }

    @Override
    public void run() {
        Log.i(TAG, "Started");

        FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();

        try {
            ByteBuffer bufferToNetwork = null;
            boolean dataSent = true;
            while (!Thread.interrupted()) {
                if (dataSent)
                    bufferToNetwork = ByteBufferPool.acquire();
                else
                    bufferToNetwork.clear();

                // TODO: Block when not connected
//                FileLock flin = null;
//                flin = vpnInput.tryLock();
//                //文件被其它线程使用
//                if(null == flin) {
//                    continue;
//                }

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
//                flin.release();
                // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                if (!dataSent)
                    Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (IOException e) {
            Log.w(TAG, e.toString(), e);
        } finally {
            AppUtils.closeResources(vpnInput);
        }
    }
}
