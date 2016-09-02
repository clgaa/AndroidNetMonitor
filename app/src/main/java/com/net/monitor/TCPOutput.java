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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.net.monitor.Packet.TCPHeader;
import com.net.monitor.Packet.IP4Header;
import com.net.monitor.TCB.TCBStatus;

public class TCPOutput implements Runnable {
    private static final String TAG = TCPOutput.class.getSimpleName();

    private LocalVPNService mVpnService;
    private ConcurrentLinkedQueue<Packet> mDeviceToNetWorksPackets;
    private ConcurrentLinkedQueue<ByteBuffer> mNetworksToDevicePacketBytes;
    private Selector mSelector;

    private Random mRandom = new Random();

    public TCPOutput(ConcurrentLinkedQueue<Packet> outQueue, ConcurrentLinkedQueue<ByteBuffer> inQueue,
                     Selector selector, LocalVPNService vpnService) {
        this.mDeviceToNetWorksPackets = outQueue;
        this.mNetworksToDevicePacketBytes = inQueue;
        this.mSelector = selector;
        this.mVpnService = vpnService;
    }

    @Override
    public void run() {
        Log.i(TAG, "Starting");
        try {
            Thread currentThread = Thread.currentThread();
            while (true) {
                Packet currentOutPacket;
                do {
                    currentOutPacket = mDeviceToNetWorksPackets.poll();
                    if (currentOutPacket != null)
                        break;
                    Thread.sleep(10);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                ByteBuffer payloadBuffer = currentOutPacket.backingBuffer;
                currentOutPacket.backingBuffer = null;
                TCPHeader tcpHeader = currentOutPacket.tcpHeader;
                TCB.TCBKey tcbKey = new TCB.TCBKey(currentOutPacket.ip4Header.destinationAddress.getHostAddress(), tcpHeader.destinationPort, tcpHeader.sourcePort);
                TCB tcb = TCB.getTCB(tcbKey);
                if (tcb == null) {
                    initializeConnection(tcbKey, currentOutPacket, responseBuffer);
                } else if (tcpHeader.isSYN()) {
                    processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                } else if (tcpHeader.isRST()) { ////服务器端口为开(服务端),请求超时(客户端)
                    closeCleanly(tcb, responseBuffer);
                } else if (tcpHeader.isFIN()) {
                    processFIN(tcb, tcpHeader, responseBuffer);
                } else if (tcpHeader.isACK()) {
                    processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);
                }

                if (responseBuffer.position() == 0) {
                    ByteBufferPool.release(responseBuffer);
                }
                ByteBufferPool.release(payloadBuffer);
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            TCB.closeAll();
        }
    }

    private void initializeConnection(TCB.TCBKey tcbKey, Packet currentOutPacket, ByteBuffer responseBuffer)
            throws IOException {

        TCPHeader tcpHeader = currentOutPacket.tcpHeader;
        IP4Header ip4Header =currentOutPacket.ip4Header;

        currentOutPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN()) {
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            mVpnService.protect(outputChannel.socket());
            //模拟一个ack&syn包
            TCB tcb = new TCB(tcbKey, mRandom.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, currentOutPacket);
            TCB.putTCB(tcbKey, tcb);
            try {
                outputChannel.connect(new InetSocketAddress(ip4Header.destinationAddress, tcpHeader.destinationPort));
                if (outputChannel.finishConnect()) {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    currentOutPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    tcb.mySequenceNum++;
                } else {
                    tcb.status = TCBStatus.SYN_SENT;
                    tcb.selectionKey = outputChannel.register(mSelector, SelectionKey.OP_CONNECT, tcb);
                    mSelector.wakeup();
                    return;
                }
            } catch (IOException e) {
                currentOutPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        } else {
            currentOutPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
        }
        mNetworksToDevicePacketBytes.offer(responseBuffer);
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            if (tcb.status == TCBStatus.SYN_SENT) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            if (tcb.waitingForNetworkData) {
                tcb.status = TCBStatus.CLOSE_WAIT;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK,
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            } else {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            }
        }
        mNetworksToDevicePacketBytes.offer(responseBuffer);
    }

    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException {
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();

        synchronized (tcb) {
            SocketChannel outputChannel = (SocketChannel)tcb.selectionKey.channel();
            if (tcb.status == TCBStatus.SYN_RECEIVED) {
                tcb.status = TCBStatus.ESTABLISHED;
                mSelector.wakeup();
                tcb.selectionKey = outputChannel.register(mSelector, SelectionKey.OP_READ, tcb);
                tcb.waitingForNetworkData = true;
            } else if (tcb.status == TCBStatus.LAST_ACK) {
                closeCleanly(tcb, responseBuffer);
                return;
            }

            if (payloadSize == 0) return; // Empty ACK, ignore


            if (!tcb.waitingForNetworkData) {
                mSelector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            // Forward to remote server
            try {
                byte[] b = interceptor(payloadBuffer.duplicate());
                if (null != b) {
                    tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
                    tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
                    Packet referencePacket = tcb.referencePacket;
                    referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    mNetworksToDevicePacketBytes.offer(responseBuffer);


                    String response = buildResponse(b);
                    ByteBuffer receiveBuffer = ByteBufferPool.acquire();
                    // Leave space for the header
                    receiveBuffer.position(TCPInput.HEADER_SIZE);

                    receiveBuffer.put(response.getBytes());
//                    Packet referencePacket = tcb.referencePacket;

                    referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, response.length());
                    tcb.mySequenceNum += response.length(); // Next sequence number
                    receiveBuffer.position(TCPInput.HEADER_SIZE + response.length());
                    mNetworksToDevicePacketBytes.offer(receiveBuffer);
                    return;
                }

                while (payloadBuffer.hasRemaining())
                    outputChannel.write(payloadBuffer);
            } catch (Exception e) {
                Log.e(TAG, "Network write error: " + tcb.mTcbKey, e);
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            Packet referencePacket = tcb.referencePacket;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        }
        mNetworksToDevicePacketBytes.offer(responseBuffer);
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer) {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);
        mNetworksToDevicePacketBytes.offer(buffer);
        TCB.closeTCB(tcb);
    }

    private void closeCleanly(TCB tcb, ByteBuffer buffer) {
        ByteBufferPool.release(buffer);
        TCB.closeTCB(tcb);
    }

    private byte[] interceptor(ByteBuffer payload) {
        if (null == payload) {
            return null;
        }
        int payloadSize = payload.limit() - payload.position();
        Log.d("chenlong", "=============begin===========");
        byte[] b = new byte[payloadSize];
        payload.get(b, 0, payloadSize);
        String payloadText = new String(b);
        Log.d("chenlong", payloadText);
        byte[] result = VpnManager.getInstance().notify(payloadText);
        Log.d("chenlong", "=============end============");
        return result;
    }


    private String buildResponse(byte[] data) {
        if (null == data) {
            return null;
        }
        String response = "HTTP/1.1 200 OK\r\n";
        response += "Content-Type: application/json;charset=utf-8\r\n";
        response += "Content-Length: " + data.length + "\r\n";
        response += "Connection: keep-alive\r\n";
        response += "\r\n";
        response += new String(data);

        return response;
    }
}
