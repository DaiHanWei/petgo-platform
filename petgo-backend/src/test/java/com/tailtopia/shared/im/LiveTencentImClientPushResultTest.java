package com.tailtopia.shared.im;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * L0：腾讯 REST 成败判定（V1.3.4 Story 6.2 · AC4.2）。与 {@code postRest}「只对 ErrorCode 非 0 告警」同口径。
 *
 * <p>{@code pushOfflineChecked} 端到端用例走本机回环 {@link HttpServer}（不出网、无凭证）。
 */
class LiveTencentImClientPushResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个回环假腾讯 REST：记录请求体，回固定响应。 */
    private String fakeTencent(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static LiveTencentImClient client(String baseUrl) {
        ImProperties props = new ImProperties();
        props.setMode("live");
        props.setSdkAppId("20043419");
        props.setSecretKey("test-secret-key-not-real-0123456789");
        props.setUserSigTtlSeconds(86400);
        props.setAdminIdentifier("administrator");
        props.setRestBaseUrl(baseUrl);
        return new LiveTencentImClient(props);
    }

    @Test
    void checkedPushReturnsTrueOnErrorCodeZeroWithSameBodyAsPushOffline() throws IOException {
        LiveTencentImClient c = client(fakeTencent("{\"ActionStatus\":\"OK\",\"ErrorCode\":0}"));
        c.pushOffline("u_7", "Judul", "Isi", "OPS_ANNOUNCEMENT", "dlTok", "campTok");
        assertThat(c.pushOfflineChecked("u_7", "Judul", "Isi", "OPS_ANNOUNCEMENT", "dlTok", "campTok")).isTrue();

        assertThat(bodies).hasSize(2);
        ObjectNode a = (ObjectNode) JSON.readTree(bodies.get(0));
        ObjectNode b = (ObjectNode) JSON.readTree(bodies.get(1));
        a.remove("MsgRandom"); // 每次随机
        b.remove("MsgRandom");
        assertThat(b).isEqualTo(a);
        assertThat(a.get("To_Account").get(0).asText()).isEqualTo("u_7");
    }

    @Test
    void checkedPushReturnsFalseOnNonZeroErrorCode() throws IOException {
        LiveTencentImClient c = client(fakeTencent("{\"ActionStatus\":\"FAIL\",\"ErrorCode\":90001}"));
        assertThat(c.pushOfflineChecked("u_7", "t", "b", "OPS_ANNOUNCEMENT", "tok", "camp")).isFalse();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void checkedPushReturnsFalseOnTransportException() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        LiveTencentImClient c = client("http://127.0.0.1:" + closedPort); // 连接被拒
        assertThat(c.pushOfflineChecked("u_7", "t", "b", "OPS_ANNOUNCEMENT", "tok", "camp")).isFalse();
    }

    /** bug 604：撞限频（60007）也只发一次、按失败返回——不重推，防用户收到两条。 */
    @Test
    void rateLimitedPushIsNotRetried() throws IOException {
        LiveTencentImClient c = client(fakeTencent(
                "{\"ActionStatus\":\"FAIL\",\"ErrorCode\":60007,\"ErrorInfo\":\"Please slow down\"}"));
        assertThat(c.pushOfflineChecked("u_7", "t", "b", "OPS_ANNOUNCEMENT", "tok", "camp")).isFalse();
        c.pushOffline("u_9", "t", "b", "LIKE", "tok", null);
        assertThat(bodies).hasSize(2); // 每次调用恰好一次请求
    }

    @Test
    void errorCodeZeroIsSuccess() {
        assertThat(LiveTencentImClient.isSuccess(Map.of("ErrorCode", 0, "ActionStatus", "OK"))).isTrue();
    }

    @Test
    void missingErrorCodeIsSuccess() {
        assertThat(LiveTencentImClient.isSuccess(Map.of("ActionStatus", "OK"))).isTrue();
    }

    @Test
    void nonZeroErrorCodeIsFailure() {
        assertThat(LiveTencentImClient.isSuccess(Map.of("ErrorCode", 90001, "ErrorInfo", "x"))).isFalse();
    }

    @Test
    void nullBodyIsSuccess() {
        assertThat(LiveTencentImClient.isSuccess(null)).isTrue();
    }

    @Test
    void stubClientDefaultCheckedPushSucceeds() {
        TencentImClient stub = new StubTencentImClient(new ImProperties());
        assertThat(stub.pushOfflineChecked("u_1", "t", "b", "OPS_ANNOUNCEMENT", "tok", "camp")).isTrue();
    }
}
