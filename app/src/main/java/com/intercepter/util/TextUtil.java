package com.intercepter.util;

import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;


import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具类
 * 
 * @author wangzengyang 2012-11-19
 */
public class TextUtil {

    /**
     * Returns true if the string is null or 0-length.
     * 
     * @param str
     *            the string to be examined
     * @return true if str is null or zero length
     */
    public static boolean isEmpty(String str) {
        if (str == null) {
            return true;
        }
        str = str.trim();
        return str.length() == 0 || str.equals("null");
    }

    /**
     * 去掉文件名称中的非法字符
     *
     * @param str
     * @return
     */
    public static String escapeFileName(String str) {
        if (str == null) {
            return null;
        }
        /** 非法字符包括：/\:*?"<>| */
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                    || c == '>' || c == '|') {
                continue;
            } else {
                builder.append(c);
            }
        }

        return builder.toString();
    }

    /**
     * 比较两个字符串是否相同
     * 
     * @param first
     * @param second
     * @return
     */
    public static boolean equals(String first, String second) {
        if (isEmpty(first) || isEmpty(second))
            return false;
        return first.equals(second);
    }

    public static int length(String phone) {
        return phone == null ? 0 : phone.length();
    }


    public static String getHighlightTxt(String txt) {
        String result = txt;
        if (!isEmpty(result)) {
        	result = result.replace("{", "<font color='#ff8903'>");
        	result = result.replace("}", "</font>");
            result = result.replace("\n", "<br>");
        }
        return result;
    }
    public  static CharSequence getHtmlFormet(String str){
       return Html.fromHtml(getHighlightTxt(str));
    }

    /**
     * 将push消息转化为正规金额表达，原始文本如下
     * "2013-12-25 11:03:20\u6536\u5230\u4e58\u5ba213000000129\u7684\u8f66\u8d39125"
     * 转化后为"2013-12-25 11:03:20收到乘客13000000129的车费1元2角5分"
     */
    public static String converTextWithAmount(String text) {
        String result = null;
        Pattern p = Pattern.compile("\\d+");
        Matcher m = p.matcher(text);
        String lastNumberString = null;// 字符串最靠近结尾的数字串
        int lastNumberStringStart = 0;// 字符串最靠近结尾的数字串在原始字符串的起始位置
        while (m.find()) {
            lastNumberString = m.group();
            lastNumberStringStart = m.start();
        }
        if (lastNumberString == null) {// 不是指定格式的字符串
            return text;
        }
        try {
            int amount = Integer.valueOf(lastNumberString);
            int yuan = amount / 100;
            int cent = amount % 100;
            int horn = cent / 10;
            int point = cent % 10;
            String content1 = text.substring(0, lastNumberStringStart);
            StringBuilder sb = new StringBuilder();
            if (yuan > 0) {
                sb.append(yuan).append("元");
            }
            if (horn > 0) {
                sb.append(horn).append("角");
            }
            if (point > 0) {
                sb.append(point).append("分");
            }
            result = content1 + sb.toString();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

}
