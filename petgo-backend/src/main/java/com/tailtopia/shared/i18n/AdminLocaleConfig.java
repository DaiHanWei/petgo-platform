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
 * 管理后台多语 i18n 配置（Story 1.6，NFR2；Story 11.7 加印尼语）。Cookie 记忆语言偏好 + 顶栏 {@code ?lang=} 切换。
 *
 * <p>默认 {@code zh_CN}，支持 {@code zh_CN} / {@code en} / {@code id}（Story 11.7 加入印尼语）。
 * 语言集独立于 App（App 为 id/en；后台三语，<b>不复用 .arb</b>）。
 * {@code MessageSource} 由 Boot 依 {@code spring.messages.*} 自动装配，仅 admin Thymeleaf 模板 {@code #{admin.*}} 取用；
 * api 链返 JSON/ProblemDetail（文案固定，不经此），LocaleResolver/Interceptor 对其行为无害。
 */
@Configuration
public class AdminLocaleConfig implements WebMvcConfigurer {

    /** 语言偏好 Cookie（与 App 无关，仅后台）。 */
    public static final String LOCALE_COOKIE = "ADMIN_LOCALE";

    /**
     * 显式定义 {@code messageSource}（保留显式定义而不改回 Boot 自动配置：这里要精确控制回退链）。
     * basename {@code classpath:i18n/messages}，UTF-8。
     *
     * <p>回退链（Story 11.7）：{@code messages_<locale>} → {@code messages}（英文基线）→ code。
     * 三语键集对齐 + 基线覆盖由 L0 测试硬保证。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false); // 未匹配 locale 不跟随 JVM 系统语言
        // 🔴 Story 11.7 · AC1：缺键先回退**英文基线** messages.properties，再回退 code。
        //
        // 改动前是「缺键直接回退 code」—— 任何语言漏一条键，界面上就露出
        // `admin.tags.icon` 这样的原始键名。加印尼语要补 870 条键，翻译期间必然有缺口，
        // 那期间切过去就是满屏键名 ⇒ 基线是**放开印尼语切换的前提**，不是优化。
        //
        // 🛡 setUseCodeAsDefaultMessage 保留为**兜底的兜底**（基线也缺才会命中）；
        //    zh_CN / en 本来就是全量，永远不会走到回退，行为零变化。
        source.setUseCodeAsDefaultMessage(true);
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
