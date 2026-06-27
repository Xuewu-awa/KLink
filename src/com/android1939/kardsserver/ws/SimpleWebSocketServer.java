package com.android1939.kardsserver.ws;

import android.util.Base64;

import com.android1939.kardsserver.MatchManager;
import com.android1939.kardsserver.ServerLog;
import com.android1939.kardsserver.ServerConfig;
import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.model.UserRecord;
import com.android1939.kardsserver.util.JwtUtil;
import com.android1939.kardsserver.util.TimeUtil;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleWebSocketServer {
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String CLOSE_FRAME = "\u0000__KARDS_WS_CLOSE__";

    private final ServerConfig config;
    private final KardsDatabase database;
    private final MatchManager matches;
    private final Map<Integer, Client> clients = new ConcurrentHashMap<Integer, Client>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public SimpleWebSocketServer(ServerConfig config, KardsDatabase database, MatchManager matches) {
        this.config = config;
        this.database = database;
        this.matches = matches;
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        InetAddress address = "0.0.0.0".equals(config.bindHost) ? null : InetAddress.getByName(config.bindHost);
        serverSocket = new ServerSocket(config.wsPort, 50, address);
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "KardsWebSocketServer");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        for (Client client : clients.values()) {
            client.close();
        }
        clients.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
        serverSocket = null;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isOnline(int userId) {
        return clients.containsKey(userId);
    }

    public boolean kick(int userId) {
        Client client = clients.remove(userId);
        matches.kickPlayer(userId);
        database.setOnline(userId, false);
        ServerLog.add("room", "kick player " + userId);
        if (client == null) {
            return false;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("channel", "notification");
            message.put("message", "kicked");
            message.put("context", "room");
            client.send(message.toString());
            client.sendClose();
        } catch (Exception ignored) {
        }
        client.close();
        return true;
    }

    public int onlineCount() {
        return clients.size();
    }

    public boolean sendToId(int userId, JSONObject message) {
        Client client = clients.get(userId);
        if (client == null) {
            return false;
        }
        client.send(message.toString());
        return true;
    }

    public int broadcast(JSONObject message) {
        int sent = 0;
        String text = message == null ? "{}" : message.toString();
        for (Client client : clients.values()) {
            client.send(text);
            sent++;
        }
        return sent;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "KardsWebSocketClient");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception e) {
                if (running) {
                    running = false;
                }
            }
        }
    }

    private void handle(Socket socket) {
        Client client = null;
        try {
            socket.setSoTimeout(0);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            Handshake handshake = readHandshake(in);
            UserRecord user = authenticate(handshake);
            if (user == null) {
                writeHttpError(out, 401, "Unauthorized");
                return;
            }
            String key = handshake.headers.get("sec-websocket-key");
            if (key == null) {
                writeHttpError(out, 400, "Bad Request");
                return;
            }
            writeHandshake(out, key, handshake.selectedProtocol());
            client = new Client(user.id, socket, in, out);
            clients.put(user.id, client);
            matches.setOnline(user.id, true);
            database.setOnline(user.id, true);
            ServerLog.add("ws", "player " + user.id + " connected");
            readLoop(client);
        } catch (Exception ignored) {
        } finally {
            if (client != null) {
                clients.remove(client.userId);
                matches.setOnline(client.userId, false);
                database.setOnline(client.userId, false);
                ServerLog.add("ws", "player " + client.userId + " disconnected");
                client.close();
            } else {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void readLoop(Client client) throws Exception {
        while (running) {
            String text = readFrame(client.in);
            if (text == null) {
                break;
            }
            if (CLOSE_FRAME.equals(text)) {
                client.sendClose();
                break;
            }
            handleMessage(client, text);
        }
    }

    private void handleMessage(Client client, String text) {
        try {
            JSONObject data = new JSONObject(text);
            String channel = data.optString("channel");
            if ("ping".equals(channel)) {
                JSONObject response = baseMessage("pong", "ping", "", client.userId, "");
                client.send(response.toString());
                return;
            }
            if ("touchcard".equals(channel) || "emoji".equals(channel)) {
                forward(client, data, channel, data.optString("message"));
                return;
            }
            if ("notification".equals(channel)) {
                String message = data.optString("message");
                if ("websocketcheck".equals(message) || "matchaction".equals(message) || "im_here".equals(message)) {
                    forward(client, data, "notification", message);
                }
            }
        } catch (Exception ignored) {
            client.send("Echo: " + text);
        }
    }

    private void forward(Client client, JSONObject data, String channel, String message) throws Exception {
        String receiver = data.optString("receiver");
        JSONObject response = baseMessage(message, channel, data.opt("context"), client.userId, receiver);
        try {
            int receiverId = Integer.parseInt(receiver);
            sendToId(receiverId, response);
        } catch (Exception ignored) {
        }
    }

    private JSONObject baseMessage(String message, String channel, Object context, int sender, String receiver) throws Exception {
        JSONObject response = new JSONObject();
        response.put("message", message);
        response.put("channel", channel);
        response.put("context", context == null ? "" : context);
        response.put("timestamp", TimeUtil.nowIso());
        response.put("sender", sender);
        response.put("receiver", receiver == null ? "" : receiver);
        return response;
    }

    private UserRecord authenticate(Handshake handshake) throws Exception {
        List<String> tokens = extractTokens(handshake);
        for (String token : tokens) {
            UserRecord exact = database.findUserByJwt(token);
            if (exact != null) {
                return matches.isKicked(exact.id) ? null : exact;
            }
            try {
                JSONObject payload = JwtUtil.verify(config.jwtSecret, token);
                int userId = payload.optInt("user_id");
                UserRecord user = database.findUserById(userId);
                if (user != null && tokenMatchesUserSession(token, payload, user)) {
                    if (matches.isKicked(user.id)) {
                        return null;
                    }
                    return user;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean tokenMatchesUserSession(String token, JSONObject payload, UserRecord user) {
        try {
            if (user.playerJwt == null || user.playerJwt.length() == 0) {
                return false;
            }
            if (token.equals(user.playerJwt)) {
                return true;
            }
            JSONObject serverPayload = JwtUtil.verify(config.jwtSecret, user.playerJwt);
            long clientExp = payload.optLong("exp", 0);
            long serverExp = serverPayload.optLong("exp", 0);
            return clientExp > 0 && serverExp > 0 && Math.abs(clientExp - serverExp) < 86400;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> extractTokens(Handshake handshake) throws Exception {
        List<String> tokens = new ArrayList<String>();
        String auth = handshake.headers.get("authorization");
        addNormalizedTokens(tokens, auth);
        addNormalizedTokens(tokens, tokenFromRequestLine(handshake.requestLine));
        addNormalizedTokens(tokens, handshake.headers.get("x-authorization"));
        addNormalizedTokens(tokens, handshake.headers.get("x-token"));
        addNormalizedTokens(tokens, handshake.headers.get("sec-websocket-protocol"));
        return tokens;
    }

    private void addNormalizedTokens(List<String> tokens, String value) throws Exception {
        if (value == null) {
            return;
        }
        String[] parts = value.split(",");
        for (String part : parts) {
            String token = normalizeAuthToken(part);
            if (token != null && !tokens.contains(token)) {
                tokens.add(token);
            }
            int space = part.indexOf(' ');
            while (space > 0 && space + 1 < part.length()) {
                String tail = normalizeAuthToken(part.substring(space + 1));
                if (tail != null && !tokens.contains(tail)) {
                    tokens.add(tail);
                }
                space = part.indexOf(' ', space + 1);
            }
        }
    }

    private String normalizeAuthToken(String value) {
        if (value == null) {
            return null;
        }
        String token = value.trim();
        if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.length() == 0) {
            return null;
        }
        if (token.regionMatches(true, 0, "Authorization:", 0, 14)) {
            token = token.substring(14).trim();
        }
        if (token.regionMatches(true, 0, "JWT ", 0, 4)) {
            token = token.substring(4).trim();
        } else if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        } else if (token.regionMatches(true, 0, "JWT:", 0, 4)) {
            token = token.substring(4).trim();
        } else if (token.regionMatches(true, 0, "Bearer:", 0, 7)) {
            token = token.substring(7).trim();
        } else if (token.regionMatches(true, 0, "JWT%20", 0, 6)) {
            token = token.substring(6).trim();
        } else if (token.regionMatches(true, 0, "Bearer%20", 0, 9)) {
            token = token.substring(9).trim();
        }
        if (token.startsWith("=")) {
            token = token.substring(1).trim();
        }
        if (token.length() == 0 || "ws".equalsIgnoreCase(token)) {
            return null;
        }
        if (token.indexOf('.') < 0) {
            return null;
        }
        return token;
    }

    private String tokenFromRequestLine(String requestLine) throws Exception {
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        int queryIndex = parts[1].indexOf('?');
        if (queryIndex < 0) {
            return null;
        }
        String query = parts[1].substring(queryIndex + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            key = URLDecoder.decode(key, "UTF-8");
            if ("token".equalsIgnoreCase(key) || "jwt".equalsIgnoreCase(key) || "authorization".equalsIgnoreCase(key)) {
                return normalizeAuthToken(URLDecoder.decode(value, "UTF-8"));
            }
        }
        return null;
    }

    private Handshake readHandshake(InputStream in) throws Exception {
        Handshake handshake = new Handshake();
        handshake.requestLine = readLine(in);
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                handshake.headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
        }
        return handshake;
    }

    private void writeHandshake(OutputStream out, String key, String protocol) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        String accept = Base64.encodeToString(digest.digest((key + MAGIC).getBytes("ISO-8859-1")), Base64.NO_WRAP);
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 101 Switching Protocols\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        if (protocol != null && protocol.length() > 0) {
            response.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");
        }
        response.append("\r\n");
        out.write(response.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    private void writeHttpError(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes("UTF-8");
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private String readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7f;
        if (length == 126) {
            length = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (in.read() & 0xff);
            }
        }
        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask);
        }
        if (length > 1024 * 1024) {
            throw new IllegalArgumentException("Frame too large");
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        if (opcode == 8) return CLOSE_FRAME;
        return new String(payload, "UTF-8");
    }

    private void readFully(InputStream in, byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IllegalArgumentException("Unexpected EOF");
            offset += read;
        }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), "ISO-8859-1");
            }
            out.write(current);
            previous = current;
        }
        return out.size() == 0 ? null : new String(out.toByteArray(), "ISO-8859-1");
    }

    private static final class Handshake {
        String requestLine;
        final Map<String, String> headers = new HashMap<String, String>();

        String selectedProtocol() {
            String protocol = headers.get("sec-websocket-protocol");
            if (protocol == null) {
                return null;
            }
            String[] parts = protocol.split(",");
            for (String part : parts) {
                if ("ws".equalsIgnoreCase(part.trim())) {
                    return "ws";
                }
            }
            return null;
        }
    }

    private static final class Client {
        final int userId;
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Client(int userId, Socket socket, InputStream in, OutputStream out) {
            this.userId = userId;
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        synchronized void send(String text) {
            try {
                byte[] payload = text.getBytes("UTF-8");
                out.write(0x81);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length <= 65535) {
                    out.write(126);
                    out.write((payload.length >> 8) & 0xff);
                    out.write(payload.length & 0xff);
                } else {
                    out.write(127);
                    long length = payload.length;
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((length >> (8 * i)) & 0xff));
                    }
                }
                out.write(payload);
                out.flush();
            } catch (Exception ignored) {
            }
        }

        synchronized void sendClose() {
            try {
                out.write(0x88);
                out.write(0);
                out.flush();
            } catch (Exception ignored) {
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
