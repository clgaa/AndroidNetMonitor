package com.net.monitor.util;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by didi on 16/9/6.
 */
public class AppUtils {
    // TODO: Move this to a "utils" class for reuse
    public static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
