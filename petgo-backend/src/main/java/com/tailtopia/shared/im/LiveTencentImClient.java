package com.tailtopia.shared.im;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 腾讯 IM live 客户端（Story 5.5 增量，{@code petgo.im.mode=live} 装配）。
 *
 * <p>两类能力，<b>纯 JDK</b> 完成 UserSig 签名（无需引腾讯 SDK），REST 经 Spring {@link RestClient}：
 * <ul>
 *   <li><b>UserSig 签名（TLSSigAPIv2）</b>：{@code HMAC-SHA256(secretKey, content)} → 组 JSON →
 *       {@link Deflater}(zlib) → 腾讯变体 base64url（{@code + / =} → {@code * - _}）。
 *       注入固定 {@link Clock} 即<b>确定性输出</b>，可 L0 单测断言（非占位、真验）。</li>
 *   <li><b>REST 编排</b>：{@code account_import}（幂等建号，不计 MAU）+ {@code sendmsg}（C2C 系统消息）。
 *       C2C 会话无需服务端建会话——{@link #createConversation} 仅返回确定性会话 id，客户端按对端 userID 开会话。</li>
 * </ul>
 *
 * <p>护栏：SecretKey 仅本类持有、<b>绝不下发客户端 / 绝不入日志</b>；UserSig 短时（{@code userSigTtlSeconds}）；
 * 后端<b>不持 IM 长连接、不中转聊天媒体</b>（实时收发由客户端 SDK 直连）；REST 失败仅记<b>异常类名</b>（不泄正文/凭证），
 * 由调用方决定是否阻断（建号/系统消息失败<b>不阻断</b>已成业务，见 {@code ConsultAcceptService}/{@code AdminVetService}）。
 *
 * <p>真实 REST 建号 / 系统消息 / 真机收发属 <b>L2</b>（需真实 SDKAppID/SecretKey + 数据中心，待本地）；
 * UserSig 签名确定性属 <b>L0</b>（云端真验）。
 */
public class LiveTencentImClient implements TencentImClient {

    private static final Logger log = LoggerFactory.getLogger(LiveTencentImClient.class);

    private final ImProperties props;
    private final Clock clock;
    private final RestClient rest;

    public LiveTencentImClient(ImProperties props) {
        this(props, Clock.systemUTC());
    }

    /** 测试用：注入固定 {@link Clock} 断言 UserSig 签名确定性（L0）。 */
    LiveTencentImClient(ImProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(java.time.Duration.ofSeconds(5));
        rf.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.rest = RestClient.builder().baseUrl(props.getRestBaseUrl()).requestFactory(rf).build();
    }

    // ===== UserSig 签名（TLSSigAPIv2，纯 JDK，L0 确定性） =====

    @Override
    public UserSig signUserSig(String imUserId) {
        String sig = genUserSig(imUserId, props.getUserSigTtlSeconds());
        return new UserSig(imUserId, sig, props.getSdkAppId(), props.getUserSigTtlSeconds());
    }

    /**
     * 生成 UserSig（TLSSigAPIv2 等价实现）。{@code time} 取注入 {@link Clock}（秒），故同输入 → 同输出。
     */
    private String genUserSig(String identifier, long expire) {
        long sdkAppId = parseSdkAppId(props.getSdkAppId());
        long time = clock.instant().getEpochSecond();
        String sig = hmacSha256(identifier, sdkAppId, time, expire);
        // 固定字段顺序构造 JSON（腾讯按 key 解析，顺序不影响校验；固定顺序保证本地确定性）。
        String doc = "{"
                + "\"TLS.ver\":\"2.0\","
                + "\"TLS.identifier\":\"" + identifier + "\","
                + "\"TLS.sdkappid\":" + sdkAppId + ","
                + "\"TLS.expire\":" + expire + ","
                + "\"TLS.time\":" + time + ","
                + "\"TLS.sig\":\"" + sig + "\""
                + "}";
        byte[] compressed = deflate(doc.getBytes(StandardCharsets.UTF_8));
        return base64Url(compressed);
    }

    /** content 串顺序固定为 identifier→sdkappid→time→expire（与腾讯校验端一致）。 */
    private String hmacSha256(String identifier, long sdkAppId, long time, long expire) {
        String content = "TLS.identifier:" + identifier + "\n"
                + "TLS.sdkappid:" + sdkAppId + "\n"
                + "TLS.time:" + time + "\n"
                + "TLS.expire:" + expire + "\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw); // 标准 base64（与腾讯 sig 字段一致）
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 必然可用；异常仅记类名，绝不外泄 SecretKey。
            throw new IllegalStateException("UserSig 签名失败: " + e.getClass().getSimpleName());
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] buf = new byte[2048];
        int len = deflater.deflate(buf);
        deflater.end();
        return Arrays.copyOfRange(buf, 0, len);
    }

    /** 腾讯变体 base64url：标准 base64 后 {@code +}→{@code *}、{@code /}→{@code -}、{@code =}→{@code _}。 */
    private static String base64Url(byte[] input) {
        byte[] b64 = Base64.getEncoder().encode(input);
        for (int i = 0; i < b64.length; i++) {
            switch (b64[i]) {
                case '+' -> b64[i] = '*';
                case '/' -> b64[i] = '-';
                case '=' -> b64[i] = '_';
                default -> {
                    // 普通 base64 字符保持不变
                }
            }
        }
        return new String(b64, StandardCharsets.UTF_8);
    }

    private static long parseSdkAppId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalStateException("IM SDKAppID 配置无效（须为数字）");
        }
    }

    // ===== REST 编排（L2：真实建号 / 系统消息） =====

    @Override
    public void ensureAccount(String imUserId, String displayName) {
        // account_import 幂等：账号已存在腾讯返回 OK（ErrorCode=0）/已导入码，均视为成功。不计 MAU。
        Map<String, Object> body = displayName == null || displayName.isBlank()
                ? Map.of("UserID", imUserId)
                : Map.of("UserID", imUserId, "Nick", displayName);
        postRest("/v4/im_open_login_svc/account_import", body, "ensureAccount");
    }

    @Override
    public String createConversation(String userImId, String vetImId) {
        // C2C 无需服务端建会话：返回确定性会话 id，客户端按对端 userID 开会话。
        return "c2c-" + userImId + "-" + vetImId;
    }

    @Override
    public void sendSystemMessage(String conversationId, String text) {
        // 从 C2C 会话 id（c2c-<user>-<vet>）解析收发双方：系统消息以兽医身份发给用户，落进双方 C2C 会话。
        String[] parts = conversationId == null ? new String[0] : conversationId.split("-");
        if (parts.length != 3 || !"c2c".equals(parts[0])) {
            log.warn("[IM-live] 跳过系统消息：会话 id 非 C2C 约定格式");
            return;
        }
        String userImId = parts[1];
        String vetImId = parts[2];
        Map<String, Object> body = Map.of(
                "SyncOtherMachine", 2, // 不同步到发送方（系统消息）
                "From_Account", vetImId,
                "To_Account", userImId,
                "MsgRandom", ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE),
                "MsgBody", java.util.List.of(Map.of(
                        "MsgType", "TIMTextElem",
                        "MsgContent", Map.of("Text", text))));
        postRest("/v4/openim/sendmsg", body, "sendSystemMessage");
    }

    @Override
    public void pushOffline(String imUserId, String title, String body, String deepLinkType, String deepLinkToken) {
        // 离线推送属 Epic 6（独立推送系统），本增量不实现真实下发；仅记非敏感日志占位（L2/Epic6 待本地）。
        log.debug("[IM-live] offline push to {} type={} (deferred to Epic6)", imUserId, deepLinkType);
    }

    @Override
    public void deleteUserConversationMedia(String imUserId) {
        // 注销删媒体属 Story 7.3（决策 D2）；真实 REST 删消息/会话媒体待本地（L2），亦可依赖 IM 侧媒体 TTL。
        log.debug("[IM-live] delete conversation media for {} (deferred to 7.3 L2)", imUserId);
    }

    @Override
    public boolean verifyCallback(String token) {
        String expected = props.getCallbackToken();
        return expected != null && !expected.isBlank() && expected.equals(token);
    }

    /**
     * 统一 REST 调用：拼管理员鉴权 query（usersig 用 admin 身份签）+ POST JSON。
     * 失败仅记<b>异常类名</b>（绝不泄 body/凭证/堆栈），<b>不抛出</b>——由调用方「不阻断业务」语义兜底。
     */
    private void postRest(String path, Map<String, Object> body, String op) {
        try {
            String adminSig = genUserSig(props.getAdminIdentifier(), props.getUserSigTtlSeconds());
            String uri = path
                    + "?sdkappid=" + parseSdkAppId(props.getSdkAppId())
                    + "&identifier=" + props.getAdminIdentifier()
                    + "&usersig=" + adminSig
                    + "&random=" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE)
                    + "&contenttype=json";
            rest.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // 非敏感日志：仅 op + 异常类名（不打印 imUserId 外的 PII / 文本 / usersig）。
            log.warn("[IM-live] {} 失败（不阻断业务）: {}", op, e.getClass().getSimpleName());
        }
    }
}
