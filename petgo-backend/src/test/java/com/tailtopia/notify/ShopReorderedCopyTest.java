package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 4.2 AC5.3 —— 重新下单推送文案（PRD §6 F22），四份 i18n。 */
class ShopReorderedCopyTest {

    private static final String TITLE = "notify.SHOP_ORDER_REORDERED.title";
    private static final String BODY = "notify.SHOP_ORDER_REORDERED.body";

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = ShopReorderedCopyTest.class.getResourceAsStream("/i18n/" + file)) {
            assertThat(in).as(file).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    @DisplayName("两个键在四份文件都存在，且 body 含 {0}")
    void keysInAllFourBundles() throws IOException {
        for (String f : List.of("messages.properties", "messages_en.properties", "messages_id.properties",
                "messages_zh_CN.properties")) {
            Properties p = load(f);
            assertThat(p.getProperty(TITLE)).as(f + " title").isNotBlank();
            assertThat(p.getProperty(BODY)).as(f + " body").contains("{0}");
        }
    }

    @Test
    @DisplayName("印尼语与 PRD §6 F22 逐字一致")
    void indonesianMatchesPrd() throws IOException {
        Properties id = load("messages_id.properties");
        assertThat(id.getProperty(TITLE)).isEqualTo("Pesananmu sudah dibuat ulang");
        assertThat(id.getProperty(BODY)).isEqualTo("Pembayaranmu sudah kami terima. Pesanan {0} sedang disiapkan.");
        assertThat(load("messages_zh_CN.properties").getProperty(TITLE)).isEqualTo("订单已为你重新下单");
    }

    @Test
    @DisplayName("英文 body 单引号写成 ''（MessageFormat 转义），格式化后为 We've … 且展示号被注入")
    void englishApostropheEscaped() throws IOException {
        for (String f : List.of("messages.properties", "messages_en.properties")) {
            Properties p = load(f);
            assertThat(p.getProperty(TITLE)).isEqualTo("Your order has been placed again");
            String body = p.getProperty(BODY);
            assertThat(body).contains("We''ve");
            assertThat(new MessageFormat(body, Locale.ENGLISH).format(new Object[] {"TOKO-20261105-NEW001"}))
                    .isEqualTo("We've received your payment. Order TOKO-20261105-NEW001 is being prepared.");
        }
    }
}
