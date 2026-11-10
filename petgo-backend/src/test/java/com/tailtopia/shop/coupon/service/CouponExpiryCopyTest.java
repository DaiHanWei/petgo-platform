package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * L0：v1.4.2 shop Story 2.3 AC4（PRD §6 G9）—— 用<b>真实</b> {@code i18n/messages*.properties} 渲染；
 * 四份 properties 键齐全；英文单引号写成 {@code ''}；到期时刻按 WIB、{@code d MMM, HH:mm}。
 */
class CouponExpiryCopyTest {

    private static final Locale ID = Locale.forLanguageTag("id");
    private static final List<String> FILES = List.of("messages.properties", "messages_en.properties",
            "messages_id.properties", "messages_zh_CN.properties");
    private static final String TITLE = "notify.COUPON_EXPIRING.title";
    private static final String BODY = "notify.COUPON_EXPIRING.body";

    private final CouponExpiryCopy copy = new CouponExpiryCopy(source());

    private static ReloadableResourceBundleMessageSource source() {
        ReloadableResourceBundleMessageSource s = new ReloadableResourceBundleMessageSource();
        s.setBasename("classpath:i18n/messages");
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        s.setFallbackToSystemLocale(false);
        return s;
    }

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = CouponExpiryCopyTest.class.getResourceAsStream("/i18n/" + file)) {
            assertThat(in).as(file).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void 四份properties键齐全() throws IOException {
        for (String f : FILES) {
            Properties p = load(f);
            assertThat(p.getProperty(TITLE)).as(f + " " + TITLE).isNotBlank();
            assertThat(p.getProperty(BODY)).as(f + " " + BODY).isNotBlank();
        }
    }

    @Test
    void 英文单引号转义_印尼语不含中文() throws IOException {
        assertThat(load("messages.properties").getProperty(BODY)).contains("don''t");
        assertThat(load("messages_en.properties").getProperty(BODY)).contains("don''t");
        Properties id = load("messages_id.properties");
        assertThat(id.getProperty(TITLE) + id.getProperty(BODY)).doesNotContainPattern("[\\u4e00-\\u9fff]");
    }

    @Test
    void 渲染PRD_G9原句() {
        Object[] args = {"3", "6 Nov, 23:59"};
        assertThat(copy.render("title", args, ID)).isEqualTo("3 kupon segera berakhir");
        assertThat(copy.render("body", args, ID)).isEqualTo("Pakai sebelum 6 Nov, 23:59. Jangan sampai hangus!");
        assertThat(copy.render("title", args, Locale.ENGLISH)).isEqualTo("3 coupons expiring soon");
        assertThat(copy.render("body", args, Locale.ENGLISH))
                .isEqualTo("Use them before 6 Nov, 23:59 so they don't go to waste!");
        assertThat(copy.render("title", args, Locale.SIMPLIFIED_CHINESE)).isEqualTo("3 张券快过期");
    }

    @Test
    void WIB换算与格式() {
        Instant utc = Instant.parse("2026-11-06T16:59:00Z"); // = WIB 2026-11-06 23:59
        assertThat(CouponExpiryCopy.formatExpiry(utc, ID)).isEqualTo("6 Nov, 23:59");
        assertThat(CouponExpiryCopy.formatExpiry(utc, Locale.ENGLISH)).isEqualTo("6 Nov, 23:59");
        // 跨日：UTC 17:00 → 次日 00:00 WIB
        assertThat(CouponExpiryCopy.formatExpiry(Instant.parse("2026-11-06T17:00:00Z"), ID)).isEqualTo("7 Nov, 00:00");
        assertThat(CouponExpiryCopy.wibDate(utc)).hasToString("2026-11-06");
    }

    @Test
    void 张数以字符串传参_不加千分位() {
        Object[] args = copy.args(1200, Instant.parse("2026-11-06T16:59:00Z"), Locale.ENGLISH);
        assertThat(args).containsExactly("1200", "6 Nov, 23:59");
        assertThat(copy.render("title", args, Locale.ENGLISH)).isEqualTo("1200 coupons expiring soon");
    }
}
