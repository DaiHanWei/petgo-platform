package com.tailtopia.admin.pin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * L1 集成：顶置管理后台（Story 11.1 · AB-10A）。
 *
 * <p>⚠️ **本 story 交付的是"接上来"**：写入机制（含同坑位重叠校验）、生效判定、下架联动
 * 早在 Story 4.1 就随 `ContentPinService` 落地了 —— 只是**没有任何入口能调到它**。
 * 因此本类的重点是「后台这条路径**真的走到了**那套既有机制」，而不是重新验证机制本身。
 *
 * <p>🛡 两条安全攸关断言，不得弱化：
 * <ul>
 *   <li>**同坑位重叠必须被拦**，且**首尾相接不算重叠**（半开区间）——
 *       后台是唯一写入方，这里放过去，Feed 顶谁就取决于查询顺序。</li>
 *   <li>**内容选择器只返回公开内容** —— 把作者主动设为私密的内容顶到 Feed，
 *       直接违背 FR-83 给作者的可见范围选择权。</li>
 * </ul>
 */
class AdminContentPinIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPinRepository pins;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private AdminAccountRepository adminAccounts;

    /**
     * 超管身份（与 AdminPagesRenderSmokeTest 同款）。
     *
     * <p>⚠️ POST 还须 `.with(csrf())` —— `/admin/**` 那条过滤链**保留了 CSRF**
     * （只有 API 链 `csrf.disable()`）。少了它拿到的是 403，
     * 会被误读成「权限门没放行」，而实际权限完全正确。
     */
    private Authentication superAdminAuth() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pin-" + n + "@tailtopia.test", "顶置管理超管", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @BeforeEach
    void clearPins() {
        pins.deleteAll();
    }

    private ContentPost publicPost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
    }

    private ContentPost privatePost(long authorId, String text) {
        ContentPost p = ContentPost.publish(authorId, ContentType.GROWTH_MOMENT, null, text, List.of());
        p.setVisibility(ContentVisibility.PRIVATE);
        return posts.save(p);
    }

    /** 表单提交用的 WIB 墙上时间字符串（`yyyy-MM-ddTHH:mm`）。 */
    private static String wib(int day, int hour) {
        return String.format("2026-09-%02dT%02d:00", day, hour);
    }

    private static Instant wibInstant(int day, int hour) {
        return ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, WIB).toInstant();
    }

    // ——————————————————— 🛡 AC3 同坑位重叠 ———————————————————

    @Test
    void overlappingScheduleOnTheSameSlotIsRejectedThroughTheAdminPath() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "第一条");
        ContentPost b = publicPost(author.getId(), "第二条");

        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                        .param("slot", ContentPin.SLOT_HOME_FEED)
                        .param("objectType", "CONTENT")
                        .param("contentId", String.valueOf(a.getId()))
                        .param("startsAt", wib(1, 10))
                        .param("endsAt", wib(1, 12)))
                .andExpect(status().is3xxRedirection());
        assertThat(pins.findAll()).hasSize(1);

        // 10:00–12:00 与 11:00–13:00 重叠 → 必须被拦，且不落库。
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                        .param("slot", ContentPin.SLOT_HOME_FEED)
                        .param("objectType", "CONTENT")
                        .param("contentId", String.valueOf(b.getId()))
                        .param("startsAt", wib(1, 11))
                        .param("endsAt", wib(1, 13)))
                .andExpect(status().is3xxRedirection());
        assertThat(pins.findAll()).as("重叠的排期不得落库").hasSize(1);
    }

    /**
     * 🔴 **首尾相接不算重叠。** 判定必须用半开区间 —— 用闭区间会把
     * 「上一条 12:00 结束、下一条 12:00 开始」误判成冲突，而那是运营最常见的排法。
     */
    @Test
    void backToBackSchedulesAreAllowed() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "上半场");
        ContentPost b = publicPost(author.getId(), "下半场");

        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(a.getId()))
                .param("startsAt", wib(2, 10)).param("endsAt", wib(2, 12)));
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(b.getId()))
                .param("startsAt", wib(2, 12)).param("endsAt", wib(2, 14)));

        assertThat(pins.findAll()).as("12:00 结束与 12:00 开始不冲突").hasSize(2);
    }

    @Test
    void editingAScheduleExcludesItselfFromTheOverlapCheck() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "要改时间的那条");
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(a.getId()))
                .param("startsAt", wib(3, 10)).param("endsAt", wib(3, 12)));
        ContentPin saved = pins.findAll().get(0);

        // 把自己的时间窗往后挪一小时：与"自己"必然重叠，但不该被自己拦住。
        mvc.perform(post("/admin/content-pins/" + saved.getId() + "/edit").with(authentication(superAdminAuth())).with(csrf())
                        .param("startsAt", wib(3, 11)).param("endsAt", wib(3, 13)))
                .andExpect(status().is3xxRedirection());
        assertThat(pins.findById(saved.getId()).orElseThrow().getStartsAt())
                .as("编辑应生效").isEqualTo(wibInstant(3, 11));
    }

    // ——————————————————— AC6 提前结束 ———————————————————

    /** 🛡 提前结束写 `terminated_at`，**不覆盖 `ends_at`** —— 否则无从知道是排期到点还是被人提前收的。 */
    @Test
    void terminatingEarlyWritesTerminatedAtAndLeavesEndsAtIntact() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "提前收掉");
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(a.getId()))
                .param("startsAt", wib(4, 10)).param("endsAt", wib(4, 20)));
        ContentPin saved = pins.findAll().get(0);
        Instant originalEnd = saved.getEndsAt();

        mvc.perform(post("/admin/content-pins/" + saved.getId() + "/terminate").with(authentication(superAdminAuth())).with(csrf()))
                .andExpect(status().is3xxRedirection());

        ContentPin after = pins.findById(saved.getId()).orElseThrow();
        assertThat(after.getTerminatedAt()).as("应写 terminatedAt").isNotNull();
        assertThat(after.getEndsAt()).as("ends_at 不得被覆盖").isEqualTo(originalEnd);
    }

    // ——————————————————— 🛡 AC4 只允许公开内容 ———————————————————

    @Test
    void contentPickerReturnsOnlyPublicContent() throws Exception {
        User author = newUser();
        ContentPost open = publicPost(author.getId(), "公开的可顶置内容");
        ContentPost hidden = privatePost(author.getId(), "作者关了同步的私密日记");

        String html = mvc.perform(get("/admin/content-pins/pick").with(authentication(superAdminAuth()))
                        .param("q", "顶置"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("公开内容应出现").contains(String.valueOf(open.getId()));

        String all = mvc.perform(get("/admin/content-pins/pick").with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(all).as("🛡 私密 Diary 绝不可出现在可顶置候选里")
                .doesNotContain("作者关了同步的私密日记");
        assertThat(hidden.getVisibility()).isEqualTo(ContentVisibility.PRIVATE);
    }

    // ——————————————————— AC5 内容失效未生效 ———————————————————

    @Test
    void listMarksSchedulesWhoseContentBecameUndisplayable() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "待会儿要被删掉");
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(a.getId()))
                .param("startsAt", wib(5, 10)).param("endsAt", wib(5, 20)));

        a.softDelete();
        posts.save(a);

        String html = mvc.perform(get("/admin/content-pins").with(authentication(superAdminAuth())).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("内容已不可展示的排期须在列表里标注出来")
                .contains("内容失效未生效");
    }

    // ——————————————————— AC8 时区 ———————————————————

    /** 运营填的是 WIB 墙上时间，入库必须是对应的 UTC 绝对时刻。 */
    @Test
    void formTimesAreInterpretedAsWibAndStoredAsUtc() throws Exception {
        User author = newUser();
        ContentPost a = publicPost(author.getId(), "时区");
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                .param("contentId", String.valueOf(a.getId()))
                .param("startsAt", wib(6, 10)).param("endsAt", wib(6, 12)));

        ContentPin saved = pins.findAll().get(0);
        assertThat(saved.getStartsAt()).isEqualTo(wibInstant(6, 10));
        // WIB 10:00 = UTC 03:00
        assertThat(saved.getStartsAt()).isEqualTo(Instant.parse("2026-09-06T03:00:00Z"));
    }

    /** 🛡 界面必须在时间输入旁明示「WIB」—— 否则运营按本地时区填，排期整体偏移。 */
    @Test
    void pageLabelsTheTimezoneNextToTheTimeInputs() throws Exception {
        String html = mvc.perform(get("/admin/content-pins").with(authentication(superAdminAuth())).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("时间输入旁须有 WIB 字样").contains("WIB");
    }

    // ——————————————————— AC2 推广卡片 ———————————————————

    @Test
    void promoCardRequiresImageAndTitleAndKeepsContentIdNull() throws Exception {
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                        .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "PROMO")
                        .param("promoImageUrl", "https://cdn.example/promo.jpg")
                        .param("promoTitle", "夏日专场")
                        .param("startsAt", wib(7, 10)).param("endsAt", wib(7, 12)))
                .andExpect(status().is3xxRedirection());

        ContentPin saved = pins.findAll().get(0);
        assertThat(saved.getContentId()).as("推广卡片不对应真实帖子").isNull();
        assertThat(saved.getPromoTitle()).isEqualTo("夏日专场");

        // 缺图或缺标题 → 拦下，不落库。
        mvc.perform(post("/admin/content-pins").with(authentication(superAdminAuth())).with(csrf())
                .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "PROMO")
                .param("promoTitle", "只有标题")
                .param("startsAt", wib(8, 10)).param("endsAt", wib(8, 12)));
        assertThat(pins.findAll()).hasSize(1);
    }

    // ——————————————————— 🛡 AC9 双权限码 ———————————————————

    /** 只持查看权限 → 能看列表，但改不了。 */
    @Test
    void viewPermissionAloneCannotWrite() throws Exception {
        Authentication viewer = staffWith("content.pin_view");
        mvc.perform(get("/admin/content-pins").with(authentication(viewer)))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/content-pins").with(authentication(viewer)).with(csrf())
                        .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "PROMO")
                        .param("promoImageUrl", "https://cdn.example/x.jpg").param("promoTitle", "x")
                        .param("startsAt", wib(9, 10)).param("endsAt", wib(9, 12)))
                .andExpect(status().isForbidden());
        assertThat(pins.findAll()).as("无编辑权限不得落库").isEmpty();
    }

    /** 什么权限都没有 → 连列表都进不去。 */
    @Test
    void withoutAnyPinPermissionThePageIsForbidden() throws Exception {
        mvc.perform(get("/admin/content-pins").with(authentication(staffWith("content.view"))))
                .andExpect(status().isForbidden());
    }

    /**
     * 🛡 **侧栏可见性必须与入口门一致。**
     *
     * <p>两边走散时的表现最难查：权限放行了、敲 URL 能进，**但侧栏里没有这个链接** ——
     * 运营只会得出「我没有这个功能」，而日志、403、报错一概没有，无从下手。
     */
    @Test
    void navShowsPinsForViewOnlyStaff() throws Exception {
        String html = mvc.perform(get("/admin/content-pins").param("lang", "zh_CN")
                        .with(authentication(staffWith("content.pin_view"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("只持 content.pin_view 应能打开本页").isNotEmpty();
        assertThat(html).as("侧栏须有顶置管理入口，否则运营只会以为自己没有这个功能")
                .contains("/admin/content-pins");
    }

    /** STAFF 身份 + 指定权限码。⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。 */
    private Authentication staffWith(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pinstaff-" + n + "@tailtopia.test", "顶置权限测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        java.util.List<org.springframework.security.core.GrantedAuthority> auths =
                new java.util.ArrayList<>();
        auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }
}
