package com.tailtopia.admin.shared.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册 {@link HxRequestArgumentResolver}（Story 2.3a）。与 {@code AdminLocaleConfig} 并列的 WebMvcConfigurer，互不影响。 */
@Configuration
public class AdminWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HxRequestArgumentResolver());
    }
}
