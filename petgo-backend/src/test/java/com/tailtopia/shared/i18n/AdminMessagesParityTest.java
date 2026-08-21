package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * L0：<b>三语</b> messages 键集一一对应（Story 1.6 AC3，本次由双语扩到 zh_CN / en / id）。
 *
 * <p>为什么必须硬测：MessageSource 配了 {@code useCodeAsDefaultMessage}，缺键不会抛异常，
 * 而是把键名当文案渲染出来（页面上出现 {@code admin.nav.orders} 这种字样）。
 * 没有这个测试，漏译只会在切到那门语言、打开那个页面时才被人肉发现。
 */
class AdminMessagesParityTest {

    /** 后台支持的语言 → 资源文件。新增语言时这里加一行，其余断言自动覆盖。 */
    private static final Map<String, String> BUNDLES = new LinkedHashMap<>(Map.of(
            "zh_CN", "/i18n/messages_zh_CN.properties",
            "en", "/i18n/messages_en.properties",
            "id", "/i18n/messages_id.properties"));

    private Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("缺少 i18n 资源 " + path).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private Set<String> keys(String path) throws Exception {
        return new TreeSet<>(load(path).stringPropertyNames());
    }

    @Test
    void allLocalesShareTheSameKeySet() throws Exception {
        Set<String> reference = keys(BUNDLES.get("zh_CN"));
        assertThat(reference).as("zh_CN 键集不该为空").isNotEmpty();

        for (Map.Entry<String, String> e : BUNDLES.entrySet()) {
            Set<String> actual = keys(e.getValue());

            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual);
            extra.removeAll(reference);

            assertThat(missing).as(e.getKey() + " 缺失的键（会以键名裸露在页面上）").isEmpty();
            assertThat(extra).as(e.getKey() + " 多出的键（其它语言没有对应翻译）").isEmpty();
        }
    }

    @Test
    void noBlankValues() throws Exception {
        for (Map.Entry<String, String> e : BUNDLES.entrySet()) {
            Properties p = load(e.getValue());
            p.forEach((k, v) -> assertThat(v.toString().trim())
                    .as(e.getKey() + " 空值 " + k).isNotEmpty());
        }
    }

    /**
     * 带占位符的文案，各语言的占位符编号集合必须一致。
     *
     * <p>漏掉一个 {@code {0}} 不会报错，只会让页面上少一个数字（例如
     * 「资质预警：即将到期 个」）——这类缺陷肉眼扫一遍译文很容易放过去。
     */
    @Test
    void placeholdersMatchAcrossLocales() throws Exception {
        Properties zh = load(BUNDLES.get("zh_CN"));
        for (Map.Entry<String, String> e : BUNDLES.entrySet()) {
            if ("zh_CN".equals(e.getKey())) {
                continue;
            }
            Properties other = load(e.getValue());
            for (String key : new TreeSet<>(zh.stringPropertyNames())) {
                assertThat(placeholders(other.getProperty(key)))
                        .as(e.getKey() + " 的占位符与 zh_CN 不一致：" + key)
                        .isEqualTo(placeholders(zh.getProperty(key)));
            }
        }
    }

    /** 抽出 {@code {0}}/{@code {1}} 这类 MessageFormat 占位符编号（顺序无关，只比集合）。 */
    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        if (value == null) {
            return found;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)}").matcher(value);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /**
     * 配置里声明的语言，都必须有对应的资源文件——防止「顶栏加了个切换入口，
     * 但没人补 messages_xx.properties」这种半截改动溜上线。
     */
    @Test
    void everySupportedLocaleHasABundle() {
        for (Locale locale : AdminLocaleConfig.SUPPORTED_LOCALES) {
            String path = "/i18n/messages_" + locale + ".properties";
            assertThat(getClass().getResourceAsStream(path))
                    .as("声明支持 " + locale + " 但缺少 " + path).isNotNull();
        }
        assertThat(AdminLocaleConfig.SUPPORTED_LOCALES)
                .as("默认语言必须在受支持列表内")
                .contains(AdminLocaleConfig.DEFAULT_LOCALE);
    }
}
