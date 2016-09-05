package com.intercepter.task;

import com.intercepter.ResponseManager;
import com.intercepter.util.DirList;

import java.net.ResponseCache;

/**
 * Created by didi on 16/9/5.
 */
public class GetApiTask implements Runnable{
    final String path;
    final String regx;
    public GetApiTask(String path, String regx) {
        this.path = path;
        this.regx = regx;
    }
    @Override
    public void run() {
        String[] list = DirList.getApi(path, regx);
        if(null != list) {
            for(String item : list) {
                ResponseManager.getInstance().getCache().put(item, "");
            }
        }
    }
}
