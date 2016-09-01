package com.net.monitor;

import com.net.monitor.listener.IMonitor;

/**
 * Created by didi on 16/8/29.
 */
public class VpnManager {
    private IMonitor mListener;

    private VpnManager() {

    }
    private static class VpnManagerHelper {
        private static VpnManager mInstance = new VpnManager();
    }

    public static VpnManager getInstance() {
        return VpnManagerHelper.mInstance;
    }


    public synchronized void registerMonitorListener(IMonitor listener) {
        if(null != listener) {
            mListener = listener;
        }
    }

    public synchronized void unRegisterMonitorListener() {
        mListener = null;
    }

    public synchronized byte[] notify(String payload) {
        if(null == mListener) {
            return null;
        }
        return mListener.onSendCallBack(payload);
    }
}
