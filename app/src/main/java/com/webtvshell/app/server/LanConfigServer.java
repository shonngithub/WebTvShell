package com.webtvshell.app.server;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import com.webtvshell.app.config.ConfigRepository;
import com.webtvshell.app.config.ValidationResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/** Minimal HTTP server for editing configuration from another device on the LAN. */
public final class LanConfigServer {
    public static final int DEFAULT_PORT = 6688;

    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = ConfigRepository.MAX_CONFIG_BYTES + 4096;

    private final Context context;
    private final ConfigRepository repository;
    private final Runnable onApplied;
    private final int port;
    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;

    public LanConfigServer(Context context, ConfigRepository repository, Runnable onApplied) {
        this(context, repository, onApplied, DEFAULT_PORT);
    }

    public LanConfigServer(Context context, ConfigRepository repository, Runnable onApplied,
                           int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口无效: " + port);
        this.context = context.getApplicationContext();
        this.repository = repository;
        this.onApplied = onApplied;
        this.port = port;
    }

    public void startServer() {
        synchronized (lifecycleLock) {
            if (running) return;
            final ServerSocket socket;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
            } catch (IOException e) {
                throw new IllegalStateException("无法启动局域网配置服务: " + e.getMessage(), e);
            }
            serverSocket = socket;
            workers = Executors.newFixedThreadPool(4, namedFactory("WebTvShell-http"));
            running = true;
            acceptThread = new Thread(this::acceptLoop, "WebTvShell-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    public void stopServer() {
        synchronized (lifecycleLock) {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
                serverSocket = null;
            }
            if (workers != null) {
                workers.shutdownNow();
                workers = null;
            }
            acceptThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Returns a browser-ready address. IP changes are reflected on each call. */
    public String getAddress() {
        return "http://" + findLanAddress() + ":" + port;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(10_000);
                ExecutorService pool = workers;
                if (pool == null) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    pool.execute(() -> handleClient(client));
                } catch (RejectedExecutionException ignored) {
                    // Activity teardown can shut down the pool just after accept() returns.
                    closeQuietly(client);
                }
            } catch (SocketException e) {
                if (running) stopServer();
            } catch (IOException e) {
                if (running) stopServer();
            }
        }
    }

    private void handleClient(Socket socket) {
        InetAddress remote = socket.getInetAddress();
        if (remote == null || (!remote.isLoopbackAddress() && !remote.isSiteLocalAddress())) {
            closeQuietly(socket);
            return;
        }
        Response response = null;
        try (Socket client = socket;
             InputStream input = new BufferedInputStream(client.getInputStream());
             OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            String method = "GET";
            try {
                Request request = readRequest(input);
                method = request.method;
                response = route(request);
            } catch (HttpError e) {
                response = jsonError(e.status, e.getMessage());
            } catch (Exception e) {
                response = jsonError(e instanceof IllegalArgumentException || e instanceof JSONException
                        ? 400 : 500, safeMessage(e));
            }
            writeResponse(output, method, response);
            output.flush();
        } catch (IOException e) {
            return;
        }
        if (response.afterSend != null) {
            final Runnable callback = response.afterSend;
            Thread delayed = new Thread(() -> {
                try {
                    Thread.sleep(700);
                    callback.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "WebTvShell-apply");
            delayed.setDaemon(true);
            delayed.start();
        }
    }

    private Response route(Request request) throws Exception {
        String path = request.path;
        if ("OPTIONS".equals(request.method)) {
            return new Response(204, "text/plain; charset=utf-8", new byte[0]);
        }
        if ("GET".equals(request.method) && "/api/status".equals(path)) {
            JSONObject status = new JSONObject();
            status.put("running", running);
            status.put("address", getAddress());
            status.put("port", port);
            status.put("appVersion", appVersion());
            status.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            PackageInfo webViewPackage = Build.VERSION.SDK_INT >= 26
                    ? WebView.getCurrentWebViewPackage() : null;
            status.put("webViewVersion", webViewPackage == null ? "" : webViewPackage.versionName);
            status.put("activeConfigVersion", repository.getActiveVersion());
            status.put("maxConfigBytes", ConfigRepository.MAX_CONFIG_BYTES);
            return json(200, status);
        }
        if ("GET".equals(request.method) && "/api/config/active".equals(path)) {
            return rawJson(200, repository.getActiveJson());
        }
        if ("GET".equals(request.method) && "/api/config/draft".equals(path)) {
            return rawJson(200, repository.getDraftJson());
        }
        if (("PUT".equals(request.method) || "POST".equals(request.method))
                && "/api/config/draft".equals(path)) {
            String config = configPayload(request.bodyText());
            repository.saveDraft(config);
            return json(200, ok("草稿已保存"));
        }
        if ("POST".equals(request.method) && "/api/config/validate".equals(path)) {
            String body = request.bodyText().trim();
            String config = body.isEmpty() ? repository.getDraftJson() : configPayload(body);
            return json(200, repository.validate(config).toJson());
        }
        if ("POST".equals(request.method) && "/api/config/apply".equals(path)) {
            JSONObject result = repository.applyDraft();
            result.put("ok", true);
            result.put("restarting", onApplied != null);
            Response response = json(200, result);
            response.afterSend = onApplied;
            return response;
        }
        if ("GET".equals(request.method)
                && ("/api/config/history".equals(path) || "/api/history".equals(path))) {
            JSONObject result = new JSONObject();
            result.put("history", repository.getHistory());
            return json(200, result);
        }
        String rollbackPrefix = "/api/config/rollback/";
        if ("POST".equals(request.method) && path.startsWith(rollbackPrefix)) {
            String version = decode(path.substring(rollbackPrefix.length()));
            JSONObject result = repository.rollback(version);
            result.put("ok", true);
            result.put("restarting", onApplied != null);
            Response response = json(200, result);
            response.afterSend = onApplied;
            return response;
        }
        if ("GET".equals(request.method)
                && ("/api/config/export".equals(path) || "/api/export".equals(path))) {
            Response response = rawJson(200, repository.exportActive());
            response.headers.put("Content-Disposition", "attachment; filename=webtvshell-config.json");
            return response;
        }
        if ("POST".equals(request.method)
                && ("/api/config/import".equals(path) || "/api/import".equals(path))) {
            ValidationResult result = repository.importDraft(configPayload(request.bodyText()));
            JSONObject json = result.toJson();
            json.put("savedAsDraft", result.isValid());
            return json(result.isValid() ? 200 : 422, json);
        }
        if ("POST".equals(request.method)
                && ("/api/rules/match-test".equals(path) || "/api/match-test".equals(path))) {
            JSONObject input = new JSONObject(request.bodyText());
            JSONObject config = input.optJSONObject("config");
            return json(200, repository.matchTest(input.optString("url", ""),
                    config == null ? repository.getDraftJson() : config.toString()));
        }
        if (path.startsWith("/api/")) throw new HttpError(404, "API 不存在");
        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            throw new HttpError(405, "请求方法不支持");
        }
        return serveAsset(path);
    }

    private Response serveAsset(String requestPath) throws IOException {
        String path = decode(requestPath);
        if ("/".equals(path)) path = "/index.html";
        if ("/demo".equals(path) || "/demo/".equals(path)) path = "/demo.html";
        if (path.contains("..") || path.indexOf('\0') >= 0 || path.contains("\\")) {
            throw new HttpError(400, "静态资源路径无效");
        }
        String assetName = "www" + path;
        try (InputStream input = context.getAssets().open(assetName)) {
            return new Response(200, mimeType(path), readAll(input, 2 * 1024 * 1024));
        } catch (FileNotFoundException e) {
            if ("/demo.html".equals(path)) {
                String html = "<!doctype html><meta charset=utf-8><title>WebTvShell</title>"
                        + "<style>body{font:16px sans-serif;max-width:720px;margin:40px auto;padding:0 16px}</style>"
                        + "<h1>WebTvShell</h1><p>配置服务正在运行。</p>"
                        + "<p><a href=\"/\">打开配置页面</a></p>";
                return new Response(200, "text/html; charset=utf-8",
                        html.getBytes(StandardCharsets.UTF_8));
            }
            throw new HttpError(404, "静态资源不存在");
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        byte[] headerBytes = readHeaders(input);
        String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\\r\\n");
        if (lines.length == 0) throw new HttpError(400, "缺少请求行");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1.")) {
            throw new HttpError(400, "请求行无效");
        }
        String method = first[0].toUpperCase(Locale.US);
        String target = first[1];
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;
        if (!path.startsWith("/")) throw new HttpError(400, "请求路径无效");

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                    lines[i].substring(colon + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpError(400, "不支持 Transfer-Encoding");
        }
        int length = 0;
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new HttpError(400, "Content-Length 无效");
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new HttpError(413, "请求体超过大小上限");
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) throw new HttpError(400, "请求体不完整");
            offset += count;
        }
        return new Request(method, path, headers, body);
    }

    private static byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int state = 0;
        while (output.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new HttpError(400, "请求头不完整");
            output.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) return output.toByteArray();
        }
        throw new HttpError(431, "请求头超过大小上限");
    }

    private static void writeResponse(OutputStream output, String method, Response response)
            throws IOException {
        byte[] body = response.body;
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(response.status).append(' ')
                .append(statusText(response.status)).append("\r\n")
                .append("Content-Type: ").append(response.contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'\r\n");
        for (Map.Entry<String, String> header : response.headers.entrySet()) {
            headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!"HEAD".equals(method)) output.write(body);
    }

    private static Response json(int status, JSONObject object) {
        return rawJson(status, object.toString());
    }

    private static Response rawJson(int status, String json) {
        return new Response(status, "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static Response jsonError(int status, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("ok", false);
            error.put("error", message == null ? statusText(status) : message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return json(status, error);
    }

    private static JSONObject ok(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("message", message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    /** Accepts a raw config object or {"config": {...}} for simple browser clients. */
    private static String configPayload(String body) throws JSONException {
        JSONObject value = new JSONObject(body);
        JSONObject nested = value.optJSONObject("config");
        return (nested == null ? value : nested).toString();
    }

    private String appVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static String findLanAddress() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (address.isSiteLocalAddress()) return host;
                    fallback = host;
                }
            }
        } catch (SocketException ignored) {
        }
        return fallback;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            throw new HttpError(400, "URL 编码无效");
        }
    }

    private static byte[] readAll(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > maxBytes) throw new HttpError(413, "静态资源超过大小上限");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 422: return "Unprocessable Entity";
            case 431: return "Request Header Fields Too Large";
            default: return "Internal Server Error";
        }
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof JSONException) {
            return error.getMessage();
        }
        return "服务器内部错误";
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static ThreadFactory namedFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new HashMap<>();
        Runnable afterSend;

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
