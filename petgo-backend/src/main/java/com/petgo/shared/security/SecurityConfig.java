package com.petgo.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 脚手架阶段安全配置 —— 放行全部请求。
 * <p>本 Story（1.1）不实现登录/OAuth/JWT（Story 1.3 才接 oauth2-resource-server）。
 * 此处先 permitAll，避免脚手架阶段 health / swagger / api-docs 被 401 挡住。
 * Story 1.3 起在此收紧鉴权规则。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 纯 API（无浏览器表单会话）；CSRF 关闭。1.3 起按需引入鉴权。
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
