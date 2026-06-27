package com.android1939.kardsserver.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class TimeUtil {
    private TimeUtil() {
    }

    public static String nowIso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
