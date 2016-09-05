package com.intercepter.util;

import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.util.regex.Pattern;

/**
 * Created by didi on 16/9/5.
 */
public class DirList {

    public static String[] getApi(String path, String regx) {
        if(TextUtil.isEmpty(path) || TextUtil.isEmpty(regx)) {
            return null;
        }

        File dir = new File(path);
        String[] list = dir.list(new DirFilter(regx));
        if (null != list) {
            for (String dirItem : list) {
                Log.d("chenlong", "api name = " + dirItem);
            }
        }
        return list;
    }

}

class DirFilter implements FilenameFilter {
    Pattern pattern;

    public DirFilter(String regx) {
        pattern = Pattern.compile(regx);
    }

    @Override
    public boolean accept(File dir, String filename) {
        return pattern.matcher(filename).matches();
    }
}
