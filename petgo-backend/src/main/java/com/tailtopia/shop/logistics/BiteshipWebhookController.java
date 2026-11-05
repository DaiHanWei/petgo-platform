package com.tailtopia.shop.logistics;

import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import com.tailtopia.shop.logistics.event.BiteshipWebhookReceivedEvent;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Biteship Webhook 接收（v1.4.2 shop Story 5.1 AC6，D-12 / I14）：{@code POST /api/v1/webhooks/biteship/{secret}}。
 *
 * <p>🔴 <b>路径密钥 + 回查，载荷只当通知</b>（Biteship 文档未写验签机制）：
 * <ul>
 *   <li>配置密钥为空 → 一律 404（fail closed）；密钥常量时间比较，不等 → 404。404 <b>不带 ProblemDetail 体</b>
 *       （避免 {@code instance} 回显带密钥的路径）；</li>
 *   <li>请求体 &gt; 64 KB → 413、JSON 解析失败 → 400，均不落表；体内无 {@code order_id}（Biteship 配置时的连通性测试）→ 200 不落表；</li>
 *   <li>正常：短事务写收件行（{@code payload} 只留白名单键，司机姓名 / 电话等一律丢弃）并发布
 *       {@link BiteshipWebhookReceivedEvent} → 提交后回 200，异步回查处理。落表失败 → 500 空体（Biteship 若重发则下次再收）。</li>
 * </ul>
 *
 * <p>🔒 不记请求体、不记密钥；access log 里路径已由 {@code LogPaths.redact} 打码。
 */
@RestController
public class BiteshipWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BiteshipWebhookController.class);

    static final int MAX_BODY_BYTES = 64 * 1024;

    /** 收件表 payload 白名单（AC6.4）。 */
    static final List<String> PAYLOAD_KEYS = List.of("event", "order_id", "courier_tracking_id",
            "courier_waybill_id", "courier_company", "courier_type", "status", "order_price", "updated_at");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BiteshipProperties props;
    private final BiteshipWebhookEventRepository inbox;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public BiteshipWebhookController(BiteshipProperties props, BiteshipWebhookEventRepository inbox,
            ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this(props, inbox, events, txManager, Clock.systemUTC());
    }

    BiteshipWebhookController(BiteshipProperties props, BiteshipWebhookEventRepository inbox,
            ApplicationEventPublisher events, PlatformTransactionManager txManager, Clock clock) {
        this.props = props;
        this.inbox = inbox;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 🔴 复审修复：先校验密钥与 {@code Content-Length}，再<b>有上限地</b>读请求体（最多 64 KB + 1 字节）——
     * 未鉴权的大包不会被整体读入内存。
     */
    @PostMapping("/api/v1/webhooks/biteship/{secret}")
    public ResponseEntity<Void> receive(@PathVariable("secret") String secret, HttpServletRequest request) {
        if (!secretMatches(secret)) {
            return ResponseEntity.notFound().build();
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();   // 无 / 伪 Content-Length（chunked）
        }
        return handle(body);
    }

    /** 已鉴权、体积合规后的处理（包级可见，单测直接覆盖）。 */
    ResponseEntity<Void> handle(byte[] body) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        if (root == null || !root.isObject()) {
            return ResponseEntity.badRequest().build();
        }
        JsonNode orderId = root.path("order_id");
        if (orderId.isMissingNode() || orderId.isNull() || orderId.asText().isBlank()) {
            return ResponseEntity.ok().build();     // Biteship 配置 Webhook 时的连通性测试
        }
        Map<String, Object> payload = whitelist(root);
        String event = root.path("event").isTextual() ? root.path("event").asText() : null;
        try {
            tx.executeWithoutResult(s -> {
                BiteshipWebhookEvent row = inbox.save(BiteshipWebhookEvent.receive(orderId.asText(), event, payload,
                        clock.instant()));
                // 事务内发布：AFTER_COMMIT 监听在提交后触发
                events.publishEvent(new BiteshipWebhookReceivedEvent(row.getId()));
            });
        } catch (RuntimeException e) {
            log.error("Biteship Webhook 落表失败 biteshipOrderId={} cause={}", orderId.asText(),
                    e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }

    /** 配置密钥为空 → false（fail closed）；常量时间比较。 */
    boolean secretMatches(String secret) {
        String configured = props.getWebhookSecret();
        if (configured == null || configured.isBlank() || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    /** 只保留白名单键，值转成普通 Java 对象（字符串 / 数字 / 布尔）。 */
    static Map<String, Object> whitelist(JsonNode root) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : PAYLOAD_KEYS) {
            JsonNode v = root.get(k);
            if (v == null || v.isNull() || v.isMissingNode()) {
                continue;
            }
            if (v.isNumber()) {
                out.put(k, v.numberValue());
            } else if (v.isBoolean()) {
                out.put(k, v.asBoolean());
            } else if (v.isValueNode()) {
                out.put(k, v.asText());
            }
            // 对象 / 数组：丢弃（白名单键按约定都是标量）
        }
        return out;
    }
}
