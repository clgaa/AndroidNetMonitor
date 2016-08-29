package xyz.hexene.localvpn.listener;

/**
 * Created by didi on 16/8/29.
 */
public interface IMonitor {
    byte[] onSendCallBack(String payload);
}
