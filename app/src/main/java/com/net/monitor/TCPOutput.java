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
import com.net.monitor.util.Config;

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
                TCPHeader tcpHeader = currentOutPacket.tcpHeader;
                TCB.TCBKey tcbKey = new TCB.TCBKey(currentOutPacket.ip4Header.destinationAddress.getHostAddress(), tcpHeader.destinationPort, tcpHeader.sourcePort);
                TCB tcb = TCB.getTCB(tcbKey);
                if (tcb == null) {
                    initializeConnection(tcbKey, currentOutPacket);
                } else if (tcpHeader.isSYN()) {
                    processDuplicateSYN(tcb, tcpHeader);
                } else if (tcpHeader.isRST()) { //常见于 客户端因服务响应超时不想与服务器继续建立或断开连接
                    closeCleanly(tcb);
                } else if (tcpHeader.isFIN() && tcpHeader.isACK()) {
                    processFIN(tcb, tcpHeader);
                } else if (tcpHeader.isACK()) {
                    processACK(tcb,tcpHeader, currentOutPacket.backingBuffer);
                }
            }
        } catch (InterruptedException e) {
            Log.i(TAG, "Stopping");
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            TCB.closeAll();
        }
    }

    /**
     * 模拟建立连接  拦截客户端发出的第一个SYNC包
     *
     * @param tcbKey
     * @param currentOutPacket
     * @throws IOException
     */
    private void initializeConnection(TCB.TCBKey tcbKey, Packet currentOutPacket)
            throws IOException {
        ByteBuffer responseBuffer = ByteBufferPool.acquire();
        TCPHeader tcpHeader = currentOutPacket.tcpHeader;
        IP4Header ip4Header = currentOutPacket.ip4Header;

        if (tcpHeader.isSYN()) {
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            mVpnService.protect(outputChannel.socket());
            //模拟一个ack&syn包
            TCB tcb = new TCB(tcbKey, outputChannel, mRandom.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, currentOutPacket);
            TCB.putTCB(tcbKey, tcb);
            try {
                outputChannel.connect(new InetSocketAddress(ip4Header.destinationAddress, tcpHeader.destinationPort));
                if (outputChannel.finishConnect()) {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    currentOutPacket.swapSourceAndDestination();
                    currentOutPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK),
                            tcb.mySequenceNum++, tcb.myAcknowledgementNum, 0);
                } else {
                    tcb.status = TCBStatus.SYN_SENT;
                    tcb.selectionKey = outputChannel.register(mSelector, SelectionKey.OP_CONNECT, tcb);
                    mSelector.wakeup();
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, e.toString(), e);
                currentOutPacket.swapSourceAndDestination();
                currentOutPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        } else {
            currentOutPacket.swapSourceAndDestination();
            currentOutPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
        }
        mNetworksToDevicePacketBytes.offer(responseBuffer);
    }

    /**
     * 模拟建立连接  拦截客户端发出的重复的SYNC包
     *
     * @param tcb
     * @param tcpHeader
     */

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader) {
        ByteBuffer responseBuffer = ByteBufferPool.acquire();
        synchronized (tcb) {
            if (tcb.status == TCBStatus.SYN_SENT) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        // 拦截到客服端发来的重复SYNC包后,直接模拟服务端端返回一个断开连接响应包,1表示sync包中SYNC占用1bit
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader) {
        ByteBuffer responseBuffer = ByteBufferPool.acquire();
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

    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer) throws IOException {
        ByteBuffer responseBuffer = ByteBufferPool.acquire();
        ByteBuffer payLoadBuffer = tcb.referencePacket.backingBuffer;
        synchronized (tcb) {
            int payloadSize = payLoadBuffer.limit() - payLoadBuffer.position();
            SocketChannel outputChannel = tcb.mOutputChannel;
            if (tcb.status == TCBStatus.SYN_RECEIVED) {
                tcb.status = TCBStatus.ESTABLISHED;
                if(tcb.selectionKey==null){
                    tcb.selectionKey = outputChannel.register(mSelector, SelectionKey.OP_READ, tcb);
                }else{
                    tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                }
                mSelector.wakeup();
                tcb.waitingForNetworkData = true;
            } else if (tcb.status == TCBStatus.LAST_ACK) {
                closeCleanly(tcb);
                return;
            }

            if (payloadSize == 0) return; // Empty ACK, ignore

            if (!tcb.waitingForNetworkData) {
                mSelector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            // 真实环境下, 将请求发送给远程的Server

            try {
                while (payloadBuffer.hasRemaining())
                    outputChannel.write(payloadBuffer);
            } catch (IOException e) {
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }
            // 虚拟情况下, 直接构造响应包给客户端
            if (!Config.isDebug) {
                return;
            }
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            tcb.referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
        }
        mNetworksToDevicePacketBytes.offer(responseBuffer);
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer) {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);
        mNetworksToDevicePacketBytes.offer(buffer);
        TCB.closeTCB(tcb);
    }

    private void closeCleanly(TCB tcb) {
        TCB.closeTCB(tcb);
    }


}
