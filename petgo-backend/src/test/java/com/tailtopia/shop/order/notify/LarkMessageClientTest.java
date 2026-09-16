package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：Lark 发消息客户端的 token 缓存与失效（Story 3-4 AC6）。
 *
 * <p>用进程内的 {@code com.sun.net.httpserver} 桩，<b>不出网</b>、不需要任何凭证 ——
 * 与真正的 Lark 无关，只验证客户端自己的状态机。
 *
 * <p>🎯 <b>本类的重点</b>：{@link #nonZeroBodyCodeInvalidatesTheCachedToken()}。
 * Lark 对失效的 tenant_access_token 返回的是 <b>HTTP 200 + body code 非 0</b>，
 * 不是 HTTP 401 —— 只在 {@code RestClientException} 分支作废缓存会漏掉它，
 * 结果是拿着同一个坏 token 一直重试，队列里的订单三轮内全转 FAILED，提醒从此静默。
 */
class LarkMessageClientTest {

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> sendBody = new AtomicReference<>(
            "{\"code\":0,\"msg\":\"ok\"}");
    private final AtomicReference<String> tokenValue = new AtomicReference<>("t-1");

    private ShopOrderNotifyProperties props;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal", ex -> {
            hits.add("token");
            respond(ex, "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\""
                    + tokenValue.get() + "\",\"expire\":7200}");
        });
        server.createContext("/open-apis/im/v1/messages", ex -> {
            hits.add("send");
            respond(ex, sendBody.get());
        });
        server.start();

        props = new ShopOrderNotifyProperties();
        props.setMode("live");
        props.setReceiveId("oc_stub");
        props.setAppId("cli_stub");
        props.setAppSecret("secret_stub");
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body)
            throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    @Test
    @DisplayName("token 取一次后缓存复用：发两条消息只取一次 token")
    void tokenIsCachedAcrossSends() {
        LarkMessageClient client = new LarkMessageClient(props);

        client.sendText("a");
        client.sendText("b");

        assertThat(hits).containsExactly("token", "send", "send");
    }

    @Test
    @DisplayName("🎯 HTTP 200 但 body code 非 0 → 抛错**并作废 token 缓存**，下一次重新取")
    void nonZeroBodyCodeInvalidatesTheCachedToken() {
        LarkMessageClient client = new LarkMessageClient(props);
        client.sendText("warm up"); // 先把 token 缓存热起来
        hits.clear();

        // Lark 的 token 失效长这样：HTTP 200，code=99991663。
        sendBody.set("{\"code\":99991663,\"msg\":\"tenant access token invalid\"}");
        assertThatThrownBy(() -> client.sendText("x"))
                .isInstanceOf(LarkMessageClient.LarkSendException.class);

        // 恢复正常后，客户端必须**重新取 token**，而不是接着用那个坏的。
        sendBody.set("{\"code\":0,\"msg\":\"ok\"}");
        tokenValue.set("t-2");
        client.sendText("y");

        assertThat(hits)
                .as("失败后没有重新取 token，就会拿着坏 token 撞满 max-retries，提醒静默到重启")
                .containsExactly("send", "token", "send");
    }

    @Test
    @DisplayName("Lark 的 HTTP 200 不代表成功 —— body code 非 0 必须当失败抛出")
    void httpOkWithErrorCodeIsAFailure() {
        sendBody.set("{\"code\":230001,\"msg\":\"bot is not in the chat\"}");

        assertThatThrownBy(() -> new LarkMessageClient(props).sendText("x"))
                .isInstanceOf(LarkMessageClient.LarkSendException.class)
                .hasMessageContaining("230001");
    }

    @Test
    @DisplayName("🔒 异常信息里不出现 appSecret / receiveId")
    void exceptionNeverLeaksCredentials() {
        sendBody.set("{\"code\":230001,\"msg\":\"bot is not in the chat\"}");

        assertThatThrownBy(() -> new LarkMessageClient(props).sendText("x"))
                .hasMessageNotContaining("secret_stub")
                .hasMessageNotContaining("oc_stub");
    }
}
