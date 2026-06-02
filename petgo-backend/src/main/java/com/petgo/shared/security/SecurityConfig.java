package com.petgo.shared.security;

import com.petgo.admin.service.AdminUserDetailsService;
import com.petgo.vet.web.BannedVetFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * 安全配置（Story 1.3 起收紧；Story 3.1 增 admin 表单登录链）。
 *
 * <p>两条 filter chain，按 {@link Order} 分区互不重叠：
 * <ol>
 *   <li><b>admin 链</b>（{@code /admin/**}）：会话 + 表单登录 + CSRF，{@code role=ADMIN} 门控；
 *       未登录跳后台登录页，user/vet 越权 403（Spring 默认 access-denied）。与 user/vet 路由完全隔离。</li>
 *   <li><b>api 链</b>（其余）：无状态自签 JWT 资源服务器；{@code role} claim → 门控 authority；
 *       401/403 统一 ProblemDetail（不混用）。这是 1.1「全放行」的收紧方向（安全规则只升不降）。</li>
 * </ol>
 *
 * <p>游客只读端点（Feed/详情 GET，FR-0A）在 Story 1.5 落实「读放行/写拒绝」对称分类。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final ProblemDetailAuthHandlers problemHandlers;

    public SecurityConfig(ProblemDetailAuthHandlers problemHandlers) {
        this.problemHandlers = problemHandlers;
    }

    /** ADMIN 密码哈希算法（BCrypt）；env 注入的明文经此编码，绝不存明文。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Admin 后台链（Story 3.1）：{@code /admin/**} 表单登录 + 会话 + CSRF，要求 {@code ROLE_ADMIN}。
     * 优先级高于 api 链，独占 {@code /admin/**}，故 api 链不会触达后台路由。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
            AdminUserDetailsService adminUserDetailsService) throws Exception {
        http
                .securityMatcher("/admin/**")
                .userDetailsService(adminUserDetailsService)
                .authorizeHttpRequests(auth -> auth
                        // 登录页本身放行（未登录可访问以输入账密）
                        .requestMatchers("/admin/login").permitAll()
                        // 其余后台页面一律要求 ADMIN（user/vet → 403 越权）
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin/dashboard", true)
                        .failureUrl("/admin/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout"));
        // CSRF 保持开启（表单链默认即开）；会话按需创建（表单登录态）。
        return http.build();
    }

    /** 业务 API 链（无状态 JWT）。 */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, BannedVetFilter bannedVetFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 封禁即生效（Story 5.7）：JWT 认证后、授权前校验 vet status，BANNED → 401 踢下线。
                .addFilterBefore(bannedVetFilter, AuthorizationFilter.class)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录/刷新放行（换取自签 JWT 的入口）
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 运维/文档/公开 H5 名片放行
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/p/**").permitAll()
                        // dev 诊断端点（仅 dev profile 存在）+ 错误转发
                        .requestMatchers("/api/v1/_ping-error", "/error").permitAll()
                        // 腾讯 IM 服务端回调（外部来源，内部 token/签名校验，Story 5.5）
                        .requestMatchers("/im/callback").permitAll()
                        // 游客只读放行锚点（Story 1.5 细化具体业务 GET）
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        // Feed 只读对游客可见（Story 3.2，FR-0A/17）：GET 内容流放行（写仍需 JWT）
                        .requestMatchers(HttpMethod.GET, "/api/v1/content-posts").permitAll()
                        // 内容详情 + 评论只读对游客可见（Story 3.3）：GET 详情/评论/回复放行（写仍需 JWT）
                        .requestMatchers(HttpMethod.GET, "/api/v1/content-posts/**",
                                "/api/v1/comments/**").permitAll()
                        // 他人迷你主页只读对游客可见（Story 3.8，FR-26 无登录要求）
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/mini-profile").permitAll()
                        // 兽医工作台端点（Story 5.1+）：仅 role=VET 可达；user/guest → 403（双向门控）
                        .requestMatchers("/api/v1/vet/**").hasRole("VET")
                        // 用户侧问诊端点（Story 5.2+）：仅 role=USER 可达（vet/guest → 403）
                        .requestMatchers("/api/v1/consult/**",
                                "/api/v1/consult-sessions", "/api/v1/consult-sessions/**").hasRole("USER")
                        // 其余 /api/v1 默认需 JWT（写一律拒绝未登录）；user 写端点对 vet token → 403
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }
}
