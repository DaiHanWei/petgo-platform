package com.tailtopia.shared.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.zip.Inflater;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * L0 单元测试（无凭证 / 无网络）：{@link LiveTencentImClient} 的 UserSig 签名是<b>确定性</b>的，
 * 注入固定 {@link Clock} 后输出稳定，且结构符合 TLSSigAPIv2（HMAC-SHA256 + zlib + 腾讯变体 base64url）。
 *
 * <p>这块在云端就是<b>真验、非占位</b>：独立重算 HMAC 与解压 JSON 断言内嵌 {@code TLS.sig}/{@code TLS.identifier}。
 */
class LiveTencentImClientTest {

    private static final String SECRET = "test-secret-key-not-real-0123456789";
    private static final String SDK_APP_ID = "20043419";
    private static final long FIXED_TIME = 1_700_000_000L;
    private static final long TTL = 86400L;

    private LiveTencentImClient client() {
        ImProperties props = new ImProperties();
        props.setMode("live");
        props.setSdkAppId(SDK_APP_ID);
        props.setSecretKey(SECRET);
        props.setUserSigTtlSeconds(TTL);
        Clock fixed = Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC);
        return new LiveTencentImClient(props, fixed);
    }

    @Test
    void signUserSigIsDeterministicForFixedClock() {
        LiveTencentImClient c = client();
        UserSig a = c.signUserSig("u_42");
        UserSig b = c.signUserSig("u_42");
        // 同输入 + 同固定时钟 → 完全一致（HMAC + zlib + base64url 全确定）。
        assertThat(a.userSig()).isEqualTo(b.userSig());
        assertThat(a.userSig()).isNotBlank();
        assertThat(a.imUserId()).isEqualTo("u_42");
        assertThat(a.sdkAppId()).isEqualTo(SDK_APP_ID);
        assertThat(a.expireSeconds()).isEqualTo(TTL);
        // 非 stub 占位（不含 stub- 前缀）。
        assertThat(a.userSig()).doesNotContain("stub-");
    }

    @Test
    void userSigUsesTencentBase64UrlAlphabet() {
        String sig = client().signUserSig("u_42").userSig();
        // 腾讯变体 base64url：绝不含标准 base64 的 + / =（已替换为 * - _）。
        assertThat(sig).doesNotContain("+").doesNotContain("/").doesNotContain("=");
    }

    @Test
    void differentIdentifierProducesDifferentSig() {
        LiveTencentImClient c = client();
        assertThat(c.signUserSig("u_1").userSig()).isNotEqualTo(c.signUserSig("v_1").userSig());
    }

    @Test
    void inflatedDocCarriesExpectedHmacAndIdentifier() throws Exception {
        String sig = client().signUserSig("u_42").userSig();
        String json = inflate(sig);

        // 结构真验：含 TLSSigAPIv2 字段。
        assertThat(json).contains("\"TLS.identifier\":\"u_42\"");
        assertThat(json).contains("\"TLS.sdkappid\":" + SDK_APP_ID);
        assertThat(json).contains("\"TLS.expire\":" + TTL);
        assertThat(json).contains("\"TLS.time\":" + FIXED_TIME);

        // 独立重算 HMAC-SHA256，断言内嵌 TLS.sig 与之一致（证明签名算法正确，非占位）。
        String expectedSig = independentHmac("u_42");
        assertThat(json).contains("\"TLS.sig\":\"" + expectedSig + "\"");
    }

    /** 逆腾讯变体 base64url + Base64 解码 + zlib 解压，还原 sig 文档 JSON。 */
    private static String inflate(String tencentBase64Url) throws Exception {
        String std = tencentBase64Url.replace('*', '+').replace('-', '/').replace('_', '=');
        byte[] compressed = Base64.getDecoder().decode(std);
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0) {
                break;
            }
            out.write(buf, 0, n);
        }
        inflater.end();
        return out.toString(StandardCharsets.UTF_8);
    }

    /** 与被测实现解耦地重算 content 串 HMAC，用于交叉验证。 */
    private static String independentHmac(String identifier) throws Exception {
        String content = "TLS.identifier:" + identifier + "\n"
                + "TLS.sdkappid:" + Long.parseLong(SDK_APP_ID) + "\n"
                + "TLS.time:" + FIXED_TIME + "\n"
                + "TLS.expire:" + TTL + "\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void createConversationReturnsC2cDeterministicId() {
        assertThat(client().createConversation("u_1", "v_2")).isEqualTo("c2c-u_1-v_2");
    }

    @Test
    void verifyCallbackRequiresConfiguredTokenMatch() {
        ImProperties props = new ImProperties();
        props.setSdkAppId(SDK_APP_ID);
        props.setSecretKey(SECRET);
        props.setCallbackToken("secret-token");
        LiveTencentImClient c = new LiveTencentImClient(props,
                Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC));
        assertThat(c.verifyCallback("secret-token")).isTrue();
        assertThat(c.verifyCallback("wrong")).isFalse();
        // live 未配 token → 一律拒绝（与 stub 的「未配则放行」相反，live 不放行未签名回调）。
        ImProperties noTok = new ImProperties();
        noTok.setSdkAppId(SDK_APP_ID);
        noTok.setSecretKey(SECRET);
        LiveTencentImClient c2 = new LiveTencentImClient(noTok,
                Clock.fixed(Instant.ofEpochSecond(FIXED_TIME), ZoneOffset.UTC));
        assertThat(c2.verifyCallback("anything")).isFalse();
    }
}
