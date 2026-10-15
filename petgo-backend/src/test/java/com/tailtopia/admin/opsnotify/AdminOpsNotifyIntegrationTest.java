package com.tailtopia.admin.opsnotify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1（需 Postgres + Redis）：后台「运营通知」发送页（V1.3.4 AB-31）。
 *
 * <p>🔴 <b>绝不以「全部用户 + 立即发送」向共享库真发</b>：真正发送一律「指定 ID + 本类现造的测试账号」；
 * 全部 / 按条件只用于预估人数，或配「未来定时 + 取消」。扫描在测试上下文里不自动跑
 * （{@code petgo.ops-notify.scan-initial-delay-ms}），立即发送走提交后的异步事件 —— 断言前轮询终态。
 * 每例 {@code @AfterEach} 把本类建的、仍 PENDING 的活动置 CANCELED，免得别的测试调扫描时把它们发出去。
 *
 * <p>🔴 <b>可重跑（共享库）</b>：不写死任何真实用户 id（含 422 / 403 用例，一律用本类现造账号）；按注册日期预估的用例
 * 用 1900 年代的随机日期并在 {@code @AfterEach} 把改过的 {@code created_at} 复原。
 */
class AdminOpsNotifyIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/admin/ops-notifications";
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;

    /** 本类建的活动 token：@AfterEach 兜底取消。 */
    private final List<String> created = new ArrayList<>();
    /** 改过 created_at 的测试用户：@AfterEach 复原成 now()，下次跑同一随机日期不会多算人。 */
    private final List<Long> backdated = new ArrayList<>();

    @AfterEach
    void cancelLeftoverPending() {
        for (String t : created) {
            jdbc.update("UPDATE ops_notification_campaigns SET status = 'CANCELED', canceled_at = now(), "
                    + "updated_at = now() WHERE public_token = ? AND status = 'PENDING'", t);
        }
        created.clear();
        for (Long id : backdated) {
            jdbc.update("UPDATE users SET created_at = now() WHERE id = ?", id);
        }
        backdated.clear();
    }

    /** 1900 年代里随机一天：真实用户不可能在这天注册，跨次重跑撞日概率可忽略。 */
    private static LocalDate randomOldDay() {
        return LocalDate.of(1900, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 365 * 40));
    }

    /** 把测试用户的注册时间改到某个 WIB 自然日中午（@AfterEach 复原）。 */
    private void backdate(User u, LocalDate wibDay) {
        jdbc.update("UPDATE users SET created_at = ?::timestamptz WHERE id = ?", wibDay + "T05:00:00Z", u.getId());
        backdated.add(u.getId());
    }

    /** STAFF principal 显式声明（不让 hasRole('SUPER_ADMIN') 的隐式全权把权限断言变成恒真）。 */
    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "opsn-" + n + "@tailtopia.test", "运营通知测试" + n, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication editor() {
        return staffWith(AdminPermissions.CONFIG_VIEW, AdminPermissions.CONFIG_EDIT);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, String target) {
        return b.header("HX-Request", "true").header("HX-Target", target);
    }

    /**
     * 合法表单（指定 ID 模式，ID 由调用方给）；{@code overrides} 成对给出「字段, 值」。
     * ⚠️ 不能在外面再 {@code .param(同名)}：MockMvc 是追加不是覆盖。
     */
    private static MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder b, String title,
            String... overrides) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("titleId", title);
        f.put("titleEn", "Weekend promo");
        f.put("bodyId", "Isi promo");
        f.put("bodyEn", "Promo body");
        f.put("audienceMode", "IDS");
        f.put("userIds", "");
        f.put("targetType", "APP_PAGE");
        f.put("targetValue", "TOKO");
        f.put("scheduledAt", "");
        // 预估闸：默认带 1（测试里真正落库的人群都是现造的 1~2 人，在容差内）；一次性提交键每次新的
        f.put("previewCount", "1");
        f.put("submitKey", UUID.randomUUID().toString());
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            f.put(overrides[i], overrides[i + 1]);
        }
        f.forEach(b::param);
        return b;
    }

    private Map<String, Object> rowByTitle(String title) {
        Map<String, Object> r = jdbc.queryForMap("SELECT id, public_token, status, scheduled_at, recipient_count, "
                + "skipped_count, created_by FROM ops_notification_campaigns WHERE title_id = ?", title);
        created.add((String) r.get("public_token"));
        return r;
    }

    private long auditCount(String action, String token) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = ? AND target_id = ?",
                Long.class, action, token);
    }

    private String awaitFinalStatus(String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        String s = null;
        while (System.currentTimeMillis() < deadline) {
            s = jdbc.queryForObject("SELECT status FROM ops_notification_campaigns WHERE public_token = ?",
                    String.class, token);
            if ("DONE".equals(s) || "PARTIAL_FAILED".equals(s)) {
                return s;
            }
            Thread.sleep(100);
        }
        return s;
    }

    private static String tomorrowWib() {
        return LocalDateTime.now(ZoneId.of("Asia/Jakarta")).plusDays(1).withHour(10).withMinute(0).format(INPUT);
    }

    @Test
    @DisplayName("AC1 指定 2 个账号立即发送：预估 2 人 → 发送 → 两人通知中心各一条，列表「已完成」送达 2；审计 CREATED")
    void idsImmediateSendReachesBothUsers() throws Exception {
        Authentication ed = editor();
        User a = newUser();
        User b = newUser();
        String ids = a.getId() + ", " + b.getId() + "\n" + a.getId() + " 999999999999"; // 含重复与无效 ID
        String title = "opsn-now-" + SEQ.incrementAndGet();

        // 新建抽屉：复用列表 mapping（?create=1）；共享跳转片段在场；发送钮初始禁用且挂预估门槛
        String drawer = mvc.perform(get(BASE).param("create", "1").header("HX-Request", "true")
                        .with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(drawer).doesNotContain("<html").contains("hx-post=\"" + BASE + "\"")
                .contains("data-preview-gate").contains("data-preview-form").contains("data-confirm=")
                .contains("name=\"previewCount\"").containsPattern("name=\"submitKey\" value=\"[0-9a-f-]{36}\"")
                .contains("hx-sync=\"this:drop\"").contains("hx-sync=\"this:replace\"")
                // 确认框模板里是字面占位，由 admin-core.js 填 N / 定时时间（不能被服务端 MessageFormat 吃掉）
                .containsPattern("data-confirm=\"[^\"]*\\{0\\}")
                .containsPattern("data-confirm-scheduled=\"[^\"]*\\{1\\}[^\"]*\\{0\\}|data-confirm-scheduled=\"[^\"]*\\{0\\}[^\"]*\\{1\\}")
                .contains("id=\"f-opsn-ttype\"").contains("data-show-when=\"targetType:PRODUCT\"")
                .containsPattern("data-show-when=\"audienceMode:IDS\"[^>]*hidden")
                .containsPattern("<textarea[^>]*disabled[^>]*name=\"userIds\"|<textarea[^>]*name=\"userIds\"[^>]*disabled");

        // 预估：去重后有效 2 人（无效 ID 不计）
        String preview = mvc.perform(htmx(form(post(BASE + "/preview"), title, "userIds", ids), "ops-notify-preview")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("data-preview-count=\"2\"");

        MvcResult res = mvc.perform(htmx(form(post(BASE), title, "userIds", ids, "previewCount", "2"),
                        "ops-notify-form-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getHeader("HX-Trigger")).contains("admin:drawer-close");
        assertThat(res.getResponse().getContentAsString()).contains("hx-swap-oob").contains("id=\"ops-notify-rows\"")
                .contains(title);

        String token = (String) rowByTitle(title).get("public_token");
        assertThat(awaitFinalStatus(token)).isEqualTo("DONE");
        Map<String, Object> row = rowByTitle(title);
        assertThat(row.get("recipient_count")).isEqualTo(2);
        assertThat(row.get("skipped_count")).as("无效 ID 跳过并计数").isEqualTo(1);
        for (User u : List.of(a, b)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'OPS_ANNOUNCEMENT' "
                    + "AND target_ref = ? AND recipient_user_id = ?", Long.class, token, u.getId())).isEqualTo(1);
        }
        assertThat(auditCount("OPS_NOTIFY_CREATED", token)).isEqualTo(1);

        // 列表：「已完成」+ 送达 2；URL 只有 token，不外露自增 id
        String list = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ops-notify-row-" + token).contains("已完成")
                .doesNotContain(BASE + "/" + row.get("id") + "/");
        assertThat(list).as("非待发送不给取消").doesNotContain(BASE + "/" + token + "/cancel");

        // 只读详情：IDS 显示指定个数（子表条数，含无效 ID，去重后 3 个）
        String detail = mvc.perform(get(BASE).param("open", token).param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("<html").contains(title).contains("3 个").doesNotContain("<form");
    }

    @Test
    @DisplayName("AC2 定时到明天 → 列表显示定时时间 → 取消 → 已取消，审计 CANCELED；再取消 409")
    void scheduledThenCancel() throws Exception {
        Authentication ed = editor();
        User a = newUser();
        String title = "opsn-sched-" + SEQ.incrementAndGet();
        String at = tomorrowWib();
        mvc.perform(htmx(form(post(BASE), title, "userIds", String.valueOf(a.getId()), "scheduledAt", at),
                        "ops-notify-form-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        Map<String, Object> row = rowByTitle(title);
        String token = (String) row.get("public_token");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("scheduled_at")).isNotNull();

        String list = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("待发送").contains(at.replace('T', ' '))
                .contains(BASE + "/" + token + "/cancel").contains("hx-target=\"#ops-notify-row-error\"");

        MvcResult cancel = mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "ops-notify-row-error")
                        .param("page", "0").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(cancel.getResponse().getContentAsString()).contains("hx-swap-oob").contains("id=\"ops-notify-rows\"");
        assertThat(cancel.getResponse().getHeader("HX-Trigger")).as("行内取消不关抽屉").isNull();
        assertThat(rowByTitle(title).get("status")).isEqualTo("CANCELED");
        assertThat(auditCount("OPS_NOTIFY_CANCELED", token)).isEqualTo(1);

        MvcResult again = mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "ops-notify-row-error")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isConflict()).andReturn();
        assertThat(again.getResponse().getHeader("HX-Retarget")).isEqualTo("#ops-notify-row-error");
        assertThat(again.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.cancel.not-pending\"");
        assertThat(auditCount("OPS_NOTIFY_CANCELED", token)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE target_ref = ?", Long.class, token))
                .isZero();

        mvc.perform(htmx(post(BASE + "/nosuchtoken/cancel"), "ops-notify-row-error")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("422 落回抽屉错误槽（带码供标红）：缺文案 / 超长 / 定时已过 / ID 空 / 非数字 / 区间倒置 / 外链非法；均不落库")
    void validationErrorsAreInline() throws Exception {
        Authentication ed = editor();
        String title = "opsn-422-" + SEQ.incrementAndGet();
        // 万一某条校验回归放行：也只会发给本类现造账号、且是明天的定时（@AfterEach 取消），绝不碰真实用户
        String me = String.valueOf(newUser().getId());
        String later = tomorrowWib();
        Object[][] cases = {
                {new String[] {"titleEn", "", "userIds", me, "scheduledAt", later}, "admin.err.ops-notify.text.blank"},
                {new String[] {"bodyId", "x".repeat(256), "userIds", me, "scheduledAt", later},
                        "admin.err.ops-notify.text.too-long"},
                {new String[] {"scheduledAt", "2020-01-01T00:00", "userIds", me}, "admin.err.ops-notify.schedule.past"},
                {new String[] {"userIds", "", "scheduledAt", later}, "admin.err.ops-notify.audience.ids-empty"},
                {new String[] {"userIds", "12,abc", "scheduledAt", later},
                        "admin.err.ops-notify.audience.ids-not-numeric"},
                {new String[] {"audienceMode", "FILTER", "registeredFrom", "2026-10-12", "registeredTo", "2026-10-01",
                        "scheduledAt", later}, "admin.err.ops-notify.audience.invalid"},
                {new String[] {"userIds", me, "scheduledAt", later, "targetType", "EXTERNAL",
                        "targetValue", "ftp://x.example/a"}, "admin.err.opsPopup.targetExternal"},
        };
        for (Object[] c : cases) {
            MvcResult res = mvc.perform(htmx(form(post(BASE), title, (String[]) c[0]), "ops-notify-form-err")
                            .param("lang", "zh_CN").with(authentication(ed)).with(csrf()))
                    .andExpect(status().isUnprocessableEntity()).andReturn();
            assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#ops-notify-form-err");
            String body = res.getResponse().getContentAsString();
            assertThat(body).as((String) c[1]).contains("data-code=\"" + c[1] + "\"").doesNotContain("hx-swap-oob")
                    .doesNotContain("??");
        }
        // 50,001 个 ID → 新码
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 50_001; i++) {
            many.append(i).append(',');
        }
        mvc.perform(htmx(form(post(BASE), title, "userIds", many.toString(), "scheduledAt", later),
                        "ops-notify-form-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_notification_campaigns WHERE title_id = ?",
                Integer.class, title)).isZero();
    }

    @Test
    @DisplayName("预估人数：按条件实时计数（造的人在内）；条件过严 0 人 → 片段标 0；条件非法 422 落回预估槽（可重跑）")
    void previewCounts() throws Exception {
        Authentication ed = editor();
        User u = newUser();
        LocalDate day = randomOldDay();
        LocalDate emptyDay = day.minusDays(1); // 只改了 u 一人到 day，前一天必为 0
        backdate(u, day);

        String one = mvc.perform(htmx(form(post(BASE + "/preview"), "p", "audienceMode", "FILTER",
                                "registeredFrom", day.toString(), "registeredTo", day.toString()), "ops-notify-preview")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(one).contains("data-preview-count=\"1\"");

        String zero = mvc.perform(htmx(form(post(BASE + "/preview"), "p", "audienceMode", "FILTER",
                                "registeredFrom", emptyDay.toString(), "registeredTo", emptyDay.toString()),
                        "ops-notify-preview")
                        .param("lang", "zh_CN").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(zero).contains("data-preview-count=\"0\"").contains("无法发送");

        // 全部用户只预估、不发送
        String all = mvc.perform(htmx(form(post(BASE + "/preview"), "p", "audienceMode", "ALL"), "ops-notify-preview")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(all).containsPattern("data-preview-count=\"[1-9]\\d*\"");

        MvcResult bad = mvc.perform(htmx(form(post(BASE + "/preview"), "p", "userIds", "x"), "ops-notify-preview")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(bad.getResponse().getHeader("HX-Retarget")).isEqualTo("#ops-notify-preview");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_notification_campaigns WHERE title_id = 'p'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("🔒 只读（仅 config.view）：能看记录与详情，无新建 / 取消入口；预估 / 新建 / 取消 POST 一律 403")
    void readOnlyAccountCannotWrite() throws Exception {
        Authentication ed = editor();
        User a = newUser();
        String title = "opsn-ro-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), title, "userIds", String.valueOf(a.getId()), "scheduledAt", tomorrowWib())
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String token = (String) rowByTitle(title).get("public_token");

        Authentication viewer = staffWith(AdminPermissions.CONFIG_VIEW);
        String page = mvc.perform(get(BASE).with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(title).doesNotContain("ops-notifications?create=1\"")
                .doesNotContain("/cancel\"");
        mvc.perform(get(BASE).param("open", token).header("HX-Request", "true").with(authentication(viewer)))
                .andExpect(status().isOk());

        String aId = String.valueOf(a.getId());
        mvc.perform(form(post(BASE), title + "x", "userIds", aId, "scheduledAt", tomorrowWib())
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(form(post(BASE + "/preview"), "p", "userIds", aId), "ops-notify-preview")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "ops-notify-row-error")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByTitle(title).get("status")).isEqualTo("PENDING");

        // 没有 config.* 的账号连页面都进不去
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("预估 · 指定 ID：停用（status 非 ACTIVE）与已注销的账号不计入")
    void previewIdsExcludesInactiveAndDeleted() throws Exception {
        Authentication ed = editor();
        User ok = newUser();
        User banned = newUser();
        User deleted = newUser();
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());
        String ids = ok.getId() + "," + banned.getId() + "," + deleted.getId();

        String body = mvc.perform(htmx(form(post(BASE + "/preview"), "p", "userIds", ids), "ops-notify-preview")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("data-preview-count=\"1\"");
    }

    @Test
    @DisplayName("按条件 + 未来定时：建成后只读详情渲染条件块（open=<token>）→ 取消")
    void filterCampaignDetailRenders() throws Exception {
        Authentication ed = editor();
        User u = newUser();
        LocalDate day = randomOldDay();
        backdate(u, day);
        String title = "opsn-filter-" + SEQ.incrementAndGet();
        mvc.perform(htmx(form(post(BASE), title, "audienceMode", "FILTER", "userIds", "",
                                "registeredFrom", day.toString(), "registeredTo", day.toString(), "hasPet", "",
                                "scheduledAt", tomorrowWib()), "ops-notify-form-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        String token = (String) rowByTitle(title).get("public_token");

        String detail = mvc.perform(get(BASE).param("open", token).param("lang", "zh_CN")
                        .header("HX-Request", "true").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("<html").contains(title).contains("按条件")
                // 日期按界面语言格式化（zh_CN = yyyy/M/d），只钉年份落在区间两端
                .containsPattern("<dd>" + day.getYear() + "[^<]*→[^<]*" + day.getYear() + "[^<]*</dd>")
                .doesNotContain("??");

        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "ops-notify-row-error")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(rowByTitle(title).get("status")).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("一次性 submitKey：校验失败不锁（改完同一键可再提交）；成功后同一键再提交 → 422 duplicate-submit，只建一条")
    void duplicateSubmitKeyRejected() throws Exception {
        Authentication ed = editor();
        String uid = String.valueOf(newUser().getId());
        String title = "opsn-dup-" + SEQ.incrementAndGet();
        String key = UUID.randomUUID().toString();
        String at = tomorrowWib();

        mvc.perform(htmx(form(post(BASE), title, "userIds", uid, "scheduledAt", at, "submitKey", key, "titleEn", ""),
                        "ops-notify-form-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), title, "userIds", uid, "scheduledAt", at, "submitKey", key),
                        "ops-notify-form-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        MvcResult again = mvc.perform(htmx(form(post(BASE), title, "userIds", uid, "scheduledAt", at,
                        "submitKey", key), "ops-notify-form-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(again.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.duplicate-submit\"");
        rowByTitle(title); // queryForMap：恰好一条，否则抛错
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_notification_campaigns WHERE title_id = ?",
                Integer.class, title)).isEqualTo(1);

        // 缺键同样 422
        mvc.perform(htmx(form(post(BASE), title + "-nokey", "userIds", uid, "scheduledAt", at, "submitKey", ""),
                        "ops-notify-form-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("服务端预估闸：缺 previewCount → preview-required；实际 0 人 → audience-empty；实际远超预估 → preview-stale；均不落库")
    void previewGateEnforcedServerSide() throws Exception {
        Authentication ed = editor();
        String title = "opsn-gate-" + SEQ.incrementAndGet();
        String at = tomorrowWib();
        String uid = String.valueOf(newUser().getId());
        StringBuilder twelve = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            twelve.append(newUser().getId()).append(',');
        }
        Object[][] cases = {
                {new String[] {"userIds", uid, "previewCount", ""}, "admin.err.ops-notify.preview-required"},
                {new String[] {"userIds", "999999999999"}, "admin.err.ops-notify.audience-empty"},
                // 预估 1 人、实际 12 人：超出 max(10, 10%) → 须重新预估
                {new String[] {"userIds", twelve.toString(), "previewCount", "1"}, "admin.err.ops-notify.preview-stale"},
        };
        for (Object[] c : cases) {
            String[] o = (String[]) c[0];
            String[] all = java.util.Arrays.copyOf(o, o.length + 2);
            all[o.length] = "scheduledAt";
            all[o.length + 1] = at;
            String body = mvc.perform(htmx(form(post(BASE), title, all), "ops-notify-form-err")
                            .param("lang", "zh_CN").with(authentication(ed)).with(csrf()))
                    .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
            assertThat(body).as((String) c[1]).contains("data-code=\"" + c[1] + "\"").doesNotContain("??");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_notification_campaigns WHERE title_id = ?",
                Integer.class, title)).isZero();
    }
}
