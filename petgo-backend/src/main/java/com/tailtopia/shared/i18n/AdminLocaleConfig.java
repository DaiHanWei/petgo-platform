package com.tailtopia.shared.i18n;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 管理后台 <b>三语</b> i18n 配置（Story 1.6 起双语；本次加印尼语）。
 * Cookie 记忆语言偏好 + 顶栏 {@code ?lang=} 切换。
 *
 * <p>语言集 {@code zh_CN}（默认）/ {@code en} / {@code id}，独立于 App（App 侧按 {@code users.locale}
 * 选 id/en 发推送，不复用 .arb）。{@code MessageSource} 供 admin Thymeleaf 模板的 {@code #{admin.*}} /
 * {@code #{perm.*}} / {@code #{role.*}} 取用；api 链返 JSON/ProblemDetail（文案目前固定，不经此），
 * LocaleResolver/Interceptor 对其行为无害。
 *
 * <p>⚠️ 印尼语的 locale 码是 {@code id}，但 JDK 17 之前会把它按老 ISO 639 映射成 {@code in}
 * （{@code new Locale("id").getLanguage()} 返回 {@code "in"}），那样就会去找 {@code messages_in.properties}
 * 而落空。本项目 Java 21+ 默认 {@code java.locale.useOldISOCodes=false}，解析为 {@code id}；
 * 若有人显式开回老行为，印尼语会静默退回默认语言 —— 有 L0 测试盯住这条。
 */
@Configuration
public class AdminLocaleConfig implements WebMvcConfigurer {

    /** 语言偏好 Cookie（与 App 无关，仅后台）。 */
    public static final String LOCALE_COOKIE = "ADMIN_LOCALE";

    /** 默认语言。 */
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE; // zh_CN

    /**
     * 后台支持的语言，顺序即顶栏切换器的顺序。
     * 新增语言 = 这里加一项 + 补一份 {@code messages_<locale>.properties}（键集对齐由 L0 测试硬保证）。
     */
    public static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.SIMPLIFIED_CHINESE,          // zh_CN
            Locale.ENGLISH,                     // en
            Locale.forLanguageTag("id"));       // id（印尼语）

    /**
     * 显式定义 {@code messageSource}（不依赖 Boot 自动配置——其 {@code ResourceBundleCondition} 要求存在基底
     * {@code messages.properties}，而本项目只有 zh_CN/en/id 三个 locale 变体，条件不命中会退化为
     * DelegatingMessageSource）。basename {@code classpath:i18n/messages}，UTF-8，缺键回退为 code
     * （三语键集对齐由 L0 测试硬保证）。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false); // 未匹配 locale 回退默认（zh_CN），不跟随 JVM 系统语言
        source.setDefaultLocale(DEFAULT_LOCALE);  // 缺 locale 变体时明确落到 zh_CN，而不是无 locale 的基底文件
        source.setUseCodeAsDefaultMessage(true);  // 缺键回退 code，绝不抛 NoSuchMessageException
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        SupportedOnlyCookieLocaleResolver resolver = new SupportedOnlyCookieLocaleResolver(LOCALE_COOKIE);
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        resolver.setCookieMaxAge(java.time.Duration.ofDays(365));
        resolver.setCookiePath("/");
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");        // ?lang=zh_CN / ?lang=en / ?lang=id
        interceptor.setIgnoreInvalidLocale(true); // ?lang=乱码 不抛 500，忽略即可
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    /**
     * 把不在 {@link #SUPPORTED_LOCALES} 里的语言收敛回默认语言。
     *
     * <p>为什么需要：{@code LocaleChangeInterceptor} 只校验「能不能解析成 Locale」，不校验「我们有没有这份翻译」。
     * 没有这层收敛时，{@code ?lang=fr} 会被原样写进 Cookie 并长期生效 —— 由于 MessageSource 配了
     * {@code useCodeAsDefaultMessage}，页面不会报错，而是把 {@code admin.nav.orders} 这样的键名当文案显示出来，
     * 且因为写进了 Cookie，用户下次进后台还是这副样子，多半会当成故障来报。收敛在这里比在每个页面兜底便宜得多。
     *
     * <p>匹配按<b>语言</b>而非完整标签：{@code zh_TW} / {@code zh} 都归到 {@code zh_CN}，
     * {@code en_US} 归到 {@code en} —— 用户带着地区变体来时给他最接近的那份翻译，而不是直接踢回中文。
     */
    static class SupportedOnlyCookieLocaleResolver extends CookieLocaleResolver {

        SupportedOnlyCookieLocaleResolver(String cookieName) {
            super(cookieName);
        }

        @Override
        public LocaleContext resolveLocaleContext(HttpServletRequest request) {
            LocaleContext ctx = super.resolveLocaleContext(request);
            Locale resolved = ctx == null ? null : ctx.getLocale();
            Locale supported = clamp(resolved);
            return supported.equals(resolved) ? ctx : new SimpleLocaleContext(supported);
        }

        /** 精确命中 → 原样；同语言命中 → 该语言的受支持变体；都不中 → 默认语言。 */
        private static Locale clamp(Locale requested) {
            if (requested == null) {
                return DEFAULT_LOCALE;
            }
            if (SUPPORTED_LOCALES.contains(requested)) {
                return requested;
            }
            for (Locale s : SUPPORTED_LOCALES) {
                if (s.getLanguage().equals(requested.getLanguage())) {
                    return s;
                }
            }
            return DEFAULT_LOCALE;
        }
    }
}
