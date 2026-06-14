package com.tailtopia.shared.im;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0 单元测试（无凭证）：打桩 IM 客户端生成会话 id / 占位 UserSig / 回调 token 校验。
 */
class StubTencentImClientTest {

    private StubTencentImClient client() {
        ImProperties props = new ImProperties();
        props.setSdkAppId("1400000000");
        props.setUserSigTtlSeconds(3600);
        return new StubTencentImClient(props);
    }

    @Test
    void createConversationReturnsNonBlankId() {
        assertThat(client().createConversation("u_1", "v_2")).isNotBlank();
    }

    @Test
    void signUserSigCarriesSdkAppIdAndTtlButIsNotRealCredential() {
        UserSig sig = client().signUserSig("u_1");
        assertThat(sig.imUserId()).isEqualTo("u_1");
        assertThat(sig.sdkAppId()).isEqualTo("1400000000");
        assertThat(sig.expireSeconds()).isEqualTo(3600);
        assertThat(sig.userSig()).startsWith("stub-"); // 占位，非真实凭证
    }

    @Test
    void ensureAccountIsNoopAndDoesNotThrow() {
        // 桩：建号空实现，绝不抛错（保持接单/建号 L0/L1 绿）。
        client().ensureAccount("u_1", "用户1");
    }

    @Test
    void verifyCallbackAllowsWhenNoTokenConfigured() {
        assertThat(client().verifyCallback("anything")).isTrue();
    }

    @Test
    void verifyCallbackChecksConfiguredToken() {
        ImProperties props = new ImProperties();
        props.setCallbackToken("secret-token");
        StubTencentImClient c = new StubTencentImClient(props);
        assertThat(c.verifyCallback("secret-token")).isTrue();
        assertThat(c.verifyCallback("wrong")).isFalse();
    }
}
