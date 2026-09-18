package com.tailtopia.admin.pin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1（V1.3.0 Story 7.3 · B3 顶置管理套模板 B，需 Docker postgres+redis）：
 * 列表模板 B 壳 + 摘要条三格 + 状态筛选 + 行开抽屉、行内无处置表单；新建表单进抽屉（端点与参数不变）；
 * 抽屉里改时间 / 提前结束的 htmx 分支（抽屉 oob + toast + 列表刷新事件）；重叠冲突 422 且**报错里带冲突项**；
 * 已结束排期只读；只持 {@code content.pin_view} 拿不到写入口。
 */
class AdminContentPinDrawerIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentPinRepository pins;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pin3-" + n + "@tailtopia.test", "顶置超管 " + n, "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null,
                new java.util.ArrayList<>(principal.getAuthorities()));
    }

    private Authentication viewerOnly() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "pin3v-" + n + "@tailtopia.test", "顶置只读 " + n, "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        // ⚠️ ROLE_ADMIN 不能省：/admin/** 在 URL 层就要求它。少了它，列表会在方法门控之前 403，
        //    而后面「new/drawer 应 403」那条也会因为同一个原因假绿 —— @PreAuthorize(MANAGE) 根本没被验到。
        return new TestingAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("content.pin_view")));
    }

    /**
     * 🔴 每个用例前清空排期：测试库是**共享、不回滚、堆着历史数据**的，
     * 而本页所有断言都建立在「这个坑位现在有哪些排期」上 —— 上一轮跑剩的排期会直接撞重叠校验。
     * 兄弟类 {@code AdminContentPinIntegrationTest} 同样这么做。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearPins() {
        pins.deleteAll();
    }

    private ContentPost publicPost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text,
                List.of("https://cdn/pin-" + SEQ.incrementAndGet() + ".jpg")));
    }

    /** 相对今天的 WIB 墙上时间（避免跨天与既有数据打架）。 */
    private static String wib(int plusDays, int hour) {
        return LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).plusDays(plusDays)
                + String.format("T%02d:00", hour);
    }

    /**
     * 造一条排期并返回它的 id。
     *
     * <p>⚠️ 不要用「取最大 id」认领结果：整页路径把重叠 / 校验失败**吞成 flash + 302**，
     * 断言 302 照样通过，于是拿回来的可能是别人的 pin，后面的断言全指向错误对象。
     * 这里按 contentId 精确捞，捞不到就当场红。
     */
    private long createPin(Authentication auth, long contentId, int day, int fromHour, int toHour) throws Exception {
        mvc.perform(post("/admin/content-pins").with(authentication(auth)).with(csrf())
                        .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                        .param("contentId", String.valueOf(contentId))
                        .param("startsAt", wib(day, fromHour)).param("endsAt", wib(day, toHour)))
                .andExpect(status().is3xxRedirection());
        return pins.findAll().stream()
                .filter(p -> java.lang.Long.valueOf(contentId).equals(p.getContentId()))
                .mapToLong(ContentPin::getId).max()
                .orElseThrow(() -> new AssertionError("排期没落库：多半是撞了重叠校验（整页路径把它吞成 flash 了）"));
    }

    /** AC1 / AC2：模板 B 壳 + 摘要条 + 行开抽屉；行内不再有改时间 / 提前结束表单；「＋新建」是抽屉入口。 */
    @Test
    void listIsTemplateBWithSummaryAndDrawerRows() throws Exception {
        Authentication admin = superAdmin();
        User author = newUser();
        long pinId = createPin(admin, publicPost(author.getId(), "B3 列表").getId(), 200, 10, 12);

        String html = mvc.perform(get("/admin/content-pins").param("lang", "zh_CN").with(authentication(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-list").contains("id=\"pins-summary\"").contains("id=\"pins-rows\"")
                .contains("id=\"pin-drawer\"")
                .contains("name=\"slot\"").contains("name=\"status\"")
                .contains("data-drawer-url=\"/admin/content-pins/" + pinId + "/drawer\"")
                .contains("id=\"pin-row-" + pinId + "\"")
                .contains("data-drawer-open=\"/admin/content-pins/new/drawer?slot=" + ContentPin.SLOT_HOME_FEED + "\"")
                // 行内处置表单与页尾常驻新建表单都已收进抽屉
                .doesNotContain("/terminate\"").doesNotContain("id=\"pinCreateForm\"")
                // 🔴 时间旁的 WIB 字样不能丢
                .contains("WIB");

        // 状态筛选：待生效的这条在 ACTIVE 档里不该出现
        String activeOnly = mvc.perform(get("/admin/content-pins").param("status", "ACTIVE")
                        .with(authentication(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(activeOnly).doesNotContain("id=\"pin-row-" + pinId + "\"")
                .contains("id=\"pins-summary\" hx-swap-oob=\"true\"")
                .doesNotContain("data-list");
    }

    /** AC3 / AC5：抽屉预览 + 时间设置 + 操作条；改时间 / 提前结束走原端点 htmx 分支。 */
    @Test
    void drawerShowsPreviewAndDispositionsKeepEndpointsUnchanged() throws Exception {
        Authentication admin = superAdmin();
        User author = newUser();
        long pinId = createPin(admin, publicPost(author.getId(), "B3 抽屉预览正文").getId(), 201, 10, 12);

        String drawer = mvc.perform(get("/admin/content-pins/" + pinId + "/drawer").param("lang", "zh_CN")
                        .with(authentication(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).contains("data-pin-id=\"" + pinId + "\"")
                .contains("data-section=\"pin-preview\"").contains("B3 抽屉预览正文")
                .contains(author.getNickname())
                .contains("data-section=\"pin-window\"")
                .contains("hx-post=\"/admin/content-pins/" + pinId + "/edit\"")
                .contains("hx-post=\"/admin/content-pins/" + pinId + "/terminate\"")
                .contains("id=\"pin-drawer-err\"").contains("WIB");
        mvc.perform(get("/admin/content-pins/" + pinId + "/drawer").with(authentication(admin)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/content-pins?open=" + pinId));

        // 改时间（htmx）：参数名不变
        String edited = mvc.perform(post("/admin/content-pins/" + pinId + "/edit")
                        .with(authentication(admin)).with(csrf()).header("HX-Request", "true")
                        .param("startsAt", wib(201, 14)).param("endsAt", wib(201, 16)).param("lang", "zh_CN"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:pin-list-refresh")))
                .andReturn().getResponse().getContentAsString();
        assertThat(edited).contains("hx-swap-oob=\"innerHTML:#pin-drawer .drawer-body\"").contains("class=\"toast\"");
        assertThat(pins.findById(pinId).orElseThrow().getStartsAt())
                .isEqualTo(java.time.LocalDateTime.parse(wib(201, 14))
                        .atZone(java.time.ZoneId.of("Asia/Jakarta")).toInstant());

        // 提前结束（htmx）→ 抽屉切只读
        String ended = mvc.perform(post("/admin/content-pins/" + pinId + "/terminate").param("lang", "zh_CN")
                        .with(authentication(admin)).with(csrf()).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ended).contains("class=\"toast\"");
        assertThat(pins.findById(pinId).orElseThrow().getTerminatedAt()).isNotNull();
        String readonly = mvc.perform(get("/admin/content-pins/" + pinId + "/drawer").param("lang", "zh_CN")
                        .with(authentication(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(readonly).as("🔴 已结束的排期只读").doesNotContain("/edit\"").doesNotContain("/terminate\"")
                .contains("已结束的排期只读");
    }

    /** AC4 / AC6：新建走抽屉表单（端点参数不变）；重叠冲突 422，且报错里带得出冲突项。 */
    @Test
    void createFromDrawerAndOverlapShowsTheConflictingSchedule() throws Exception {
        Authentication admin = superAdmin();
        User author = newUser();
        long a = publicPost(author.getId(), "B3 新建 A").getId();
        long b = publicPost(author.getId(), "B3 新建 B").getId();

        String form = mvc.perform(get("/admin/content-pins/new/drawer").param("lang", "zh_CN")
                        .with(authentication(admin)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).contains("id=\"pinCreateForm\"").contains("id=\"pinObjectType\"")
                .contains("id=\"pinCandidates\"").contains("name=\"contentId\"")
                .contains("name=\"promoTitle\"").contains("name=\"promoImageFile\"")
                .contains("name=\"startsAt\"").contains("name=\"endsAt\"").contains("WIB");

        // htmx 新建成功 → 抽屉切到新排期 + toast + 列表刷新事件
        mvc.perform(post("/admin/content-pins").with(authentication(admin)).with(csrf())
                        .header("HX-Request", "true").param("lang", "zh_CN")
                        .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                        .param("contentId", String.valueOf(a))
                        .param("startsAt", wib(202, 10)).param("endsAt", wib(202, 12)))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("admin:pin-list-refresh")));
        long created = pins.findAll().stream()
                .filter(p -> java.lang.Long.valueOf(a).equals(p.getContentId()))
                .mapToLong(ContentPin::getId).max().orElseThrow();

        // 🔴 重叠 → 422 行内 err，且报错文案里要能看出跟哪一条撞了（id + 起止），否则运营得回列表自己比时间窗
        String err = mvc.perform(post("/admin/content-pins").with(authentication(admin)).with(csrf())
                        .header("HX-Request", "true").param("lang", "zh_CN")
                        .param("slot", ContentPin.SLOT_HOME_FEED).param("objectType", "CONTENT")
                        .param("contentId", String.valueOf(b))
                        .param("startsAt", wib(202, 11)).param("endsAt", wib(202, 13)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(err).contains("#" + created).contains("10:00").contains("12:00");
        assertThat(pins.findAll()).as("重叠的排期不得落库").hasSize(1);
    }

    /** 🛡 双权限码：只持 content.pin_view 能看列表与抽屉，但拿不到写入口，新建表单端点 403。 */
    @Test
    void viewOnlyCannotReachTheWriteForms() throws Exception {
        Authentication admin = superAdmin();
        long pinId = createPin(admin, publicPost(newUser().getId(), "B3 门控").getId(), 203, 10, 12);
        Authentication viewer = viewerOnly();

        String html = mvc.perform(get("/admin/content-pins").param("lang", "zh_CN").with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"pin-row-" + pinId + "\"").doesNotContain("data-drawer-open=");

        String drawer = mvc.perform(get("/admin/content-pins/" + pinId + "/drawer")
                        .with(authentication(viewer)).header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("/edit\"").doesNotContain("/terminate\"");

        mvc.perform(get("/admin/content-pins/new/drawer").with(authentication(viewer)).header("HX-Request", "true"))
                .andExpect(status().isForbidden());
    }
}
