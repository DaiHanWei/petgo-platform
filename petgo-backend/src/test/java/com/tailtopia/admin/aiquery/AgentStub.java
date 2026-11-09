package com.tailtopia.admin.aiquery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试用 agent 桩：记录请求，按 jobToken 返回预设结果（契约同 ai-query-agent）。 */
final class AgentStub implements AutoCloseable {

    record Req(String method, String path, String secret, String body) {
    }

    final List<Req> requests = new CopyOnWriteArrayList<>();
    /** jobToken → GET /jobs/{t} 的 (status, body)；缺省 404。 */
    final Map<String, Object[]> jobs = new ConcurrentHashMap<>();
    /** 路径 → 固定状态码 + 体（优先于默认行为）。 */
    final Map<String, Object[]> overrides = new ConcurrentHashMap<>();
    volatile int submitStatus = 202;
    volatile int deleteStatus = 200;
    private final HttpServer server;

    AgentStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        requests.add(new Req(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("X-Internal-Secret"), body));
        Object[] o = overrides.get(path);
        if (o != null) {
            send(ex, (int) o[0], (byte[]) o[1], (String) o[2]);
            return;
        }
        if ("POST".equals(ex.getRequestMethod()) && "/jobs".equals(path)) {
            // 同真实 agent：接收后该任务立即可查（QUEUED），已有结果的不覆盖
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"jobToken\":\"([^\"]+)\"").matcher(body);
            if (submitStatus == 202 && m.find()) {
                jobs.putIfAbsent(m.group(1), new Object[] {200, """
                        {"status":"QUEUED","answerMd":null,"sessionId":null,"files":[],"secs":0,"turns":null,"costUsd":null,"errorCode":null}"""});
            }
            send(ex, submitStatus, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        if ("DELETE".equals(ex.getRequestMethod()) && path.startsWith("/conversations/")) {
            send(ex, deleteStatus, "{\"deleted\":true}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/jobs/") && path.split("/").length == 3) {
            Object[] j = jobs.get(path.substring("/jobs/".length()));
            if (j == null) {
                send(ex, 404, "{\"error\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8), "application/json");
            } else {
                send(ex, (int) j[0], ((String) j[1]).getBytes(StandardCharsets.UTF_8), "application/json");
            }
            return;
        }
        send(ex, 404, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void send(HttpExchange ex, int status, byte[] body, String type) throws IOException {
        if (type != null) {
            ex.getResponseHeaders().add("Content-Type", type);
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    void done(String jobToken, String answer, String sessionId, String filesJson) {
        jobs.put(jobToken, new Object[] {200, """
                {"status":"DONE","answerMd":%s,"sessionId":"%s","files":%s,"secs":28,"turns":3,"costUsd":0.12345,"errorCode":null}"""
                .formatted(quote(answer), sessionId, filesJson)});
    }

    void failed(String jobToken, String errorCode) {
        jobs.put(jobToken, new Object[] {200, """
                {"status":"FAILED","answerMd":null,"sessionId":null,"files":[],"secs":5,"turns":1,"costUsd":0.01,"errorCode":"%s"}"""
                .formatted(errorCode)});
    }

    /** 模拟 agent 重启丢失运行中任务：之后 GET 返回 404。 */
    void forget(String jobToken) {
        jobs.remove(jobToken);
    }

    void running(String jobToken) {
        jobs.put(jobToken, new Object[] {200, """
                {"status":"RUNNING","answerMd":null,"sessionId":null,"files":[],"secs":3,"turns":null,"costUsd":null,"errorCode":null}"""});
    }

    List<Req> submits() {
        return requests.stream().filter(r -> "POST".equals(r.method()) && "/jobs".equals(r.path())).toList();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
