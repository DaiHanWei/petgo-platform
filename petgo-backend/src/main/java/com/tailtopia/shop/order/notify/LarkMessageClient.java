package com.tailtopia.shop.order.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lark 发消息客户端（Story 3-4）。
 *
 * <p>⚠️ <b>本仓此前没有发消息的能力</b>：{@code LarkContentClient} 只封了 Sheets 与 Drive，
 * {@code LarkOAuthClient} 是后台登录用的。全仓 grep {@code open-apis/im} 零命中。
 *
 * <p>🔴 <b>token 缓存范式照抄 {@code LarkContentClient}</b>（过期前 5 分钟刷新）但**没有共用**：
 * 那个类的缓存是它的私有字段，抽出来要动一个跑在生产上的定时发帖链路 ——
 * 为了省 20 行去碰它不划算。这里是第二份，两份的行为必须保持一致，
 * 改其中一份时请看一眼另一份。
 *
 * <p>🔴 <b>出网必须有超时</b>：没有超时的调用会把异步线程池挂满
 * （照 {@code PostHogAnalyticsClient} 的既定做法）。
 *
 * <p>🔒 <b>appSecret 绝不落日志</b>。
 */
@Component
public class LarkMessageClient {

    private static final Logger log = LoggerFactory.getLogger(LarkMessageClient.class);

    private final ShopOrderNotifyProperties props;
    private final RestClient rest;
    private final JsonMapper json = JsonMapper.builder().build();

    /** token 缓存（单实例部署 + 低频调用，synchronized 足够）。 */
    private String cachedToken;
    private Instant tokenExpireAt = Instant.EPOCH;

    public LarkMessageClient(ShopOrderNotifyProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())));
        this.rest = RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(rf).build();
    }

    /** 发送失败（网络 / 非 0 code / 权限）。调用方据此计 retry，**绝不让它冒到订单链路**。 */
    public static class LarkSendException extends RuntimeException {
        public LarkSendException(String message) {
            super(message);
        }
    }

    /**
     * 发一条纯文本消息。
     *
     * @throws LarkSendException 任何失败
     */
    public void sendText(String text) {
        Map<?, ?> resp;
        try {
            resp = rest.post()
                    .uri(b -> b.path("/open-apis/im/v1/messages")
                            .queryParam("receive_id_type", props.getReceiveIdType())
                            .build())
                    .header("Authorization", "Bearer " + tenantToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "receive_id", props.getReceiveId(),
                            "msg_type", "text",
                            // Lark 的 text 消息体是一个 **JSON 字符串**，不是对象。
                            "content", json.writeValueAsString(Map.of("text", text))))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            invalidateToken();
            throw new LarkSendException("发送失败：" + e.getClass().getSimpleName());
        }
        try {
            ensureOk(resp, "send_message");
        } catch (LarkSendException e) {
            // 🔴 **token 过期走的是这条路，不是上面那条**：Lark 对失效的 tenant_access_token
            //    返回 HTTP 200 + body code 非 0（99991663 / 99991661 / 99991664），
            //    RestClientException 根本不会触发。不在这里作废缓存，就会一直拿着同一个坏 token 重试，
            //    max-retries 撑不了三轮，队列里的订单全部转 FAILED —— 提醒从此静默，直到进程重启。
            //    非 token 类的错误（如 receive_id 配错）多丢一次 token 只是多一次取，代价可以忽略。
            invalidateToken();
            throw e;
        }
    }

    /** 取 tenant_access_token，缓存至官方过期时刻前 5 分钟。 */
    private synchronized String tenantToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpireAt)) {
            return cachedToken;
        }
        Map<?, ?> resp;
        try {
            resp = rest.post()
                    .uri("/open-apis/auth/v3/tenant_access_token/internal")
                    .contentType(MediaType.APPLICATION_JSON)
                    // 🔒 appSecret 只在这里用一次，不记日志、不回显。
                    .body(Map.of("app_id", props.getAppId(), "app_secret", props.getAppSecret()))
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new LarkSendException("取 token 失败：" + e.getClass().getSimpleName());
        }
        ensureOk(resp, "tenant_access_token");
        cachedToken = String.valueOf(resp.get("tenant_access_token"));
        long expireSeconds = resp.get("expire") instanceof Number n ? n.longValue() : 7200L;
        tokenExpireAt = Instant.now().plusSeconds(Math.max(60, expireSeconds - 300));
        return cachedToken;
    }

    private synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpireAt = Instant.EPOCH;
    }

    /** Lark 的 HTTP 200 不代表成功，要看 body 里的 {@code code}。 */
    private void ensureOk(Map<?, ?> resp, String action) {
        Object code = resp == null ? null : resp.get("code");
        if (code instanceof Number n && n.intValue() == 0) {
            return;
        }
        // 🔒 只记 code 与 msg，不记请求体（里面有 receive_id）。
        log.warn("Lark {} 返回非 0：code={} msg={}", action, code,
                resp == null ? null : resp.get("msg"));
        throw new LarkSendException(action + " 返回非 0：code=" + code);
    }
}
