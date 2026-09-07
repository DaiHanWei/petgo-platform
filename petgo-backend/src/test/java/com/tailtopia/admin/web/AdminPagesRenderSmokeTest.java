package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1：全部外化 admin 页渲染冒烟（Story 1.6 AC4/AC8；本次由中英扩到<b>三语</b>）。
 * 以在职超管身份 GET 各页，断言 200、模板成功渲染、且没有把 message key 当文案漏到页面上。
 *
 * <p>⚠️ 光查 Thymeleaf 的 {@code ??key??} 标记是不够的：本项目的 MessageSource 配了
 * {@code useCodeAsDefaultMessage(true)}，缺键<b>不会</b>产生 {@code ??...??}，而是安静地把
 * {@code admin.nav.dashboard} 这样的键名本身渲染成文案。所以这里另外扫「元素文本以 admin./perm./role. 开头」
 * 的形态——那是漏译唯一的外在症状。
 */
class AdminPagesRenderSmokeTest extends ApiIntegrationTest {

    @Autowired
    private AdminAccountRepository adminAccounts;

    /**
     * 真实路由表（清单自检用）：从这里反查后台页，避免手工维护清单反复漏页。
     *
     * <p>⚠️ 必须点名 {@code requestMappingHandlerMapping} —— actuator 另注册了一个同类型的
     * {@code controllerEndpointHandlerMapping}，不限定则注入歧义、整个测试类起不来。
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping;

    private Authentication superAdminAuth() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "render-" + n + "@tailtopia.test", "渲染冒烟超管", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    /** 漏译的外在症状：元素文本直接以 message key 前缀开头（见类注释）。 */
    private static final java.util.regex.Pattern LEAKED_KEY =
            java.util.regex.Pattern.compile(">\\s*(admin|perm|role)\\.[a-zA-Z0-9_.]+\\s*<");

