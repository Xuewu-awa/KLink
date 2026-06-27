package com.xuewu.KLink;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 前台服务 — 保持服务器在后台运行不被系统杀死。
 */
public class ServerService extends Service {

    private static final int NOTIFICATION_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this)
                .setContentTitle("KLink 服务器运行中")
                .setContentText("本地游戏服务器正在运行")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
