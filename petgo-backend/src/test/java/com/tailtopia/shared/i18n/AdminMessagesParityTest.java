package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** L0：双语 messages 键集一一对应（Story 1.6 AC3）——zh_CN 与 en 的 key 集合必须完全相等，无单边缺失。 */
class AdminMessagesParityTest {

    private Set<String> keys(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("缺少 i18n 资源 " + path).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    @Test
    void zhAndEnKeySetsAreIdentical() throws Exception {
        Set<String> zh = keys("/i18n/messages_zh_CN.properties");
        Set<String> en = keys("/i18n/messages_en.properties");

        Set<String> onlyZh = new TreeSet<>(zh);
        onlyZh.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(zh);

        assertThat(onlyZh).as("仅 zh_CN 有的键（en 缺失）").isEmpty();
        assertThat(onlyEn).as("仅 en 有的键（zh_CN 缺失）").isEmpty();
        assertThat(zh).isEqualTo(en);
        assertThat(zh).isNotEmpty();
    }

    @Test
    void noBlankValues() throws Exception {
        Properties zh = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages_zh_CN.properties")) {
            zh.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Properties en = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages_en.properties")) {
            en.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        zh.forEach((k, v) -> assertThat(v.toString().trim()).as("zh 空值 " + k).isNotEmpty());
        en.forEach((k, v) -> assertThat(v.toString().trim()).as("en 空值 " + k).isNotEmpty());
    }
}
