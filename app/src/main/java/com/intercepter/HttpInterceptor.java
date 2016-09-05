package com.intercepter;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import com.intercepter.util.TextUtil;
import com.net.monitor.VpnManager;
import com.net.monitor.listener.IMonitor;

/**
 * Created by didi on 16/8/29.
 */
public class HttpInterceptor {

    //域名
    private String host;
    //api name + content
    private ConcurrentHashMap<String, String> api;
    //运行在子线程
    private IMonitor monitorCallBack = new IMonitor() {
        @Override
        public byte[] onSendCallBack(String payload) {
            if (null == payload || "".equalsIgnoreCase(payload)) {
                return null;
            }
            if (payload.startsWith("POST")) {
                String[] parts = payload.split("\\r\\n");
                String[] urls = parts[0].split("\\s");
                String url = urls[1];
                String requst = url.substring(url.lastIndexOf("/") + 1);
                if(ResponseManager.getInstance().isContain(requst)) {
                    String response = ResponseManager.getInstance().getResponse(requst);
                    if(!TextUtil.isEmpty(response)) {
                        return response.getBytes();
                    }
                }
            } else {
                //"GET..."
            }
            return null;
        }
    };

    public HttpInterceptor(String host, ConcurrentHashMap<String, String> api) {
        this.host = host;
        this.api = api;
    }

    public void startIntercept() {
        VpnManager.getInstance().registerMonitorListener(monitorCallBack);
    }

}
