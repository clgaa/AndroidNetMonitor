/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.net.monitor;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.net.monitor.TCB.TCBStatus;

public class TCPInput implements Runnable {
    private static final String TAG = TCPInput.class.getSimpleName();
    public static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;

    public TCPInput(ConcurrentLinkedQueue<ByteBuffer> outputQueue, Selector selector) {
        this.outputQueue = outputQueue;
        this.selector = selector;
    }

    @Override
    public void run() {
        try {
            Log.d(TAG, "Started");
            while (!Thread.interrupted()) {
                int readyChannels = selector.select();

                if (readyChannels == 0) {
                    Thread.sleep(10);
                    continue;
                }

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = keys.iterator();

                while (keyIterator.hasNext() && !Thread.interrupted()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        if (key.isConnectable())
                            processConnect(key, keyIterator);
                        else if (key.isReadable())
                            processInput(key, keyIterator);
                    }
                }
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (IOException e) {
            Log.w(TAG, e.toString(), e);
        }
    }

    private void processConnect(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        TCB tcb = (TCB) key.attachment();
        Packet referencePacket = tcb.referencePacket;
        try {
            if (tcb.socketChannel.finishConnect()) {
                keyIterator.remove();
                tcb.status = TCBStatus.SYN_RECEIVED;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                outputQueue.offer(responseBuffer);
                tcb.mySequenceNum++; // SYN counts as a byte
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection error: " + tcb.mTcbKey, e);
            ByteBuffer responseBuffer = ByteBufferPool.acquire();
            referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
            outputQueue.offer(responseBuffer);
            TCB.closeTCB(tcb);
        }
    }

    private void processInput(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        keyIterator.remove();
        ByteBuffer receiveBuffer = ByteBufferPool.acquire();
        // Leave space for the header
        receiveBuffer.position(HEADER_SIZE);

        TCB tcb = (TCB) key.attachment();
        synchronized (tcb) {
            Packet referencePacket = tcb.referencePacket;
            SocketChannel inputChannel = (SocketChannel) key.channel();
            int readBytes;
            try {
                readBytes = inputChannel.read(receiveBuffer);
//                byte[] b = buildResponse().getBytes();
//                readBytes = b.length;
//                receiveBuffer.position(HEADER_SIZE);
//                receiveBuffer.put(b);
                if (readBytes > 1) {

                    Log.d("chenlongrcv", "=============RCV   begin===========");
//                    Log.d("chenlongrcv", tcb.ipAndPort);
//                    try {
//                        byte[] b = new byte[readBytes];
//
//                        for (int i = 0; i < readBytes; i++) {
//                            b[i] = receiveBuffer.get(i + HEADER_SIZE);
//                            Log.d("chenlongrcv", "" + b[i]);
//                        }
//                        int pos = 0;
//                        for(int i = 0; i < b.length - 4; i++) {
//                            if(b[i] == 0x0D && b[i + 1] == 0x0A && b[i + 2] == 31 && b[i + 3] == -117) {
//                                pos = i + 2;
//                            }
//                        }
//                        String payloadText = new String(b);
//
////                        Log.d("chenlongrcv", payloadText);
////                        String[] parts = payloadText.split("\\r\\n");
////                        String head = null;
////                        for(String part : parts) {
////                            if(!"".equals(part)) {
////                                head += part + "\\r\\n";
////                            } else {
////                                head += "\\r\\n";
////                                break;
////                            }
////                        }
//                        String content = payloadText.substring(pos);
//                        byte[] c = content.getBytes();
//                        Log.d("chenlongrcv", GzipUtil.uncompress(content));
//                    } catch (Exception e) {
//                        Log.d("chenlongrcv", e.toString());
//                    }

                }
            } catch (Exception e) {
                Log.e(TAG, "Network read error: " + tcb.mTcbKey, e);
                Log.d("chenlongrcv", "Network read error: " + tcb.mTcbKey, e);
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                outputQueue.offer(receiveBuffer);
                TCB.closeTCB(tcb);
                return;
            }

            if (readBytes == -1) {
                // End of stream, stop waiting until we push more data
                key.interestOps(0);
                tcb.waitingForNetworkData = false;

                if (tcb.status != TCBStatus.CLOSE_WAIT) {
                    ByteBufferPool.release(receiveBuffer);
                    return;
                }

                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.FIN, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            } else {
                // XXX: We should ideally be splitting segments by MTU/MSS, but this seems to work without

                referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes);
                tcb.mySequenceNum += readBytes; // Next sequence number
                receiveBuffer.position(HEADER_SIZE + readBytes);
            }
        }
        outputQueue.offer(receiveBuffer);
    }

    private String buildResponse() {
        String cotent = "{\"code\":304,\"msg\":\"CACHED\",\"data\":[],\"ns\":\"gulf_driver\",\"key\":\"dd9a7bfb6ccbe1a73314b4e88ab9a5f\",\"md5\":\"\"}";
        String response = "HTTP/1.1 200 OK\r\n";
        response += "Content-Type: application/json;charset=utf-8\r\n";
        response += "Connection: keep-alive\r\n";

        try {
//            String data = GzipUtil.compress(cotent);
            String data = cotent;
            response += "Content-Length: " + data.length() + "\r\n";
            response += "\r\n";
            response += data;
            Log.d("chenlongrcv", response);
        } catch (Exception e) {
            e.printStackTrace();
        }


        return response;
    }
}
