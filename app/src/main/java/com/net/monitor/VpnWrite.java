package com.net.monitor;

import android.util.Log;

import com.net.monitor.util.AppUtils;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by didi on 16/9/6.
 */
public class VpnWrite implements Runnable {
    private static final String TAG = VpnWrite.class.getSimpleName();

    private FileDescriptor vpnFileDescriptor;

    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

    public VpnWrite(FileDescriptor vpnFileDescriptor,
                    ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
        this.vpnFileDescriptor = vpnFileDescriptor;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    @Override
    public void run() {
        Log.i(TAG, "Started");

        FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

        try {
            boolean dataReceived;
            while (!Thread.interrupted()) {
                ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                if (bufferFromNetwork != null) {
                    bufferFromNetwork.flip();


                    while (bufferFromNetwork.hasRemaining())
                        vpnOutput.write(bufferFromNetwork);
                    dataReceived = true;
//                    lock.release();
                    ByteBufferPool.release(bufferFromNetwork);
                } else {
                    dataReceived = false;
                }

                // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                if (!dataReceived)
                    Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (IOException e) {
            Log.w(TAG, e.toString(), e);
        } finally {
            AppUtils.closeResources(vpnOutput);
        }
    }
}
