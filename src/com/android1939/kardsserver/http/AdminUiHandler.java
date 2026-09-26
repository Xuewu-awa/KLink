package com.android1939.kardsserver.http;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 静态资源服务：把 APK 内置的 {@code assets/admin-ui/**} 通过 HTTP 发出去。
 *
 * <h3>为什么不让 WebView 直接读 file:///android_asset/admin-ui/</h3>
 * 后台是 Vite 打包的 Vue SPA，用了 {@code <script type="module">} 和 17 个路由级
 * 动态 {@code import()} 分包。{@code file://} 协议下 ES module 与动态 import 会被
 * WebView 直接拦掉（opaque origin 的 CORS 限制），且 SPA 内部引用的是绝对路径
 * （{@code /admin-ui/assets/...}）。所以必须由服务器以 HTTP 发出 —— 这也正是桌面端
 * fyserver 的做法，路径与行为完全一致。
 *
 * <h3>安全</h3>
 * 只读、只服务 assets/admin-ui/ 下的文件，并对路径做穿越校验
 * （拒绝 {@code ..}、绝对路径、反斜杠），避免读到 assets 里的其它内容。
 * 后台数据的鉴权不在这里，而在 {@link AdminApiHandler}。
 */
public final class AdminUiHandler {

    /** URL 前缀 */
    public static final String PREFIX = "/admin-ui/";
    /** APK 内对应的 assets 目录 */
    private static final String ASSET_ROOT = "admin-ui";

    private final Context context;

    public AdminUiHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 该路径是否属于后台静态资源。 */
    public static boolean handles(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /**
     * 处理一个静态资源请求。
     * 路径不存在时回退到 index.html（SPA 入口别名，等价于桌面端的 AdminUiEntryMiddleware）。
     */
    public HttpResponse handle(HttpRequest request) {
        if (request == null || request.path == null) {
            return HttpResponse.empty(404);
        }

        String relative = request.path.substring(PREFIX.length());
        if (relative.isEmpty()) {
            relative = "index.html";
        }
        // 去掉查询串残留与首尾斜杠
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }

        if (!isSafeRelativePath(relative)) {
            return HttpResponse.text(404, "Not Found");
        }

        byte[] data = readAsset(ASSET_ROOT + "/" + relative);
        if (data == null && isSpaFallbackCandidate(relative)) {
            // 未命中的前端路由 → 交给 SPA 自己处理
            data = readAsset(ASSET_ROOT + "/index.html");
            if (data != null) {
                relative = "index.html";
            }
        }
        if (data == null) {
            return HttpResponse.text(404, "Not Found");
        }

        HttpResponse response = new HttpResponse();
        response.statusCode = 200;
        response.statusText = "OK";
        response.contentType = contentType(relative);
        response.body = data;
        // 后台产物随 APK 一起更新，URL 不变的情况下让 WebView 每次都回源，
        // 避免换版本后加载到旧的 JS/CSS（桌面端也踩过"改了页面没生效"的坑）。
        response.headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
        response.headers.put("Pragma", "no-cache");
        return response;
    }

    /**
     * 只有"看起来像页面导航"的未命中路径才回退到 index.html。
     * 静态资源（.js/.css/.png/.woff 等）缺失就该老实 404，
     * 否则浏览器拿到一坨 HTML 当 JS 解析，报错更难查。
     */
    private static boolean isSpaFallbackCandidate(String relative) {
        String lower = relative.toLowerCase();
        return !(lower.endsWith(".js") || lower.endsWith(".mjs")
                || lower.endsWith(".css") || lower.endsWith(".map")
                || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp")
                || lower.endsWith(".ico") || lower.endsWith(".woff") || lower.endsWith(".woff2")
                || lower.endsWith(".ttf") || lower.endsWith(".json"));
    }

    /** 拒绝空段、".."、绝对路径与反斜杠，防止穿越出 admin-ui 目录。 */
    private static boolean isSafeRelativePath(String relative) {
        if (relative.isEmpty()) {
            return false;
        }
        if (relative.indexOf('\\') >= 0) {
            return false;
        }
        if (relative.startsWith("/")) {
            return false;
        }
        String[] segments = relative.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private byte[] readAsset(String assetPath) {
        InputStream in = null;
        try {
            in = context.getAssets().open(assetPath);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * MIME 类型。{@code .js} 必须是 text/javascript —— 给成 application/octet-stream
     * 会让 WebView 拒绝执行，ES module 直接加载失败。
     */
    public static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
