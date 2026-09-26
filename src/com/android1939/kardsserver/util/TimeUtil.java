package com.android1939.kardsserver.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class TimeUtil {
    private TimeUtil() {
    }

    public static String nowIso() {
        return formatIso(System.currentTimeMillis());
    }

    /** 把毫秒时间戳格式化为与 {@link #nowIso()} 同形的 ISO-8601 字符串（UTC）。 */
    public static String formatIso(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
