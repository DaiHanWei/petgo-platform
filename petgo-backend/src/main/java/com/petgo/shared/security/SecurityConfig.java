package com.petgo.shared.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置（Story 1.3 起收紧）。
 *
 * <p>从 1.1「全放行」收紧为「auth/文档/健康/公开名片放行 + 其余 /api/v1 需自签 JWT」。
 * 这是收紧方向（安全规则只升不降）。OAuth2 Resource Server 校验**本应用自签 JWT**
 * （非 Google token）；{@code role} claim → 门控 authority。
 *
 * <p>游客只读端点（Feed/详情 GET，FR-0A）在 Story 1.5 落实「读放行/写拒绝」对称分类，
 * 此处仅约定结构。401 未认证与 403 越权统一 ProblemDetail（不混用）。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final ProblemDetailAuthHandlers problemHandlers;

    public SecurityConfig(ProblemDetailAuthHandlers problemHandlers) {
        this.problemHandlers = problemHandlers;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录/刷新放行（换取自签 JWT 的入口）
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 运维/文档/公开 H5 名片放行
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/p/**").permitAll()
                        // dev 诊断端点（仅 dev profile 存在）+ 错误转发
                        .requestMatchers("/api/v1/_ping-error", "/error").permitAll()
                        // 游客只读放行锚点（Story 1.5 细化具体业务 GET）
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        // 其余 /api/v1 默认需 JWT（写一律拒绝未登录）
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }
}
