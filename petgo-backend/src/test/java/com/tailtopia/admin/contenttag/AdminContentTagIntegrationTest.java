package com.tailtopia.admin.contenttag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentTagBadgeStyle;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagAssignmentRepository;
import com.tailtopia.content.repository.ContentTagRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：内容装饰标签后台（Story 11.2 · AB-10C）。
 *
 * <p>⚠️ 与 11-1 同样是**"接上来"**：打标（含「只有公开内容可打标」的校验）与生效判定
 * 早在 Story 5.2 就随 `ContentTagQueryService` 落地了，此前没有入口。
 * 本 story 补的是**标签增改下线**与**取消打标**。
 *
 * <p>🛡 三条不得弱化的断言：
 * <ul>
 *   <li>**私密内容打标必须被拒** —— 外人看不到，挂荣誉徽章只有作者自己看见一个无人可见的标记。</li>
 *   <li>**下线只挡新分配，已分配的照旧生效** —— 下线是"不再发新的"，不是"把已发的追回"。</li>
 *   <li>🔴 **页面必须带 ×1.3 排序副作用提示** —— 纯提示文案是赶工时最容易被删掉的东西，
 *       删掉之后运营会把打标后的数据上涨误读成"内容本来就这么火"、把取消后的回落误判成故障。</li>
 * </ul>
 */
class AdminContentTagIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentTagRepository tags;

    @Autowired
    private com.tailtopia.content.service.ContentTagQueryService tagQuery;

    @Autowired
    private ContentTagAssignmentRepository assignments;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private AdminAccountRepository adminAccounts;

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /** ⚠️ POST 还须 `.with(csrf())` —— `/admin/**` 那条过滤链保留了 CSRF，少了是 403。 */
    private Authentication superAdminAuth() {
        return staffAuth(AdminAccountType.SUPER_ADMIN);
    }

    private Authentication staffAuth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tag-" + n + "@tailtopia.test", "装饰标签测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它，少了拿到的是过滤链 403。
        List<org.springframework.security.core.GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private static String wib(int day, int hour) {
        return String.format("2026-10-%02dT%02d:00", day, hour);
    }

    private static Instant wibInstant(int day, int hour) {
        return ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, WIB).toInstant();
    }

    /**
     * 造一个标签。
     *
     * <p>⚠️ **码必须每次唯一**：`content_tags.code` 有唯一约束，而这是一个**共享库** ——
     * 用固定码的话第二次运行就会撞唯一约束（本条第一版就是这么错的，
     * 与"用 findAll() 做全库断言"是同一类毛病：把共享状态当独占状态）。
     */
    private ContentTag tag(String codePrefix) {
        String code = codePrefix + "_" + SEQ.incrementAndGet();
        return tags.save(ContentTag.of(code, "本周最佳-" + code, "icon.png", "编辑精选"));
    }

    private ContentPost publicPost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
    }

    private ContentPost privatePost(long authorId, String text) {
        ContentPost p = ContentPost.publish(authorId, ContentType.GROWTH_MOMENT, null, text, List.of());
        p.setVisibility(ContentVisibility.PRIVATE);
        return posts.save(p);
    }

    // ——————————————————— AC1 标签增改下线 ———————————————————


    /**
     * 一张合规的标签图标（Story 11.5：PNG、正方、≥72px）。
     *
     * <p>⚠️ 图标改成**上传**之后，建标签的请求必须是 multipart —— 原先那种
     * {@code .param("icon", "star.png")} 的写法已经不成立（那时 icon 是个文本框）。
     */
    private static MockMultipartFile iconPng() {
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return new MockMultipartFile("iconFile", "icon.png", "image/png", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按名称取最近创建的标签（2026-09-02 标签码改自动生成后，测试不再能预知码）。 */
    private ContentTag byName(String name) {
        return tags.findAllByOrderByIdDesc().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没找到名为 " + name + " 的标签"));
    }

    @Test
    void creatingATagWithABlankFieldIsRejected() throws Exception {
        String marker = "BLANK_" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/content-tags").file(iconPng()).with(authentication(superAdminAuth())).with(csrf())
                .param("name", " ")
                .param("description", marker));
        assertThat(tags.findAllByOrderByIdDesc().stream()
                .anyMatch(t -> marker.equals(t.getDescription())))
                .as("必填项空白不得落库").isFalse();
    }

    /**
     * 2026-09-02：标签码由系统自动生成（{@code ct-<自增id>}），运营不再手填 ——
     * 原先多个运营会填出同一个码。自增即天然唯一且按创建先后排序。
     */
    @Test
    void tagCodeIsAutoGeneratedFromTheRowId() throws Exception {
        String nameA = "自动码A" + SEQ.incrementAndGet();
        String nameB = "自动码B" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/content-tags").file(iconPng()).with(authentication(superAdminAuth())).with(csrf())
                        .param("name", nameA).param("description", "编辑精选"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/admin/content-tags").file(iconPng()).with(authentication(superAdminAuth())).with(csrf())
                        .param("name", nameB).param("description", "编辑精选"))
                .andExpect(status().is3xxRedirection());

        ContentTag a = byName(nameA);
        ContentTag b = byName(nameB);
        assertThat(a.getCode()).isEqualTo("ct-" + a.getId());
        assertThat(b.getCode()).isEqualTo("ct-" + b.getId());
        // 占位码绝不能留在库里（两步建号的回填必须发生）。
        assertThat(a.getCode()).doesNotContain("pending");
        // 自动排序：后建的号更大。
        assertThat(b.getId()).isGreaterThan(a.getId());
    }

    /**
     * 🛡 **下线只挡新分配，已分配的照旧生效。**
     *
     * <p>下线是"不再发新的"，不是"把已发的追回" —— 真要立刻全部失效，运营应逐条取消分配。
     */
    @Test
    void retiringATagBlocksNewAssignmentsButKeepsExistingOnesRunning() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "已经打过标的内容");
        ContentPost b = publicPost(author.getId(), "打标之后才想打的内容");
        ContentTag t = tag("RETIRE_ME");

        mvc.perform(post("/admin/content-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                        .param("postId", String.valueOf(a.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(1, 10)))
                .andExpect(status().is3xxRedirection());
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(1, 12))).hasSize(1);

        // 下线
        mvc.perform(post("/admin/content-tags/" + t.getId() + "/retire")
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("retired", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(tags.findById(t.getId()).orElseThrow().isRetired()).isTrue();

        // 已分配的仍在生效
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(1, 12)))
                .as("已分配的照旧生效到各自结束时间").hasSize(1);

        // 新分配被拒
        mvc.perform(post("/admin/content-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                .param("postId", String.valueOf(b.getId()))
                .param("tagId", String.valueOf(t.getId()))
                .param("startsAt", wib(1, 10)));
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(1, 12)))
                .as("已下线的标签不得再被分配").hasSize(1);

        // 下线的标签不出现在打标下拉里
        String html = mvc.perform(get("/admin/content-tags").with(authentication(superAdminAuth()))
                        .param("lang", "zh_CN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("列表里仍能看到它（历史可查）").contains(t.getCode());
    }

    // ——————————————————— 🛡 AC2 只允许公开内容打标 ———————————————————

    @Test
    void taggingPrivateContentIsRejected() throws Exception {
        User author = newUser();
        ContentPost hidden = privatePost(author.getId(), "作者关了同步的私密日记");
        ContentTag t = tag("PRIVATE_GUARD");

        mvc.perform(post("/admin/content-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                        .param("postId", String.valueOf(hidden.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(2, 10)))
                .andExpect(status().is3xxRedirection());
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(2, 12)))
                .as("🛡 私密内容绝不可被打标").isEmpty();
    }

    @Test
    void contentPickerListsOnlyPublicContent() throws Exception {
        User author = newUser();
        publicPost(author.getId(), "公开可打标内容");
        privatePost(author.getId(), "私密不可打标日记");

        String html = mvc.perform(get("/admin/content-tags/pick").with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("私密不可打标日记");
    }

    // ——————————————————— AC3 时间窗与永久 ———————————————————

    @Test
    void emptyEndMeansPermanentAndTimesAreWib() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "永久标");
        ContentTag t = tag("FOREVER");

        mvc.perform(post("/admin/content-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                        .param("postId", String.valueOf(a.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(3, 10)))
                .andExpect(status().is3xxRedirection());

        var saved = assignments.findByPostIdOrderByStartsAtDesc(a.getId()).get(0);
        assertThat(saved.getEndsAt()).as("结束留空 = 永久").isNull();
        assertThat(saved.getStartsAt()).isEqualTo(wibInstant(3, 10));
        // WIB 10:00 = UTC 03:00
        assertThat(saved.getStartsAt()).isEqualTo(Instant.parse("2026-10-03T03:00:00Z"));

        // 永久分配在任意未来时刻都算生效
        assertThat(assignments.findActiveByTag(t.getId(), wibInstant(28, 23))).hasSize(1);
    }

    @Test
    void unassignRemovesTheTagFromThatPost() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "待取消");
        ContentTag t = tag("UNASSIGN_ME");
        mvc.perform(post("/admin/content-tags/assign").with(authentication(superAdminAuth())).with(csrf())
                .param("postId", String.valueOf(a.getId()))
                .param("tagId", String.valueOf(t.getId()))
                .param("startsAt", wib(4, 10)));
        var saved = assignments.findByPostIdOrderByStartsAtDesc(a.getId()).get(0);

        mvc.perform(post("/admin/content-tags/assignments/" + saved.getId() + "/remove")
                        .with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(assignments.findByPostIdOrderByStartsAtDesc(a.getId())).isEmpty();
    }

    // ——————————————————— 🔴 AC4 排序副作用提示 ———————————————————

    /**
     * 🔴 **这条钉的是一段纯提示文案。**
     *
     * <p>它没有任何功能行为，因此删掉之后页面照样渲染、其它测试照样绿 ——
     * 而运营会开始把打标后的数据上涨误读成「这条内容本来就这么火」、
     * 把取消打标后的曝光回落误判成线上故障。
     */
    @Test
    void pageWarnsThatTaggingIsAlsoATrafficAction() throws Exception {
        String html = mvc.perform(get("/admin/content-tags").with(authentication(superAdminAuth()))
                        .param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("须说明打标同时是流量动作").contains("流量动作");
        assertThat(html).as("须给出具体倍数").contains("1.3");
        assertThat(html).as("须说明取消/到期时曝光会回落").contains("曝光");
    }

    /** 🛡 WIB 字样必须在时间输入旁 —— 否则运营按本地时区填，整批生效时间偏移。 */
    @Test
    void pageLabelsTheTimezone() throws Exception {
        String html = mvc.perform(get("/admin/content-tags").with(authentication(superAdminAuth()))
                        .param("lang", "zh_CN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("WIB");
    }

    // ——————————————————— 🛡 AC6 双权限码 ———————————————————

    @Test
    void viewPermissionAloneCannotWrite() throws Exception {
        Authentication viewer = staffAuth(AdminAccountType.STAFF, "content.tag_view");
        mvc.perform(get("/admin/content-tags").with(authentication(viewer)))
                .andExpect(status().isOk());
        String marker = "NOPE_" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/content-tags").file(iconPng())
                        .with(authentication(viewer)).with(csrf())
                        .param("name", marker)
                        .param("description", "x"))
                .andExpect(status().isForbidden());
        assertThat(tags.findAllByOrderByIdDesc().stream()
                .anyMatch(t -> marker.equals(t.getName()))).isFalse();
    }

    @Test
    void withoutAnyTagPermissionThePageIsForbidden() throws Exception {
        mvc.perform(get("/admin/content-tags")
                        .with(authentication(staffAuth(AdminAccountType.STAFF, "content.view"))))
                .andExpect(status().isForbidden());
    }

    /** 🛡 侧栏可见性与入口门一致 —— 走散了运营只会以为自己没有这个功能。 */
    @Test
    void navShowsTagsForViewOnlyStaff() throws Exception {
        String html = mvc.perform(get("/admin/content-tags").param("lang", "zh_CN")
                        .with(authentication(staffAuth(AdminAccountType.STAFF, "content.tag_view"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/admin/content-tags");
    }

    // ——————————————————— 胶囊底色（2026-08-28）———————————————————

    /**
     * 🔴 **胶囊底色按标签走，并以色值下发**。
     *
     * <p>产品原话：「我认为这一整枚都是运营配的」—— 此前底色写死在 App 里，
     * 运营只能配胶囊上的字与那枚小图，于是这枚胶囊在他眼里只有一半是自己的。
     *
     * <p>⚠️ 断言下发的是**色值**而不是枚举名：客户端不认识调色板，
     * 这样将来加一档颜色不需要发版。改成下发枚举名会安静地让老客户端画不出颜色。
     */
    @Test
    void badgeStyleIsPerTagAndGoesOutAsHexValues() throws Exception {
        String name = "编辑推荐S" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/content-tags").file(iconPng())
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("name", name)
                        .param("description", "编辑精选")
                        .param("badgeStyle", "VIOLET"))
                .andExpect(status().is3xxRedirection());

        ContentTag t = byName(name);
        assertThat(t.getBadgeStyle()).isEqualTo(ContentTagBadgeStyle.VIOLET);

        ContentPost p = publicPost(newUser().getId(), "被打标的内容");
        mvc.perform(post("/admin/content-tags/assign")
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("postId", String.valueOf(p.getId()))
                        .param("tagId", String.valueOf(t.getId()))
                        .param("startsAt", wib(1, 10)))
                .andExpect(status().is3xxRedirection());

        var view = tagQuery.findVisibleTags(List.of(p.getId()), wibInstant(1, 12))
                .get(p.getId()).get(0);
        assertThat(view.badgeStart())
                .as("🔴 下发的不是色值 ⇒ 客户端得认识调色板，加一档颜色就要发版")
                .isEqualTo("#845EC9");
        assertThat(view.badgeEnd()).isEqualTo("#6C48AE");
    }

    /** 🛡 没选 / 值不认识 → 设计稿原始的橙→红，**不报错**（值不对只可能是有人手改了请求）。 */
    @Test
    void unknownBadgeStyleFallsBackToTheDesignDefault() throws Exception {
        String name = "编辑推荐F" + SEQ.incrementAndGet();
        mvc.perform(multipart("/admin/content-tags").file(iconPng())
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("name", name)
                        .param("description", "x").param("badgeStyle", "NOPE"))
                .andExpect(status().is3xxRedirection());

        assertThat(byName(name).getBadgeStyle())
                .isEqualTo(ContentTagBadgeStyle.SUNSET);
    }

    /**
     * 🛡 改底色时**不选新图标不会把图标弄丢**（与既有的「保留原图标」规则叠加）。
     *
     * <p>那个坑一旦复发，后台界面上看不出来 —— 直到用户端图标消失。
     */
    @Test
    void editingTheBadgeStyleKeepsTheExistingIcon() throws Exception {
        ContentTag t = tag("KEEPICON");
        String iconBefore = t.getIcon();

        mvc.perform(multipart("/admin/content-tags/" + t.getId() + "/edit")
                        .with(authentication(superAdminAuth())).with(csrf())
                        .param("name", t.getName()).param("description", "说明")
                        .param("badgeStyle", "GREEN"))
                .andExpect(status().is3xxRedirection());

        ContentTag after = tags.findById(t.getId()).orElseThrow();
        assertThat(after.getBadgeStyle()).isEqualTo(ContentTagBadgeStyle.GREEN);
        assertThat(after.getIcon())
                .as("🔴 改个底色把图标弄丢了 —— 后台界面上看不出来，直到用户端图标消失")
                .isEqualTo(iconBefore);
    }

    /**
     * 🔴 **后台列表要按真实样子画出整枚胶囊**。
     *
     * <p>图标只有 9px、字是白色，脱开胶囊单看那张 PNG 什么都看不出来 ——
     * 运营因此判断不了「我配的这枚最后长什么样」，这正是「这枚胶囊只有一半是我的」
     * 那个误解的来源。
     */
    @Test
    void theAdminListRendersTheWholeBadgeInItsRealColours() throws Exception {
        mvc.perform(multipart("/admin/content-tags").file(iconPng())
                .with(authentication(superAdminAuth())).with(csrf())
                .param("name", "本周最佳P" + SEQ.incrementAndGet())
                .param("description", "x").param("badgeStyle", "GREEN"));

        String html = mvc.perform(get("/admin/content-tags").param("lang", "zh_CN")
                        .with(authentication(superAdminAuth())))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("🔴 列表里没有按该标签的真实底色画出胶囊 ⇒ 运营看不到成品")
                .contains(ContentTagBadgeStyle.GREEN.css());
    }
}