    private void assertRenders(String path, String lang) throws Exception {
        String html = mvc.perform(get(path).param("lang", lang).with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as(path + " (" + lang + ") 缺键标记").doesNotContain("??admin.");
        assertThat(html).as(path + " (" + lang + ") 应有内容").isNotEmpty();

        java.util.regex.Matcher m = LEAKED_KEY.matcher(html);
        assertThat(m.find())
                .as(path + " (" + lang + ") 把 message key 当文案渲染了："
                        + (m.reset().find() ? m.group() : ""))
                .isFalse();
    }

    /**
     * 🔴 运营配置页的**每一块都必须真的出现在页面上**。
     *
     * <h2>这条守的是一个真实事故（2026-08-26 实机截图发现）</h2>
     * 「首页推荐算法」整块（Story 16.4 交付）当时写在了 {@code th:fragment="content"} 的
     * <b>闭合标签之外</b>，而模板只渲染那个片段 ⇒ <b>整块被静默丢弃，从交付起就没显示过</b>。
     * 连带 Story 17.1 加在同一表单里的「限流系数」输入框也一起不可见 ——
     * 也就是那两条 story 的「后台可配」实际上办不到。
     *
     * <p>⚠️ <b>当时全套测试都是绿的</b>，因为没有一条测到「这一块在页面上」：
     * 本类的 {@code assertRenders} 只验「渲染不报错」；服务层测试直接调 service；
     * 端点测试直接 POST。**丢掉一整块 HTML 不会让任何一条变红。**
     *
     * <p>断言用 form 的 action 与 {@code data-section} 标记做锚点，
     * 不断言可见文案 —— 文案会随 i18n 变，而锚点不会。
     */
    @Test
    void everyConfigSectionIsActuallyRenderedOnThePage() throws Exception {
        String html = mvc.perform(get("/admin/config").with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("定价配置整块不见了").contains("/admin/config/pricing");
        assertThat(html).as("PawCoin 整块不见了").contains("/admin/config/pawcoin");
        assertThat(html).as("分享奖励整块不见了（Story 18.3）")
                .contains("/admin/config/share-reward");
        assertThat(html).as("充值档位整块不见了").contains("/admin/config/tiers/");
        // ⚠️ 首页推荐算法已于 2026-08-26 搬到独立页「算法参数」（不对运营开放）——
        //    这里反向断言它**不再**出现在运营配置页上，否则就是搬漏了、两处都有。
        // 🔴 断的是**表单**（action + 输入框名），不是 `/admin/algo-params` 这个串 ——
        //    侧栏导航链接在每一页都有，拿它断言会恒红。第一版就是这么写的，被本条自己抓到。
        assertThat(html).as("算法参数的表单应该已经搬走了，运营配置页上不该还有")
                .doesNotContain("/admin/config/feed-rank")
                .doesNotContain("name=\"throttleFactor\"");
    }

    /**
     * 🔴 「算法参数」页的每一块都必须真的在页面上（2026-08-26 独立成页）。
     *
     * <p>与上一条同源的教训：Story 16.4 的这一块曾经因为写在 {@code th:fragment} 之外而
     * <b>整块被静默丢弃</b>，全套测试照样绿。搬家之后同样要钉住。
     */
    @Test
    void algoParamPageRendersItsFormAndChangeLog() throws Exception {
        String html = mvc.perform(get("/admin/algo-params").with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("算法参数表单不见了").contains("/admin/algo-params");
        assertThat(html).as("🔴 限流系数输入框不见了（Story 17.1 挂在这个表单里）")
                .contains("throttleFactor");
        assertThat(html).as("🔴 变更记录表不见了 —— 没有 A/B 时它是唯一的锚点")
                .contains("data-section=\"algo-changelog\"");
        assertThat(html).as("「不对运营开放」的说明不见了")
                .contains("data-notice=\"algo-not-for-ops\"");
        assertThat(html).as("「无 A/B 实验基建」的提醒不见了")
                .contains("data-notice=\"algo-no-ab\"");
    }

    /**
     * 侧栏分组归属与次序由**产品指定**（2026-08-20 定次序，2026-08-26 重排分组）。
     *
     * <p><b>2026-08-26 的重排</b>：
     * <ul>
     *   <li>「用户」「被举报用户」从「内容」组移入「用户运营」—— 这两项处置的是<b>账号</b>
     *       （查看资料 / 警告 / 封号），与内容维度不是一回事；原先挤在内容组里，
     *       运营要找人得先点「内容」。</li>
     *   <li>原「内容运营」整组（顶置 / 内容标签 / 互动积分）并入「内容」—— 它们本就作用在
     *       内容上，单列一组只是让运营在两组间来回找。该分组连同 i18n 键一并删除。</li>
     * </ul>
     *
     * <p>钉住它是因为这个次序<b>没有任何自解释的规律</b>（不是字母序、不是权限分组序），
     * 后来人加菜单时很容易按「新加的往后排」或顺手重排，而改错了没有任何报错 ——
     * 只有运营下次找不到入口时才会发现。
     *
     * <p>⚠️ 断言的是**文档中的先后**，所以它同时守住了「哪一项在哪个组里」：
     * 用户 / 被举报用户若被挪回内容组，就会跑到顶置管理前面，本条即红。
     */
    @Test
    void contentNavKeepsTheProductSpecifiedOrder() throws Exception {
        String html = visibleText("/admin/dashboard");
        java.util.List<String> expected = java.util.List.of(
                // 内容组
                "/admin/seed-post", "/admin/content", "/admin/comments",
                "/admin/manual-review", "/admin/content-pins", "/admin/content-tags",
                // 用户运营组（在内容组之后）
                "/admin/users", "/admin/tickets", "/admin/user-tags");
        java.util.List<Integer> positions = expected.stream().map(html::indexOf).toList();
        assertThat(positions).as("每个入口都应渲染出来").doesNotContain(-1);
        assertThat(positions).as("侧栏次序：" + String.join(" → ", expected))
                .isSorted();
    }

    /**
     * 侧栏「人工复核」链接的可见性必须与该页入口门一致（2026-08-20 一并放宽到 content.takedown）。
     *
     * <p>两边走散时的表现最难查：**权限放行了、直接敲 URL 能进，但侧栏里没有这个链接** ——
     * 运营只会得出「我没有这个功能」，而日志、403、报错一概没有，无从下手。
     *
     * <p>这里以只持 {@code content.takedown} 的 STAFF 身份渲染任意一页，断言侧栏里有这个链接。
     */
    @Test
    void navShowsManualReviewForTakedownOnlyStaff() throws Exception {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "navstaff-" + n + "@tailtopia.test", "只有下架权的审核员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 那条链在 URL 层就要求它（SecurityConfig
        //    anyRequest().hasRole("ADMIN")），少了它拿到的是过滤链的 403，
        //    根本走不到 @PreAuthorize —— 会被误读成「权限门没放行」。
        Authentication staff = new TestingAuthenticationToken(principal, null,
                java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("content.takedown")));

        String html = mvc.perform(get("/admin/manual-review").param("lang", "zh_CN")
                        .with(authentication(staff)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("只持 content.takedown 应能打开本页（入口门 2026-08-20 放宽）")
                .isNotEmpty();
        assertThat(html).as("侧栏应有「人工复核」入口，否则运营只会以为自己没有这个功能")
                .contains("/admin/manual-review");
    }

    /**
     * 页内标题必须与导航名一致（bug 20260820）。
     *
     * <p>2026-08-19 那次拆分改了导航名（工单队列 → 被举报用户），却漏了页内 h1、
     * 浏览器 title、副标题和权限显示名 —— 侧栏写「被举报用户」、点进去大标题还是
     * 「工单队列」，副标题还写着「三类工单统一排队」，而这页早就只剩一类了。
     *
     * <p>此前没有任何测试盯着这种不一致：缺键会被 {@code ??admin.} 断言抓到，
     * <b>但「键在、值过期」渲染得完全正常</b>。这条直接钉住渲染出的字面量。
     */
    @Test
    void pageHeadingsMatchTheirNavLabels() throws Exception {
        String tickets = visibleText("/admin/tickets");
        assertThat(tickets).as("被举报用户页的标题").contains("被举报用户");
        assertThat(tickets).as("拆分后本页只剩用户举报一类，旧标题不该再出现")
                .doesNotContain("工单队列").doesNotContain("三类工单统一排队");

        assertThat(visibleText("/admin/manual-review")).as("人工复核页的标题").contains("人工复核");

        // Story 11.1：侧栏叫「顶置管理」，页内标题必须同名（四处同源里的前两处）。
        assertThat(visibleText("/admin/content-pins")).as("顶置管理页的标题").contains("顶置管理");
        // Story 11.2：侧栏叫「内容标签」，页内标题必须同名（2026-08-28 由「装饰标签」更名）。
        // ⚠️ 旧名一并断言不再出现 —— 改名时它正是被漏在页内的那个字。
        String contentTags = visibleText("/admin/content-tags");
        assertThat(contentTags).as("内容标签页的标题").contains("内容标签");
        assertThat(contentTags).as("改名后旧名不该再出现在页面上").doesNotContain("装饰标签");
        // Story 11.3：侧栏叫「用户标签」，页内标题必须同名。
        assertThat(visibleText("/admin/user-tags")).as("用户标签页的标题").contains("用户标签");
    }

    /**
     * 页面渲染结果**去掉 HTML 注释**后的内容。
     *
     * <p>后台模板里的中文说明注释会原样发到浏览器（Thymeleaf 不吃标准 HTML 注释），
     * 其中不乏「从『工单队列』拆分而来」这类如实记述历史的句子 ——
     * 直接对整份 HTML 断言「不含旧名」会被这些注释误伤。这里只留可见文本。
     */
    private String visibleText(String path) throws Exception {
        String html = mvc.perform(get(path).param("lang", "zh_CN")
                        .with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 筛选栏的下拉「选完即刷新」（bug 20260820）：靠 {@code form[data-autosubmit]} +
     * admin.js 的 change 委托实现。
     *
     * <p>这条守的是**属性别被顺手删掉** —— 删了页面照样渲染、测试照样绿，
     * 只是运营又得多点一次「筛选」，而这种回退没人会立刻注意到。
     * （JS 行为本身后台没有测试基建，这里只钉住服务端渲染出的那半边。）
     */
    @Test
    void filterBarsOptInToAutoSubmit() throws Exception {
        for (String path : new String[] {"/admin/manual-review", "/admin/tickets"}) {
            String html = mvc.perform(get(path).with(authentication(superAdminAuth())))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as(path + " 的筛选表单应带 data-autosubmit").contains("data-autosubmit");
        }
    }

    /**
     * 逐页双语扫描的清单。**不要再手工判断该不该往里加** ——
     * {@link #everyParameterFreeAdminPageIsInTheBilingualSweep()} 会把漏掉的页点名报出来。
     */
    static final java.util.List<String> SWEPT_PAGES = java.util.List.of("/admin/dashboard", "/admin/seed-post", "/admin/tickets", "/admin/content",
                "/admin/content-pins", "/admin/content-tags", "/admin/user-tags",
                "/admin/manual-review", "/admin/anomalies", "/admin/consult-sessions", "/admin/vets",
                "/admin/vets/online", "/admin/failed-requests", "/admin/ratings", "/admin/users",
                "/admin/audit-logs", "/admin/accounts",
                // V1.1.6 Story 12.1：「运营发布身份」页（虚拟账号 + 运营真实账号两区）。
                // ⚠️ 这一页此前不在本表里 —— 加进来才会验它的 i18n 键在两种语言下都齐。
                "/admin/virtual-accounts",
                // V1.1.6 Story 13.2：批次列表（工作台需要一个真实 batchId，另在其专属测试里渲染）。
                "/admin/seed-batches",
                // V1.1.6 Story 13.5：排期管理（12-1 的移出提示会跳到这里）。
                "/admin/content-schedules",
                // V1.1.6 Story 15.1：内容互动积分榜。
            // 2026-08-26：算法参数独立成页，须一并纳入逐页双语扫描
            "/admin/algo-params");

    /**
     * 🔴 <b>自动发现的双语扫描</b>：凡是「无路径参数的后台 GET」，一律真跑一遍并施加与
     * {@link #assertRenders} 相同的检查 —— <b>新加一页自动进网，不需要谁记得回来加一行</b>。
     *
     * <h2>为什么不用手写清单</h2>
     * 上面那张 {@link #SWEPT_PAGES} 是手写的，而它本身就漏过页：Story 12.1 的
     * 「运营发布身份」页曾长期不在表内（注释里还留着「这一页此前不在本表里」），
     * 于是它的 i18n 键在任何语言下都没被验过。手写清单会以同一种方式反复漏 ——
     * 新页作者不知道有这张表。本条落地时实际覆盖 <b>46 页</b>，而手写清单只有 22 页：
     * 多出来的 24 页（电商全部 12 页 + 运营配置 / 评论管理 / 支付 / 退款 / 结算 /
     * 客服工单 / AI 单 / 问诊单 / 红码超额等）<b>此前从未在任何语言下被验过</b>，
     * 而它们是运营天天要点的页面。
     *
     * <h2>刻意不做豁免名单</h2>
     * 第一版写成「发现的路由必须出现在清单里」，结果逼出一张 11 条的豁免名单
     * （跳转 / HTMX 片段 / 登录页）。豁免名单迟早会被当成绿灯开关用 ——
     * 「加进去就绿了」。改成<b>运行时判定</b>后一条名单都不需要：
     * <ul>
     *   <li>3xx（{@code /admin} → dashboard、{@code /admin/reports} → 人工复核）→ 不是页面，跳过；</li>
     *   <li>响应体没有 {@code <html>}（HTMX 片段，如挑内容/挑标签/挑宠物）→ 不是整页，跳过；</li>
     *   <li>其余即整页，一律检查。</li>
     * </ul>
     *
     * <p>🔴 顺带补上一条清单永远给不了的保障：<b>5xx 一律失败</b>。手写清单之外的页
     * 从前连「打得开」都没人验过 —— 模板表达式写错只在渲染那一刻才炸，编译期查不出来。
     *
     * <p>⚠️ 带路径参数的详情页（某个兽医 / 某个订单 / 某个批次）**不在本条范围内**：
     * 它们要先造出那条数据才打得开，归各自 story 的集成测试。
     */
    @Test
    void everyParameterFreeAdminPageSurvivesTheBilingualSweep() throws Exception {
        java.util.List<String> failures = new java.util.ArrayList<>();
        java.util.Set<String> actuallyChecked = new java.util.TreeSet<>();
        for (String path : parameterFreeAdminGets()) {
            for (java.util.Locale locale : com.tailtopia.shared.i18n.AdminLocaleConfig.SUPPORTED_LOCALES) {
                var res = mvc.perform(get(path).param("lang", locale.toString())
                        .with(authentication(superAdminAuth()))).andReturn().getResponse();
                if (res.getStatus() >= 500) {
                    failures.add(path + " (" + locale + ") 渲染 " + res.getStatus()
                            + " —— 模板表达式写错只在渲染那一刻才炸");
                    continue;
                }
                if (res.getStatus() >= 300) {
                    continue; // 纯跳转，不是页面
                }
                String html = res.getContentAsString();
                if (!html.contains("<html")) {
                    continue; // HTMX 片段，不是整页
                }
                actuallyChecked.add(path);
                if (html.contains("??admin.")) {
                    failures.add(path + " (" + locale + ") 有缺键标记 ??admin.");
                }
                java.util.regex.Matcher m = LEAKED_KEY.matcher(html);
                if (m.find()) {
                    failures.add(path + " (" + locale + ") 把 message key 当文案渲染了：" + m.group());
                }
            }
        }
        // 🛡 **防空跑**：本条的两个「跳过」分支（3xx / 非整页）是按响应内容判定的 ——
        //    哪天守卫、路由前缀或 layout 变了，它可能把所有页都跳过而依然全绿。
        //    钉一个下限，让「什么都没检查」这件事本身失败。2026-08-26 落地时实际检查到 **46 页**
        //    （手写清单只有 22 页），下限取 35 留出余量；新增页只会让它更宽松，不会误红。
        assertThat(actuallyChecked)
                .as("🔴 本条一页都没真检查到 —— 极可能是守卫/路由/layout 变了导致全部被跳过，"
                        + "而不是「没有问题」。实际检查到的页：" + actuallyChecked)
                .hasSizeGreaterThanOrEqualTo(35);
        assertThat(failures)
                .as("🔴 自动发现的后台页在双语扫描下不合格。漏译不会报错 —— MessageSource 配了"
                        + " useCodeAsDefaultMessage，缺键会安静地把 admin.xxx.yyy 键名本身当文案显示")
                .isEmpty();
    }

    /**
     * 🔴 **侧栏可见性必须与页面入口门一致**（权限自查 2026-08-26）。
     *
     * <h2>两个方向都要守，而且坏起来的样子完全不同</h2>
     * <ul>
     *   <li><b>侧栏比入口紧</b>（有权限却看不见入口）—— 最难查的一类：权限放行了、
     *       直接敲 URL 能进，但侧栏没有这个链接。运营只会得出「我没有这个功能」，
     *       而日志、403、报错<b>一概没有</b>。</li>
     *   <li><b>侧栏比入口松</b>（没权限却看得见）—— 点进去 403，整个后台看着到处报错。</li>
     * </ul>
     *
     * <p>本条落地时一次查出 <b>7 处</b>：4 处侧栏无门而入口有门（种子内容发布 / 兽医账号 /
     * 客服工单 / 退款管理），3 处侧栏漏码（内容管理漏 content.view、后台账号漏
     * admin.view_accounts、退货先例漏 refund.payout）。另有两处是**分组门**比入口紧
     * （客服组只认 support.handle、退款组不认 refund.view）—— 分组门必须是组内各项的并集。
     *
     * <h2>为什么这样测</h2>
     * 从 {@code @PreAuthorize} 里取权限码，逐个造一个「只持这一个码」的 STAFF 去渲染侧栏。
     * Java 会把常量拼接在编译期内联，所以注解里拿到的是**完整表达式**，
     * 不需要在测试里复刻 {@code AdminPermissions} 的常量表 —— 那种复刻本身就会漂。
     */
    @Test
    void everySidebarLinkMatchesItsPageGate() throws Exception {
        // 超管视角，用来确定「哪些页在侧栏里有入口」。
        // ⚠️ 必须显式给 ROLE_SUPER_ADMIN —— 第一版只给了 ROLE_ADMIN，于是 hasRole('SUPER_ADMIN')
        //    全部为假、侧栏几乎是空的，绝大多数页被当成「侧栏没有入口」整体跳过 ⇒ **本条形同空跑**
        //    （改错侧栏后照样绿，已实测）。这就是下面那条覆盖下限存在的理由。
        String nav = navHtmlFor("ROLE_SUPER_ADMIN");
        java.util.Set<String> checked = new java.util.TreeSet<>();
        java.util.List<String> tooTight = new java.util.ArrayList<>();
        java.util.List<String> tooLoose = new java.util.ArrayList<>();

        for (var e : pageGates().entrySet()) {
            String path = e.getKey();
            String expr = e.getValue();
            String marker = "href=\"" + path + "\"";
            if (!nav.contains(marker)) {
                continue; // 这一页侧栏本来就没有入口（详情页、跳转等），不在本条范围
            }
            java.util.Set<String> codes = new java.util.TreeSet<>();
            Matcher m = Pattern.compile("hasAuthority\\('([^']+)'\\)").matcher(expr == null ? "" : expr);
            while (m.find()) {
                codes.add(m.group(1));
            }
            if (codes.isEmpty()) {
                continue; // 无码门（对所有后台账号开放），无从比对
            }
            checked.add(path);
            // 方向一：凡是能进这一页的码，都必须在侧栏看到入口。
            for (String code : codes) {
                if (!navHtmlFor(code).contains(marker)) {
                    tooTight.add(path + " ← 只持 " + code + " 时侧栏没有入口（能进却看不见）");
                }
            }
            // 方向二：一个码都不持时，不该看见入口。
            if (navHtmlFor("__none__").contains(marker)) {
                tooLoose.add(path + " ← 不持任何相关码却看得见入口（点进去 403）");
            }
        }
        // 🛡 防空跑：上面两处 continue 都是「跳过」，一旦基线侧栏渲染不出来就会全跳过而依然全绿。
        //    2026-08-26 落地时实际比对 20 页，下限取 15 留余量。
        assertThat(checked)
                .as("🔴 一页都没真比对到 —— 极可能是基线侧栏没渲染出来（见上面 ROLE_SUPER_ADMIN 那条注释），"
                        + "而不是「没有问题」。实际比对到：" + checked)
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(tooTight)
                .as("🔴 侧栏比入口**紧**：运营有权限却看不到入口，只会得出「我没有这个功能」，"
                        + "而日志、403、报错一概没有 —— 这是最难查的一类")
                .isEmpty();
        assertThat(tooLoose)
                .as("🔴 侧栏比入口**松**：没权限的人看得见入口，点进去 403")
                .isEmpty();
    }

    /** 以「只持这些权限码」的 STAFF 渲染一页（取 /admin/dashboard —— 它无入口门，任何后台账号都进得去）。 */
    private String navHtmlFor(String... authorities) throws Exception {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "gate-" + n + "@tailtopia.test", "权限一致性 STAFF", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(),
                authorities.length == 0 ? AdminAccountType.SUPER_ADMIN : AdminAccountType.STAFF);
        java.util.List<org.springframework.security.core.GrantedAuthority> granted =
                new java.util.ArrayList<>();
        granted.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String a : authorities) {
            granted.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(a));
        }
        Authentication auth = new TestingAuthenticationToken(principal, null, granted);
        String html = mvc.perform(get("/admin/dashboard").param("lang", "zh_CN")
                        .with(authentication(auth)))
                .andReturn().getResponse().getContentAsString();
        // 🔴 只取 <nav>…</nav>：概览页**正文里也有若干快捷入口**（如种子内容发布），
        //    对整页搜索会把它们误当成侧栏链接 —— 第一版就这么写的，误报了 /admin/seed-post。
        int from = html.indexOf("<nav");
        int to = html.indexOf("</nav>");
        return (from >= 0 && to > from) ? html.substring(from, to) : "";
    }

    /** 每个「无路径参数的后台页」→ 它的 {@code @PreAuthorize} 表达式（常量已由编译期内联）。 */
    private java.util.Map<String, String> pageGates() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            boolean isGet = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods()
                            .contains(org.springframework.web.bind.annotation.RequestMethod.GET);
            if (!isGet || !handler.getBeanType()
                    .isAnnotationPresent(org.springframework.stereotype.Controller.class)) {
                return;
            }
            var pre = handler.getMethod()
                    .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/admin") && !pattern.contains("{")) {
                    out.put(pattern, pre == null ? null : pre.value());
                }
            }
        });
        return out;
    }

    /** 全部「无路径参数、由 {@code @Controller} 处理」的后台 GET 路由（真实路由表，不手工维护）。 */
    private java.util.List<String> parameterFreeAdminGets() {
        java.util.Set<String> paths = new java.util.TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            boolean isGet = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods()
                            .contains(org.springframework.web.bind.annotation.RequestMethod.GET);
            if (!isGet || !handler.getBeanType()
                    .isAnnotationPresent(org.springframework.stereotype.Controller.class)) {
                return;
            }
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/admin") && !pattern.contains("{")) {
                    paths.add(pattern);
                }
            }
        });
        return java.util.List.copyOf(paths);
    }

    @Test
    void allExternalizedAdminPagesRenderInEveryLocale() throws Exception {
        for (String p : SWEPT_PAGES) {
            for (java.util.Locale locale : com.tailtopia.shared.i18n.AdminLocaleConfig.SUPPORTED_LOCALES) {
                assertRenders(p, locale.toString());
            }
        }
    }
}
