package xyz.intercepter;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import xyz.hexene.localvpn.VpnManager;
import xyz.hexene.localvpn.listener.IMonitor;

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
            if(payload.startsWith("POST")) {
                String[] parts = payload.split("\\r\\n");
                String[] urls = parts[0].split("\\s");
                String url = urls[1];
                if (null != api) {
                    Iterator<String> iterator = api.keySet().iterator();
                    while (iterator.hasNext()) {
                        String key = iterator.next();
                        if (url.endsWith(key)) {
                            String value = api.get(key);
                            return value.getBytes();
                        }
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

    public void endIntercept() {
        VpnManager.getInstance().unRegisterMonitorListener();
    }
}
