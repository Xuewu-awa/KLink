package com.android1939.kardsserver.http;

import com.android1939.kardsserver.ServerLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class SimpleHttpServer {
    public interface Handler {
        HttpResponse handle(HttpRequest request) throws Exception;
    }

    private static final class BadRequestException extends Exception {
        BadRequestException(String message) {
            super(message);
        }
    }

    private final String bindHost;
    private final int port;
    private final Handler handler;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public SimpleHttpServer(String bindHost, int port, Handler handler) {
        this.bindHost = bindHost;
        this.port = port;
        this.handler = handler;
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        InetAddress address = "0.0.0.0".equals(bindHost) ? null : InetAddress.getByName(bindHost);
        serverSocket = new ServerSocket(port, 50, address);
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "KardsHttpServer");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
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

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleSocket(socket);
                    }
                }, "KardsHttpClient");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception e) {
                if (running) {
                    running = false;
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (running && !socket.isClosed()) {
                HttpRequest request;
                try {
                    request = readRequest(socket, in);
                } catch (BadRequestException e) {
                    HttpResponse response = HttpResponse.text(400, e.getMessage());
                    response.closeConnection = true;
                    writeResponse(out, response, true);
                    ServerLog.add("http", "bad request -> 400");
                    break;
                }
                if (request == null) {
                    break;
                }
                HttpResponse response = handler.handle(request);
                if (response == null) {
                    response = HttpResponse.empty(404);
                }
                boolean close = shouldClose(request, response);
                writeResponse(out, response, close);
                ServerLog.add("http", request.method + " " + request.path + " -> " + response.statusCode);
                if (close) {
                    break;
                }
            }
        } catch (Exception e) {
            ServerLog.add("error", e.toString());
            try {
                HttpResponse response = HttpResponse.text(500, e.toString());
                response.closeConnection = true;
                writeResponse(new BufferedOutputStream(socket.getOutputStream()), response, true);
            } catch (Exception ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private HttpRequest readRequest(Socket socket, InputStream in) throws Exception {
        String requestLine = readLine(in);
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            throw new BadRequestException("Bad request line");
        }
        HttpRequest request = new HttpRequest();
        if (socket.getInetAddress() != null) {
            request.remoteAddress = socket.getInetAddress().getHostAddress();
        }
        request.method = parts[0].toUpperCase(Locale.US);
        request.rawPath = parts[1];
        if (parts.length >= 3 && parts[2].length() > 0) {
            request.version = parts[2];
        }
        try {
            parsePath(request, parts[1]);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Bad request target");
        }
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            request.headers.put(name, value);
            if ("content-length".equals(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (Exception ignored) {
                    throw new BadRequestException("Bad Content-Length");
                }
                if (contentLength < 0) {
                    throw new BadRequestException("Bad Content-Length");
                }
            }
        }
        if (line == null) {
            throw new BadRequestException("Incomplete headers");
        }
        request.body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = in.read(request.body, offset, contentLength - offset);
            if (read < 0) {
                throw new BadRequestException("Incomplete request body");
            }
            offset += read;
        }
        return request;
    }

    private void parsePath(HttpRequest request, String rawPath) throws Exception {
        int queryIndex = rawPath.indexOf('?');
        String path = queryIndex >= 0 ? rawPath.substring(0, queryIndex) : rawPath;
        request.path = URLDecoder.decode(path, "UTF-8");
        if (queryIndex < 0) {
            return;
        }
        String query = rawPath.substring(queryIndex + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            request.query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
    }

    private String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\n') {
                byte[] bytes = out.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, "ISO-8859-1");
            }
            out.write(current);
        }
        if (out.size() == 0) {
            return null;
        }
        return new String(out.toByteArray(), "ISO-8859-1");
    }

    private boolean shouldClose(HttpRequest request, HttpResponse response) {
        if (response != null && response.closeConnection) {
            return true;
        }
        String connection = request.header("connection");
        if (connection != null && "close".equalsIgnoreCase(connection.trim())) {
            return true;
        }
        if ("HTTP/1.0".equalsIgnoreCase(request.version)) {
            return connection == null || !"keep-alive".equalsIgnoreCase(connection.trim());
        }
        return false;
    }

    private void writeResponse(OutputStream out, HttpResponse response, boolean close) throws Exception {
        if (response == null) {
            response = HttpResponse.empty(404);
        }
        if (response.body == null) {
            response.body = new byte[0];
        }
        out.write(("HTTP/1.1 " + response.statusCode + " " + response.statusText + "\r\n").getBytes("ISO-8859-1"));
        if (response.contentType != null && response.contentType.length() > 0) {
            String contentTypeName = response.contentType.contains("application/json") ? "content-type" : "Content-Type";
            out.write((contentTypeName + ": " + response.contentType + "\r\n").getBytes("ISO-8859-1"));
        }
        out.write(("Content-Length: " + response.body.length + "\r\n").getBytes("ISO-8859-1"));
        out.write(("Date: " + httpDate() + "\r\n").getBytes("ISO-8859-1"));
        for (String key : response.headers.keySet()) {
            out.write((key + ": " + response.headers.get(key) + "\r\n").getBytes("ISO-8859-1"));
        }
        if (close) {
            out.write("Connection: close\r\n\r\n".getBytes("ISO-8859-1"));
        } else {
            out.write("Connection: keep-alive\r\n".getBytes("ISO-8859-1"));
            out.write("Keep-Alive: timeout=5\r\n\r\n".getBytes("ISO-8859-1"));
        }
        out.write(response.body);
        out.flush();
    }

    private String httpDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }
}
