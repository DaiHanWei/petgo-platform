package com.tailtopia.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

/**
 * 后台静态资源内容指纹的另一半（bug 20260901-471 防复发，配套 application.yml 的
 * {@code spring.web.resources.chain.strategy.content}）。
 *
 * <p>资源链只负责「按带指纹的 URL 提供文件」；模板里 {@code @{/admin/admin.js}} 这样的链接
 * 要被改写成 {@code /admin/admin-<md5>.js}，靠的是本过滤器包装 response 的
 * {@code encodeURL}（Thymeleaf 的 {@code @{...}} 会走它）。
 *
 * <p>🔴 <b>Boot 4 不再自动注册这个过滤器</b>（Boot 3 时代由 Thymeleaf 自动配置带出，
 * 4.0 的 {@code ThymeleafAutoConfiguration} 里已无对应内嵌配置类，已核实 4.0.6 的 jar）——
 * 只开 yml 那半，表现是「资源可以按指纹 URL 访问，但没有任何页面引用它」，等于没开。
 *
 * <p>🛡 未命中版本化资源的 URL 原样返回：对 /api、重定向、分享页等零影响。
 */
@Configuration
public class StaticResourceVersionConfig {

    @Bean
    public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        return new ResourceUrlEncodingFilter();
    }
}
