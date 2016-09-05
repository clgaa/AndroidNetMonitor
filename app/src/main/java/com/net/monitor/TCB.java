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

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;

/**
 * Transmission Control Block
 */
public class TCB {
    public TCBKey mTcbKey;

    public long mySequenceNum, theirSequenceNum;
    public long myAcknowledgementNum, theirAcknowledgementNum;
    public TCBStatus status;


    // TCP has more states, but we need only these
    public enum TCBStatus {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
    }

    public SocketChannel socketChannel;
    public Packet referencePacket;
    public boolean waitingForNetworkData;
    public SelectionKey selectionKey;

    private static final int MAX_CACHE_SIZE = 50; // XXX: Is this ideal?
    private static LRUCache<TCBKey, TCB> tcbCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, TCB>() {
                @Override
                public void cleanup(Map.Entry<String, TCB> eldest) {
                    eldest.getValue().closeChannel();
                }
            });

    public static TCB getTCB(TCBKey tcbKey) {
        synchronized (tcbCache) {
            return tcbCache.get(tcbKey);
        }
    }

    public static void putTCB(TCBKey tcbKey, TCB tcb) {
        synchronized (tcbCache) {
            tcbCache.put(tcbKey, tcb);
        }
    }

    public TCB(TCBKey tcbKey, long mySequenceNum, long theirSequenceNum, long myAcknowledgementNum, long theirAcknowledgementNum, SocketChannel socketChannel, Packet referencePacket) {
        this.mTcbKey = tcbKey;

        this.mySequenceNum = mySequenceNum;
        this.theirSequenceNum = theirSequenceNum;
        this.myAcknowledgementNum = myAcknowledgementNum;
        this.theirAcknowledgementNum = theirAcknowledgementNum;
        this.socketChannel = socketChannel;
        this.referencePacket = referencePacket;
    }

    public static void closeTCB(TCB tcb) {
        tcb.closeChannel();
        synchronized (tcbCache) {
            tcbCache.remove(tcb.mTcbKey);
        }
    }

    public static void closeAll() {
        synchronized (tcbCache) {
            Iterator<Map.Entry<TCBKey, TCB>> it = tcbCache.entrySet().iterator();
            while (it.hasNext()) {
                it.next().getValue().closeChannel();
                it.remove();
            }
        }
    }

    private void closeChannel() {
        try {
            if (selectionKey != null) {
                socketChannel.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    public static class TCBKey {
        String mDestinationAddress;
        int mDestinationPort;
        int mSourcePort;

        public TCBKey(String mDestinationAddress, int mDestinationPort, int mSourcePort) {
            this.mDestinationAddress = mDestinationAddress;
            this.mDestinationPort = mDestinationPort;
            this.mSourcePort = mSourcePort;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TCBKey tcbKey = (TCBKey) o;

            if (mDestinationPort != tcbKey.mDestinationPort) return false;
            if (mSourcePort != tcbKey.mSourcePort) return false;
            return mDestinationAddress != null ? mDestinationAddress.equals(tcbKey.mDestinationAddress) : tcbKey.mDestinationAddress == null;

        }

        @Override
        public int hashCode() {
            int result = mDestinationAddress != null ? mDestinationAddress.hashCode() : 0;
            result = 31 * result + mDestinationPort;
            result = 31 * result + mSourcePort;
            return result;
        }

        @Override
        public String toString() {
            return "TCBKey{" +
                    "mDestinationAddress='" + mDestinationAddress + '\'' +
                    ", mDestinationPort=" + mDestinationPort +
                    ", mSourcePort=" + mSourcePort +
                    '}';
        }
    }


}
