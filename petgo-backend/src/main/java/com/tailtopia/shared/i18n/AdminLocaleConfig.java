package com.tailtopia.shared.i18n;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 管理后台双语 i18n 配置（Story 1.6，NFR2）。Cookie 记忆语言偏好 + 顶栏 {@code ?lang=} 切换。
 *
 * <p>默认 {@code zh_CN}，支持 {@code zh_CN}/{@code en}。语言集独立于 App（App 为 id/en，后台为 zh-CN/en，不复用 .arb）。
 * {@code MessageSource} 由 Boot 依 {@code spring.messages.*} 自动装配，仅 admin Thymeleaf 模板 {@code #{admin.*}} 取用；
 * api 链返 JSON/ProblemDetail（文案固定，不经此），LocaleResolver/Interceptor 对其行为无害。
 */
@Configuration
public class AdminLocaleConfig implements WebMvcConfigurer {

    /** 语言偏好 Cookie（与 App 无关，仅后台）。 */
    public static final String LOCALE_COOKIE = "ADMIN_LOCALE";

    /**
     * 显式定义 {@code messageSource}（不依赖 Boot 自动配置——其 {@code ResourceBundleCondition} 要求存在基底
     * {@code messages.properties}，而本项目只有 zh_CN/en 两个 locale 变体，条件不命中会退化为 DelegatingMessageSource）。
     * basename {@code classpath:i18n/messages}，UTF-8，缺键回退为 code（双键集对齐由 L0 测试硬保证）。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false); // 未匹配 locale 回退默认（zh_CN），不跟随 JVM 系统语言
        source.setUseCodeAsDefaultMessage(true);  // 缺键回退 code，绝不抛 NoSuchMessageException
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LOCALE_COOKIE);
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE); // zh_CN
        resolver.setCookieMaxAge(java.time.Duration.ofDays(365));
        resolver.setCookiePath("/");
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang"); // ?lang=zh_CN / ?lang=en
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
