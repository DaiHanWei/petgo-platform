package com.tailtopia.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * L0：后台语言解析（三语切换）。无 Spring 容器、无 DB——直接构造配置类的 bean 验证行为。
 *
 * <p>覆盖两类实际会咬人的情况：
 * <ul>
 *   <li><b>印尼语的 {@code id} 码</b>：JDK 17 之前会按老 ISO 639 把它映射成 {@code in}，
 *       那样就会去找 {@code messages_in.properties} 而落空，页面静默退回中文。
 *       这条断言在任何一次 JDK/启动参数变更把老行为打开时立刻报警。</li>
 *   <li><b>不受支持的语言</b>：{@code ?lang=fr} 会被写进 Cookie 长期生效，而 MessageSource 配了
 *       {@code useCodeAsDefaultMessage}，页面不会报错、只会把键名当文案显示 —— 属于「看起来坏了但没人知道为什么」
 *       的那种故障。必须在解析层收敛掉。</li>
 * </ul>
 */
class AdminLocaleResolutionTest {

    private final AdminLocaleConfig config = new AdminLocaleConfig();

    private Locale resolve(String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (cookieValue != null) {
            request.setCookies(new Cookie(AdminLocaleConfig.LOCALE_COOKIE, cookieValue));
        }
        return new AdminLocaleConfig.SupportedOnlyCookieLocaleResolver(AdminLocaleConfig.LOCALE_COOKIE) {
            {
                setDefaultLocale(AdminLocaleConfig.DEFAULT_LOCALE);
            }
        }.resolveLocaleContext(request).getLocale();
    }

    @Test
    void indonesianKeepsTheIdLanguageCode() {
        // 若 JVM 打开了 java.locale.useOldISOCodes，这里会变成 "in"，印尼语资源就永远加载不到。
        assertThat(Locale.forLanguageTag("id").getLanguage()).isEqualTo("id");
        assertThat(Locale.forLanguageTag("id").toString()).isEqualTo("id");
        assertThat(AdminLocaleConfig.SUPPORTED_LOCALES)
                .extracting(Locale::toString)
                .containsExactly("zh_CN", "en", "id");
    }

    @Test
    void supportedLocalesResolveAsIs() {
        assertThat(resolve("zh_CN")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(resolve("en")).isEqualTo(Locale.ENGLISH);
        assertThat(resolve("id")).isEqualTo(Locale.forLanguageTag("id"));
    }

    @Test
    void unsupportedLocaleFallsBackToDefault() {
        assertThat(resolve("fr")).isEqualTo(AdminLocaleConfig.DEFAULT_LOCALE);
        assertThat(resolve("ja_JP")).isEqualTo(AdminLocaleConfig.DEFAULT_LOCALE);
        assertThat(resolve(null)).isEqualTo(AdminLocaleConfig.DEFAULT_LOCALE);
    }

    /** 带地区变体时给最接近的那份翻译，而不是直接踢回中文。 */
    @Test
    void regionalVariantsMapToTheirSupportedLanguage() {
        assertThat(resolve("en_US")).isEqualTo(Locale.ENGLISH);
        assertThat(resolve("en_GB")).isEqualTo(Locale.ENGLISH);
        assertThat(resolve("zh_TW")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(resolve("id_ID")).isEqualTo(Locale.forLanguageTag("id"));
    }

    /** MessageSource 三语都能真的取到文案（而不是回退成 code）。 */
    @Test
    void messageSourceServesAllThreeLocales() {
        MessageSource source = config.messageSource();
        String key = "admin.nav.dashboard";
        String zh = source.getMessage(key, null, Locale.SIMPLIFIED_CHINESE);
        String en = source.getMessage(key, null, Locale.ENGLISH);
        String id = source.getMessage(key, null, Locale.forLanguageTag("id"));

        assertThat(zh).isNotEqualTo(key);
        assertThat(en).isNotEqualTo(key);
        assertThat(id).as("印尼语没取到文案（多半是 id→in 映射或文件缺键）").isNotEqualTo(key);
        assertThat(zh).isNotEqualTo(en);
        assertThat(id).isNotEqualTo(zh);
    }

    /** 未受支持的语言即便绕过解析层直接问 MessageSource，也应落到默认语言而不是键名。 */
    @Test
    void messageSourceFallsBackToDefaultLocaleNotCode() {
        MessageSource source = config.messageSource();
        assertThat(source.getMessage("admin.nav.dashboard", null, Locale.FRENCH))
                .isEqualTo(source.getMessage("admin.nav.dashboard", null, AdminLocaleConfig.DEFAULT_LOCALE));
    }
}
