package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：新订单提醒的配置默认值与开关语义（Story 3-4 AC6 / AC8）。
 *
 * <p>🔴 本类守的是「**合并到任何环境都不会意外出网**」这条 —— 默认 off、收件人默认空。
 */
class ShopOrderNotifyPropertiesTest {

    @Test
    @DisplayName("🔴 默认 mode=off，且收件人默认为空 —— 合并进任何环境都不会意外发消息")
    void defaultsAreSilent() {
        var p = new ShopOrderNotifyProperties();

        assertThat(p.getMode()).isEqualTo("off");
        assertThat(p.getReceiveId()).isEmpty();
        assertThat(p.isLive()).isFalse();
    }

    @Test
    @DisplayName("🔴 mode=live 但收件人没配 → 仍不发（空收件人发出去也是错的）")
    void liveWithoutReceiverStaysSilent() {
        var p = new ShopOrderNotifyProperties();
        p.setMode("live");

        assertThat(p.isLive()).as("收件人为空时发消息只会得到一个 API 错误").isFalse();
    }

    @Test
    @DisplayName("mode=live 且配了收件人 → 开")
    void liveWithReceiverIsLive() {
        var p = new ShopOrderNotifyProperties();
        p.setMode("live");
        p.setReceiveId("oc_test_chat_id");

        assertThat(p.isLive()).isTrue();
    }

    @Test
    @DisplayName("mode 大小写与空白不敏感（运维手填 env 时常带空格）")
    void modeIsTrimmedAndCaseInsensitive() {
        var p = new ShopOrderNotifyProperties();
        p.setReceiveId("oc_x");
        p.setMode(" LIVE ");

        assertThat(p.isLive()).isTrue();
    }

    @Test
    @DisplayName("汇总窗口默认 10 分钟 —— 与 SHOP-NFR-03「10 分钟内送达」对齐")
    void windowMatchesTheDeliveryNfr() {
        assertThat(new ShopOrderNotifyProperties().getWindowMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("🔴 夜间静默默认关闭 —— OD-5 定值前不启用")
    void quietHoursDefaultOff() {
        assertThat(new ShopOrderNotifyProperties().isQuietHoursEnabled()).isFalse();
    }

    @Test
    @DisplayName("🔴 代码里没有任何硬编码的收件人 id（AC8）")
    void noHardcodedReceiver() {
        // 默认值必须是空串。任何形如 oc_xxx 的字面量出现在这里都是把「发给谁」写死了，
        // 而 OD-5 还没拍板 —— 拍板后要能只改 env 就生效。
        assertThat(new ShopOrderNotifyProperties().getReceiveId()).isEmpty();
        assertThat(new ShopOrderNotifyProperties().getAppId()).isEmpty();
        assertThat(new ShopOrderNotifyProperties().getAppSecret()).isEmpty();
    }
}
