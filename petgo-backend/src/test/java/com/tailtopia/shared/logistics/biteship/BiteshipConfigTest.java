package com.tailtopia.shared.logistics.biteship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * L0：v1.4.2 shop Story 1.1 · Biteship 装配护栏。
 *
 * <p>🔴 决策 C-5 覆盖 story 原文「生产缺 key 拒启动」：prod profile 下配置不可用 → 不拒启动，装配
 * {@link UnavailableBiteshipClient}（询价一律失败）；非 prod 配错仍拒启动。
 */
class BiteshipConfigTest {

    private final BiteshipConfig config = new BiteshipConfig();

    private static BiteshipProperties props(String mode, String key) {
        BiteshipProperties p = new BiteshipProperties();
        p.setMode(mode);
        p.setApiKey(key);
        return p;
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    @DisplayName("默认 stub（dev / 测试 / 云端）")
    void defaultStub() {
        assertThat(config.biteshipClient(new BiteshipProperties(), env()))
                .isInstanceOf(StubBiteshipClient.class);
    }

    @Test
    @DisplayName("live + live key → Live 客户端，模式 LIVE")
    void liveOk() {
        BiteshipClient c = config.biteshipClient(props("live", "biteship_live.xyz"), env());
        assertThat(c).isInstanceOf(LiveBiteshipClient.class);
        assertThat(c.providerMode()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("sandbox + test key → Live 客户端，模式 SANDBOX")
    void sandboxOk() {
        BiteshipClient c = config.biteshipClient(props("sandbox", "biteship_test.xyz"), env());
        assertThat(c).isInstanceOf(LiveBiteshipClient.class);
        assertThat(c.providerMode()).isEqualTo("SANDBOX");
    }

    @Test
    @DisplayName("非 prod：live 缺 key / 前缀不符 → 拒启动")
    void liveBadKeyRefusedOutsideProd() {
        assertThatThrownBy(() -> config.biteshipClient(props("live", ""), env()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.biteshipClient(props("live", "biteship_test.xyz"), env()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非 prod：sandbox 用 live key → 拒启动")
    void sandboxWithLiveKeyRefused() {
        assertThatThrownBy(() -> config.biteshipClient(props("sandbox", "biteship_live.xyz"), env("dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非 prod：未知 mode → 拒启动")
    void unknownModeRefused() {
        assertThatThrownBy(() -> config.biteshipClient(props("foo", ""), env()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("C-5：prod（非 stag）+ stub → 不拒启动，装配不可用客户端（询价一律失败，绝不用桩价）")
    void prodStubIsUnavailableNotStub() {
        BiteshipClient c = config.biteshipClient(props("stub", ""), env("prod"));
        assertThat(c).isInstanceOf(UnavailableBiteshipClient.class);
        assertThatThrownBy(() -> c.rates(null)).isInstanceOf(BiteshipException.class);
    }

    @Test
    @DisplayName("C-5：prod + live 缺 key / 前缀不符 → 不拒启动，不可用客户端")
    void prodLiveBadKeyIsUnavailable() {
        assertThat(config.biteshipClient(props("live", ""), env("prod")))
                .isInstanceOf(UnavailableBiteshipClient.class);
        assertThat(config.biteshipClient(props("live", "biteship_test.xyz"), env("prod")))
                .isInstanceOf(UnavailableBiteshipClient.class);
    }

    @Test
    @DisplayName("prod（非 stag）+ sandbox → 不可用客户端")
    void prodSandboxIsUnavailable() {
        assertThat(config.biteshipClient(props("sandbox", "biteship_test.xyz"), env("prod")))
                .isInstanceOf(UnavailableBiteshipClient.class);
    }

    @Test
    @DisplayName("prod,stag + stub → 允许桩；prod,stag + sandbox → 允许")
    void stagAllowsStubAndSandbox() {
        assertThat(config.biteshipClient(props("stub", ""), env("prod", "stag")))
                .isInstanceOf(StubBiteshipClient.class);
        assertThat(config.biteshipClient(props("sandbox", "biteship_test.xyz"), env("prod", "stag")))
                .isInstanceOf(LiveBiteshipClient.class);
    }

    @Test
    @DisplayName("prod + live key → Live")
    void prodLiveOk() {
        assertThat(config.biteshipClient(props("live", "biteship_live.xyz"), env("prod")))
                .isInstanceOf(LiveBiteshipClient.class);
    }

    @Test
    @DisplayName("🔒 toString 不含 key；couriers 规范化并剔除 jntcargo")
    void toStringHidesKey() {
        BiteshipProperties p = props("live", "biteship_live.SECRET123");
        p.setCouriers("JNE, jntcargo ,sicepat,jne");
        assertThat(p.toString()).doesNotContain("SECRET123").doesNotContain("biteship_live.");
        assertThat(p.couriersCsv()).isEqualTo("jne,sicepat");
        assertThat(p.containsForbiddenCourier()).isTrue();
    }
}
