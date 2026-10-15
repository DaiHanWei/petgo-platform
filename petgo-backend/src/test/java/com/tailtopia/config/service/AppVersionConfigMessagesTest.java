package com.tailtopia.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.4 Story 3.1 · D-17；返工清单 §2 把 min-gt-latest 拆成 iOS / Android 两条）：版本配置保存的 5 个错误码在四份文案包里都有文案。
 *
 * <p>🔴 缺任何一条，admin 主题接上保存接口后 422 的 detail 会直接露出键名。
 */
class AppVersionConfigMessagesTest {

    private static final List<String> CODES = List.of(
            "admin.err.config.version.format",
            "admin.err.config.version.min-gt-ios-latest",
            "admin.err.config.version.min-gt-android-latest",
            "admin.err.config.store-url.scheme",
            "admin.err.config.store-url.blank");

    private static Properties load(String path) throws IOException {
        Properties p = new Properties();
        try (var in = AppVersionConfigMessagesTest.class.getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyCodeHasCopyInEveryBundle() throws IOException {
        for (String bundle : List.of("/i18n/messages.properties", "/i18n/messages_en.properties",
                "/i18n/messages_zh_CN.properties", "/i18n/messages_id.properties")) {
            Properties p = load(bundle);
            for (String code : CODES) {
                assertThat(p.getProperty(code)).as(bundle + " " + code).isNotBlank().isNotEqualTo(code);
            }
        }
    }
}
