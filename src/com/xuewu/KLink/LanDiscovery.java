package com.xuewu.KLink;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网房间发现 — UDP 广播 + 监听。
 *
 * 协议：
 * - 广播端口：5233
 * - 消息格式：JSON { type:"kards_room", roomName, hostName, httpPort, wsPort, players, address }
 * - 广播间隔：2 秒
 *
 * 同时支持广播（房主）和扫描（客户端），可在同一实例上运行。
 */
public final class LanDiscovery {

    public interface RoomListener {
        void onRoomFound(JSONObject roomInfo);
    }

    private static final int DISCOVERY_PORT = 5233;
    private static final int BROADCAST_INTERVAL_MS = 2000;
    private static final String MESSAGE_TYPE = "kards_room";

    private final Context context;
    private final RoomListener listener;

    private final AtomicBoolean broadcasting = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private DatagramSocket socket;
    private Thread broadcastThread;
    private Thread scanThread;
    private WifiManager.MulticastLock multicastLock;

    // 当前广播的房间信息
    private String roomName = "KARDS Room";
    private String hostName = "Host";
    private int httpPort = 5231;
    private int wsPort = 5232;
    private int players = 0;

    public LanDiscovery(Context context, RoomListener listener) {
        this.context = context;
        this.listener = listener;
    }

    // ==================== 房间信息配置 ====================

    public void setRoomInfo(String roomName, String hostName, int httpPort, int wsPort, int players) {
        this.roomName = roomName != null ? roomName : "KARDS Room";
        this.hostName = hostName != null ? hostName : "Host";
        this.httpPort = httpPort > 0 ? httpPort : 5231;
        this.wsPort = wsPort > 0 ? wsPort : 5232;
        this.players = players;
    }

    // ==================== 广播（房主端） ====================

    /**
     * 开始广播房间信息。
     */
    public synchronized void startBroadcast() {
        if (broadcasting.get()) return;
        ensureSocket();
        broadcasting.set(true);
        broadcastThread = new Thread(new Runnable() {
            @Override
            public void run() {
                broadcastLoop();
            }
        }, "KLink-LAN-Broadcast");
        broadcastThread.setDaemon(true);
        broadcastThread.start();
    }

    public synchronized void stopBroadcast() {
        broadcasting.set(false);
        if (broadcastThread != null) {
            broadcastThread.interrupt();
            broadcastThread = null;
        }
    }

    private void broadcastLoop() {
        while (broadcasting.get()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", MESSAGE_TYPE);
                msg.put("roomName", roomName);
                msg.put("hostName", hostName);
                msg.put("httpPort", httpPort);
                msg.put("wsPort", wsPort);
                msg.put("players", players);
                msg.put("address", getLocalIp());

                byte[] data = msg.toString().getBytes("UTF-8");
                DatagramPacket packet = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        DISCOVERY_PORT);
                socket.send(packet);
            } catch (Exception ignored) {}

            try {
                Thread.sleep(BROADCAST_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ==================== 扫描（客户端） ====================

    /**
     * 开始扫描局域网房间。结果通过 RoomListener 回调。
     */
    public synchronized void startScan() {
        if (scanning.get()) return;
        ensureSocket();
        acquireMulticastLock();
        scanning.set(true);
        scanThread = new Thread(new Runnable() {
            @Override
            public void run() {
                scanLoop();
            }
        }, "KLink-LAN-Scan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    public synchronized void stopScan() {
        scanning.set(false);
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
        releaseMulticastLock();
    }

    private void scanLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (scanning.get()) {
            try {
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                JSONObject msg = new JSONObject(json);

                if (!MESSAGE_TYPE.equals(msg.optString("type"))) continue;
                if (!packet.getAddress().getHostAddress().equals(getLocalIp())) {
                    // 过滤掉自己的广播
                }

                // 使用实际来源地址
                msg.put("address", packet.getAddress().getHostAddress());
                if (!msg.has("httpPort")) msg.put("httpPort", msg.optInt("wsPort", 5231) - 1);

                if (listener != null) {
                    listener.onRoomFound(msg);
                }
            } catch (Exception e) {
                if (!scanning.get()) break;
            }
        }
    }

    // ==================== 生命周期 ====================

    public synchronized void destroy() {
        stopBroadcast();
        stopScan();
        closeSocket();
    }

    // ==================== 内部 ====================

    private void ensureSocket() {
        if (socket != null && !socket.isClosed()) return;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket.setSoTimeout(3000); // 3s timeout for scanning
        } catch (Exception e) {
            throw new RuntimeException("无法创建 UDP socket: " + e.getMessage(), e);
        }
    }

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        socket = null;
    }

    private String getLocalIp() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                return String.format("%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("KLink-LAN-Discovery");
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try { multicastLock.release(); } catch (Exception ignored) {}
            multicastLock = null;
        }
    }
}
