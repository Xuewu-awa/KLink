package com.xuewu.KLink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 透明代理 — 监听 127.0.0.1:5231，双向转发到远程服务器。
 *
 * 同时处理 HTTP 和 WebSocket 流量：
 * - HTTP:  逐请求转发（Get/Post → 远程 → 响应回传）
 * - WebSocket: 升级握手后隧道转发（双向裸字节）
 */
public final class ProxyServer {

    private final String remoteHost;
    private final int remotePort;
    private final int listenPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public ProxyServer(String remoteHost, int remotePort) {
        this(remoteHost, remotePort, 5231);
    }

    public ProxyServer(String remoteHost, int remotePort, int listenPort) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.listenPort = listenPort;
    }

    /**
     * 启动代理。在后台线程 accept 连接。
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", listenPort), 50);
        running.set(true);

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "KLink-Proxy-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 停止代理，关闭所有连接。
     */
    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    // ==================== Accept Loop ====================

    private void acceptLoop() {
        while (running.get()) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(30000);
            } catch (IOException e) {
                if (!running.get()) break;
                continue;
            }

            final Socket finalClient = client;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    handleConnection(finalClient);
                }
            }, "KLink-Proxy-Client").start();
        }
    }

    // ==================== Connection Handler ====================

    private void handleConnection(Socket client) {
        Socket remote = null;
        try {
            remote = new Socket();
            remote.setTcpNoDelay(true);
            remote.setSoTimeout(30000);
            remote.connect(new InetSocketAddress(remoteHost, remotePort), 10000);

            // 双向转发
            Thread c2r = forward(client.getInputStream(), remote.getOutputStream(), "C→R");
            Thread r2c = forward(remote.getInputStream(), client.getOutputStream(), "R→C");

            // 最长等待 60 秒，超时后强制关闭（防止单边连接挂起导致线程泄漏）
            c2r.join(60000);
            r2c.join(60000);

        } catch (Exception ignored) {
            // 连接中断是正常情况（游戏关闭、网络波动等）
        } finally {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    // ==================== Stream Forwarding ====================

    /**
     * 单向转发线程：从 in 读取全部字节写入 out，直到 EOF 或异常。
     */
    private Thread forward(final InputStream in, final OutputStream out, String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32768];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {}
                // 一方断开时关闭另一方
                try { out.close(); } catch (IOException ignored) {}
            }
        }, "KLink-Proxy-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ==================== Utilities ====================

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
