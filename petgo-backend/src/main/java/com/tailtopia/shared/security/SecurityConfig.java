package com.tailtopia.shared.security;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.account.service.AdminLoginThrottle;
import com.tailtopia.admin.account.web.AdminLoginThrottleFilter;
import com.tailtopia.admin.account.web.AdminSessionGuardFilter;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.vet.web.BannedVetFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
// Story 1.3 AC5/T5：开启方法级安全，供 @PreAuthorize 细化到 admin.view_logs 等权限码；
// 既有 api/admin 链无 @PreAuthorize，开启对其无影响（注解式、按需生效）。
@EnableMethodSecurity
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
            AdminUserDetailsService adminUserDetailsService,
            AdminLoginThrottle loginThrottle,
            AdminAccountRepository adminAccounts,
            com.tailtopia.admin.audit.service.AdminAuditService auditService,
            com.tailtopia.admin.audit.service.AdminAlertService alertService) throws Exception {
        http
                .securityMatcher("/admin/**")
                .userDetailsService(adminUserDetailsService)
                // Story 1.1 AC4：前置限流——已锁定 username 的 POST /admin/login 直接回登录页(?locked)，不进入认证。
                .addFilterBefore(new AdminLoginThrottleFilter(loginThrottle),
                        UsernamePasswordAuthenticationFilter.class)
                // Story 1.2 AC5（A1）：会话守卫——每请求复查账号仍 ACTIVE，撤权/停用即时失效（放行 login/oauth 防死循环）。
                .addFilterBefore(new AdminSessionGuardFilter(adminAccounts),
                        AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // 静态资源放行（登录页未登录即需加载 CSS/JS，否则登录页裸奔无样式）。
                        // ⚠️ 用 *.css / *.js 模式而不是精确文件名：静态资源已开内容指纹
                        //   （bug 20260901-471，admin.js → admin-<md5>.js），精确名匹配不到
                        //    指纹化后的 URL，表现是登录页 CSS/JS 全 403、页面裸奔。
                        //    模式只覆盖 /admin/ 一级目录下的样式与脚本，不放行任何页面路由。
                        .requestMatchers("/admin/*.css", "/admin/*.js",
                                "/admin/vendor/**").permitAll()
                        // 登录页 + Lark OAuth 登录/回调放行（未登录可访问以建会话）
                        .requestMatchers("/admin/login", "/admin/oauth/**").permitAll()
                        // 其余后台页面一律要求 ADMIN（user/vet → 403 越权）
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        // Story 1.1 AC4：失败计数 + 达阈值锁定；统一文案不区分字段。
                        .failureHandler((request, response, exception) -> {
                            String username = request.getParameter("username");
                            loginThrottle.recordFailure(username);
                            String target = loginThrottle.isLocked(username)
                                    ? "/admin/login?locked" : "/admin/login?error";
                            response.sendRedirect(request.getContextPath() + target);
                        })
                        // 成功登录清零失败计数后进入后台。
                        .successHandler((request, response, authentication) -> {
                            loginThrottle.clear(request.getParameter("username"));
                            // Story 1.3 AC7：formLogin 即「紧急账密」入口（Lark OAuth 走 /admin/oauth/**，不经此）。
                            // 登录成功 → 写一条不可篡改审计 + 向全体在职超管发安全告警。摘要只记邮箱语义，绝不含密码。
                            if (authentication.getPrincipal()
                                    instanceof com.tailtopia.admin.service.AdminUserDetails admin) {
                                long accountId = admin.getAdminAccountId();
                                auditService.record(accountId,
                                        com.tailtopia.admin.audit.service.AuditActions.EMERGENCY_LOGIN_SUCCEEDED,
                                        "ADMIN_ACCOUNT", String.valueOf(accountId),
                                        "紧急账密登录成功：" + admin.getUsername() + "（来源=EMERGENCY_PASSWORD）");
                                alertService.alertSuperAdmins(
                                        com.tailtopia.admin.audit.service.AuditActions.EMERGENCY_LOGIN_SUCCEEDED,
                                        accountId);
                            }
                            response.sendRedirect(request.getContextPath() + "/admin/dashboard");
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout"))
                // 权限不足（URL 级门控 + @PreAuthorize 方法级拒绝，经 GlobalExceptionHandler 重抛回到本链）：
                // 403 + forward 到「权限不足」提示页，而非裸 Whitelabel/500。
                .exceptionHandling(ex -> ex.accessDeniedHandler(adminAccessDeniedHandler()));
        // CSRF 保持开启（表单链默认即开）；会话按需创建（表单登录态）。
        return http.build();
    }

    /** admin 链 403 落点：置 403 状态并 forward 至 /admin/denied 友好提示页（保留登录会话与侧栏）。 */
    private static org.springframework.security.web.access.AccessDeniedHandlerImpl adminAccessDeniedHandler() {
        var handler = new org.springframework.security.web.access.AccessDeniedHandlerImpl();
        handler.setErrorPage("/admin/denied");
        return handler;
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
                        // 运维/文档/公开 H5（名片 /p、里程碑庆祝分享 /m、单条内容分享 /c）放行。
                        // ⚠️ 三个前缀是**三种不同的分享类型**，各自落地页不同（Story 9.3 · AD-15 Rule 5）——
                        // 不可合并成一个通配。
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/p/**", "/m/**", "/c/**",
                                // 场所对外 H5（V1.3.0 batch-b1 Story 1.10 · AD-5）：
                                // 与前三页同性质 —— 服务端直出、公开无鉴权、noindex，
                                // 下架/不存在统一落 card_gone + 404（防枚举）。
                                "/place/**").permitAll()
                        // 品牌静态资源（H5 名片/分享页左上角 wordmark，bug 20260701-182）公开放行。
                        .requestMatchers(HttpMethod.GET, "/brand/**").permitAll()
                        // 法律政策 H5（隐私 / 条款 / Mitra 条款 / 账号删除 / 儿童安全 / 支持）+ 下载引导落地页公开放行（商店上架 + App WebView 引用）
                        .requestMatchers(HttpMethod.GET, "/privacy", "/terms", "/mitra-terms",
                                "/account-deletion", "/child-safety", "/support", "/get").permitAll()
                        // dev 诊断端点（仅 dev profile 存在）+ 错误转发
                        .requestMatchers("/api/v1/_ping-error", "/error").permitAll()
                        // 腾讯 IM 服务端回调（外部来源，内部 token/签名校验，Story 5.5）
                        .requestMatchers("/im/callback").permitAll()
                        // 支付网关回调（Midtrans 外部来源，内部 SHA-512 签名校验，Story 1.1）
                        .requestMatchers("/pay/callback").permitAll()
                        // IM UserSig 签发（Story 5.5）：显式要求已认证；用户态 MAU 闸门（非 VET 须有活跃会话）
                        // 在 ImUserSigController 内做（403），此处仅收口鉴权（401）。
                        .requestMatchers(HttpMethod.GET, "/api/v1/im/usersig").authenticated()
                        // App 版本信息（Story 6.5，游客可读，App 内更新提醒用）
                        .requestMatchers(HttpMethod.GET, "/api/v1/app-version").permitAll()
                        // 游客只读放行锚点（Story 1.5 细化具体业务 GET）
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        // Feed 只读对游客可见（Story 3.2，FR-0A/17）：GET 内容流放行（写仍需 JWT）
                        .requestMatchers(HttpMethod.GET, "/api/v1/content-posts").permitAll()
                        // 内容详情 + 评论只读对游客可见（Story 3.3）：GET 详情/评论/回复放行（写仍需 JWT）
                        .requestMatchers(HttpMethod.GET, "/api/v1/content-posts/**",
                                "/api/v1/comments/**").permitAll()
                        // 他人迷你主页只读对游客可见（Story 3.8，FR-26 无登录要求）
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/mini-profile").permitAll()
                        // 公开主页（V1.3.0 batch-b1 Story 2.1 · FR-118）：与迷你卡同一口径 ——
                        // 点头像即看，无登录要求。登录者可识别（viewer 用于 isBlocked / isReported），
                        // 但**只认 role=USER**（判定在 controller，兽医 token 的 sub 是 vetId）。
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/profile").permitAll()
                        // 公开主页的内容区（Story 2.2 · FR-118.2）：同上，游客可读。
                        // ⚠️ 可见范围**不靠这条放行**把关 —— 非 PUBLIC 的内容在 SQL 层就查不出来（NFR-2）；
                        // 拉黑守卫在 controller 里与 /profile **各拦一次**（只拦主页会留个绕过口）。
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/posts").permitAll()
                        // 主页宠物卡（Story 2.3 · AC3）：看这人养了只什么，同样不需要登录。
                        // ⚠️ **点进去**的宠物访客视图（`/api/v1/pets/*/visitor/**`）**刻意不在这里放行** ——
                        // AC1 明写"仅对登录用户开放"，它靠落进默认的 authenticated 规则实现，
                        // 而不是再写一条规则（少动一次安全配置就少一次出错机会）。
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*/pet").permitAll()
                        // Toko 商品只读对游客可见（V1.4.0 Story 1.1，FR-93A）：GET 商品列表/详情放行。
                        // 与 FR-78「未登录点击非落地 Tab 触发登录引导」有意不同——商品浏览是转化漏斗
                        // 最上层，登录墙会直接杀掉转化；登录引导推迟到「加入购物车」（Story 3.6）。
                        // 写入端点属 Story 1.3 后台，走 /admin/**，不在此放行。
                        .requestMatchers(HttpMethod.GET, "/api/v1/shop/products",
                                "/api/v1/shop/products/**").permitAll()
                        // Toko 顶部 banner（2026-08-27）：与商品列表同理 ——
                        // banner 在转化漏斗最上层，用登录墙拦它没有任何意义。
                        // 只读；配置走 /admin/shop/banners，不在此放行。
                        .requestMatchers(HttpMethod.GET, "/api/v1/shop/banner").permitAll()
                        // 行政区划树（Story 2.4）：区划与是否可配送都不敏感，
                        // 且用户在注册前就该能看到「你们送不送我这儿」。
                        .requestMatchers(HttpMethod.GET, "/api/v1/shop/regions").permitAll()
                        // 宠物友好场所只读对游客可见（V1.3.0 batch-b1 Story 1.1，FR-112.2）：
                        // 场所列表是「这个功能里已经攒了些什么地方」的展示面，用登录墙拦它没有意义
                        // ——同 Toko 商品列表的既定取舍。App 侧对应地**不把 /places 放进
                        // _controlledLocations**（Story 1.1 Dev Notes 明写「场所列表游客可看」）。
                        // 🔴 只放 GET：标记场所（Story 1.3）与场所评论（1.7）仍需 JWT；
                        //    且服务端**永远不提供场所编辑端点**（2026-09-15 拍板，纠错走后台 AB-17A）。
                        // 🔴 **只放这一个精确路径，不写 `/places/**` 通配**：通配等于替
                        //    还不存在的端点预先授权。1.7/1.8 一旦加 `/places/{token}/my-reaction`
                        //    这类「按调用者」的读接口，它会默认匿名可达 —— 而 controller 里
                        //    盲取的 currentUserId 是空 principal，结果是 500 而不是 401，
                        //    且这次放行在安全配置里看不见。子路径（详情 1.5 / H5 1.10）
                        //    各自在本文件显式加一行，这样每一次放开都留痕。
                        .requestMatchers(HttpMethod.GET, "/api/v1/places").permitAll()
                        // 标记场所（V1.3.0 batch-b1 Story 1.3）：**仅 role=USER**。
                        // 🔴 必须显式限定 —— PlaceController 把 jwt.sub 当 users.id 用，而兽医
                        // token 的 sub 是 vetId，与 users.id 是两个会大量碰撞的命名空间。落到
                        // anyRequest().authenticated() 的话，兽医能以一个无关用户的名义创建场所，
                        // 而 places.created_by 没有外键、会被静默写进去（同拉黑/举报端点的理由）。
                        // ⚠️ 用户不可编辑/删除场所，所以这条错写出去的归属**没有自助纠正途径**。
                        .requestMatchers(HttpMethod.POST, "/api/v1/places").hasRole("USER")
                        // 场所详情（Story 1.5）：同列表，GET 对游客放行。
                        // 🔴 仍然**不写 `/places/**` 通配** —— 只列出真实存在的路径形状，
                        //    每一次放开都留痕（评论列表 1.7 落地时在这里再加一行）。
                        .requestMatchers(HttpMethod.GET, "/api/v1/places/*").permitAll()
                        // 举报场所（Story 1.5）：**仅 role=USER**，与标记场所同一理由
                        // （controller 把 jwt.sub 当 users.id 用，兽医 token 的 sub 是 vetId）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/places/*/reports").hasRole("USER")
                        // 场所评论列表（Story 1.7）：同详情，GET 对游客放行。
                        // ⚠️ 这条**必须写在** `GET /api/v1/places/*` 之后也无妨（两者路径形状不同，
                        //    `/*` 只匹配一段），但绝不能省 —— 省了它游客拉评论会 401，
                        //    而详情页本身对游客开着，评论区就成了一块登录墙。
                        .requestMatchers(HttpMethod.GET, "/api/v1/places/*/comments").permitAll()
                        // 发表场所评论（Story 1.7）：**仅 role=USER**，与标记/举报同一理由
                        // （controller 把 jwt.sub 当 users.id 用，兽医 token 的 sub 是 vetId ——
                        // 落到 authenticated() 的话，兽医会以一个无关用户的名义发评论，
                        // 而 author_id 没有外键、会被静默写进去，且评论**只有作者本人能删**，
                        // 那个"作者"根本不是他 → 谁都删不掉）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/places/*/comments").hasRole("USER")
                        // 删除自己的场所评论（Story 1.7 AC7）：仅 role=USER；
                        // 「是不是本人」在 service 里硬校验，不靠这一行。
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/place-comments/*").hasRole("USER")
                        // 为场所补充照片（Story 1.9）：**仅 role=USER**，同标记/评论的理由
                        // （controller 把 jwt.sub 当 users.id 用；而且照片要"标注上传者"，
                        // 兽医 token 写进去的那个 uploader_id 根本不是他，他自己也删不掉）。
                        .requestMatchers(HttpMethod.POST, "/api/v1/places/*/photos").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/place-photos/*").hasRole("USER")
                        // 兽医工作台端点（Story 5.1+）：仅 role=VET 可达；user/guest → 403（双向门控）
                        .requestMatchers("/api/v1/vet/**").hasRole("VET")
                        // 用户侧问诊端点（Story 5.2+ / 计费流 3-2~3-4）：仅 role=USER 可达（vet/guest → 403）
                        .requestMatchers("/api/v1/consult/**",
                                "/api/v1/consult-sessions", "/api/v1/consult-sessions/**",
                                "/api/v1/consultations", "/api/v1/consultations/**").hasRole("USER")
                        // 客服工单端点（Story 4.1，FR-52）：用户建单/查单，仅 role=USER（vet/guest → 403）
                        .requestMatchers("/api/v1/support-tickets", "/api/v1/support-tickets/**").hasRole("USER")
                        // 拉黑与账号举报（V1.1.4 Story 1.5 / 2.1）：仅 role=USER。⚠️ 必须显式限定——
                        // controller 的 currentUserId 盲取 jwt.sub 当 users.id，而兽医 token 的 sub=vetId
                        // 与 users.id 是独立命名空间且大量碰撞；落到 anyRequest().authenticated() 会让
                        // 兽医以无关用户名义写入不可撤销的举报隐藏行（安全评审三轮 #1）。
                        .requestMatchers("/api/v1/me/blocked-users", "/api/v1/me/blocked-users/**",
                                "/api/v1/account-reports", "/api/v1/account-reports/**").hasRole("USER")
                        // 用户端退款方式选择/填收款（Story 4.5）：列表 + PawCoin 即时退 + QRIS 填账户，仅 role=USER
                        .requestMatchers("/api/v1/me/refund-requests",
                                "/api/v1/refund-requests/**").hasRole("USER")
                        // 订单中心聚合读接口（Story 5.1 列表 / 5.3 详情）：泛化 3 类订单，仅 role=USER
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/**").hasRole("USER")
                        // 其余 /api/v1 默认需 JWT（写一律拒绝未登录）；user 写端点对 vet token → 403
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        // 坏/过期 token 的 401 也走统一 ProblemDetail（否则默认 BearerToken 入口返回无 body/无 content-type）
                        .authenticationEntryPoint(problemHandlers)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoleConverter())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }
}
