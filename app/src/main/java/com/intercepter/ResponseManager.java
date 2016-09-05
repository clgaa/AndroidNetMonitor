package com.intercepter;

import com.intercepter.util.Constant;
import com.intercepter.util.TextUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by didi on 16/9/5.
 */
public class ResponseManager {
    private static ConcurrentHashMap<String, String> sResponse = new ConcurrentHashMap<>();
    private static final int BSIZE = 1 * 1024;

    private ResponseManager() {

    }

    private static class ResponseMangerHelper {
        private static ResponseManager mInstance = new ResponseManager();
    }

    public static ResponseManager getInstance() {
        return ResponseMangerHelper.mInstance;
    }

    public boolean isContain(String api) {
        return sResponse.containsKey(api);
    }

    public String getResponse(String api) {
        if(!isContain(api)) {
            return null;
        }
        String response = sResponse.get(api);
        if(!TextUtil.isEmpty(response)) {
            return response;
        }
        return getFromSD(api);
    }

    private synchronized String getFromSD(String api) {
        String path = Constant.PATH + api + ".txt";
        FileChannel fc = null;
        try {
            fc = new FileInputStream(path).getChannel();
            ByteBuffer buff = ByteBuffer.allocate(BSIZE);
            String response = "";
            while (fc.read(buff) != -1) {
                buff.flip();
                int payloasSize = buff.limit() - buff.position();
                byte[] data = new byte[payloasSize];
                buff.get(data, 0, payloasSize);
                response += new String(data);
                buff.clear();
            }

            sResponse.put(api, response);
            return response;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if(null != fc) {
                try {
                    fc.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public ConcurrentHashMap<String, String> getCache() {
        return sResponse;
    }

}
