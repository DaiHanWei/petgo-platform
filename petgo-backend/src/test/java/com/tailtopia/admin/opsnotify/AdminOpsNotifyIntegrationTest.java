package com.tailtopia.admin.opsnotify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * L1（需 Postgres + Redis）：后台「系统通知发送」（V1.3.4 AB-31；后台返工清单 §4 · UI 稿 I1–I11）。
 *
 * <p>🔴 <b>绝不以「全部用户 + 立即发送」向共享库真发</b>：真正发送一律「指定 ID + 本类现造的测试账号」；
 * 全部 / 按条件只用于预估人数，或配「未来定时 + 立刻取消」。扫描在测试上下文里不自动跑
 * （{@code petgo.ops-notify.scan-initial-delay-ms}），立即发送走提交后的异步事件 —— 断言前轮询终态。
 * 每例 {@code @AfterEach} 把本类建的、仍 PENDING 的活动置 CANCELED，并复原改过的 created_at。
 *
 * <p>🔴 <b>可重跑（共享库）</b>：不写死任何真实用户 id；按条件的断言只比「同一条件下的人数差」，不比绝对数。
 */
class AdminOpsNotifyIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/admin/ops-notifications";
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Environment env;

    private final List<String> createdTitles = new ArrayList<>();
    private final List<Long> backdated = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String t : createdTitles) {
            jdbc.update("UPDATE ops_notification_campaigns SET status = 'CANCELED', canceled_at = now(), "
                    + "updated_at = now() WHERE title_id = ? AND status = 'PENDING'", t);
        }
        createdTitles.clear();
        for (Long id : backdated) {
            jdbc.update("UPDATE users SET created_at = now() WHERE id = ?", id);
        }
        backdated.clear();
    }

    /** STAFF principal 显式声明（不让 hasRole('SUPER_ADMIN') 的隐式全权把权限断言变成恒真）。 */
    private Authentication staffWith(String... codes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "opsn-" + n + "@tailtopia.test", "系统通知测试" + n, "{bcrypt}x"));
        AdminUserDetails p = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(codes));
        return new TestingAuthenticationToken(p, null, new ArrayList<>(p.getAuthorities()));
    }

    private Authentication sender() {
        return staffWith(AdminPermissions.OPS_NOTIFY_VIEW, AdminPermissions.OPS_NOTIFY_SEND);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, String target) {
        return b.header("HX-Request", "true").header("HX-Target", target).param("lang", "zh_CN");
    }

    /**
     * 合法表单（指定 ID 模式、立即发送）；{@code overrides} 成对给出「字段, 值」。
     * ⚠️ 不能在外面再 {@code .param(同名)}：MockMvc 是追加不是覆盖。
     */
    private MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder b, String title, String... overrides) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("titleId", title);
        f.put("titleEn", "Weekend promo");
        f.put("bodyId", "Isi promo");
        f.put("bodyEn", "Promo body");
        f.put("targetType", "APP_PAGE");
        f.put("targetValue", "TOKO");
        f.put("audienceMode", "IDS");
        f.put("userIds", "");
        f.put("idsSource", "PASTE");
        f.put("sendMode", "NOW");
        f.put("previewCount", "1");
        f.put("submitKey", UUID.randomUUID().toString());
        for (int i = 0; i + 1 < overrides.length; i += 2) {
            f.put(overrides[i], overrides[i + 1]);
        }
        f.forEach(b::param);
        if (title != null && !title.isEmpty()) {
            createdTitles.add(title);
        }
        return b;
    }

    private Map<String, Object> rowByTitle(String title) {
        return jdbc.queryForMap("SELECT id, public_token, status, scheduled_at, recipient_count, skipped_count, "
                + "estimated_count, planned_count, ids_source FROM ops_notification_campaigns WHERE title_id = ?",
                title);
    }

    private int rowsWithTitle(String title) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ops_notification_campaigns WHERE title_id = ?",
                Integer.class, title);
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

    private static long dataCount(String body, String attr) {
        Matcher m = Pattern.compile(attr + "=\"(\\d+)\"").matcher(body);
        assertThat(m.find()).as(attr + " in " + body).isTrue();
        return Long.parseLong(m.group(1));
    }

    private long estimate(Authentication a, String... overrides) throws Exception {
        String body = mvc.perform(htmx(form(post(BASE + "/preview"), "", overrides), "opsn-estimate")
                        .with(authentication(a)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return dataCount(body, "data-count");
    }

    // ---------- 新建页 ----------

    @Test
    @DisplayName("I3：新建页是独立整页（四步步骤条 + 吸底操作条 + 一次性提交键），不再有抽屉；跳转类型无默认")
    void newPageRenders() throws Exception {
        String page = mvc.perform(get(BASE + "/new").param("lang", "zh_CN").with(authentication(sender())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<html").contains("新建系统通知").contains("① 内容").contains("② 选择用户")
                .contains("③ 发送时间").contains("④ 确认").contains("hx-post=\"" + BASE + "\"")
                .contains("hx-post=\"" + BASE + "/review\"").contains("hx-post=\"" + BASE + "/preview\"")
                .contains("hx-post=\"" + BASE + "/ids/check\"").contains("delay:300ms")
                .containsPattern("name=\"submitKey\" value=\"[0-9a-f-]{36}\"")
                .contains("请选择跳转类型").contains("现在是 WIB").contains("上传 CSV")
                .contains("name=\"registeredWithin\"").contains("name=\"species\"")
                .doesNotContain("data-drawer").doesNotContain("??");
        assertThat(page).as("物种单选下拉，不再是多选复选框").doesNotContain("type=\"checkbox\" name=\"species\"");
    }

    @Test
    @DisplayName("I4–I7 · 指定 2 个现造账号立即发送：ID 校验 → 确认页 → 确认发送 → HX-Redirect + toast；两人各收一条，计数列落库")
    void idsImmediateSendReachesBothUsers() throws Exception {
        Authentication ed = sender();
        User a = newUser();
        User b = newUser();
        String ids = a.getId() + "\n" + b.getId() + "\n" + a.getId() + "\nabc\n999999999999";
        String title = "opsn-now-" + SEQ.incrementAndGet();

        String check = mvc.perform(htmx(post(BASE + "/ids/check").param("userIds", ids), "opsn-ids-result")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(check).contains("data-valid=\"2\"").contains("有效 2 人，跳过 2 个（不存在 2）").contains("导出跳过清单");

        MvcResult review = mvc.perform(htmx(form(post(BASE + "/review"), title, "userIds", ids), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(review.getResponse().getHeader("HX-Trigger-After-Swap")).isEqualTo("opsn:reviewed");
        assertThat(review.getResponse().getContentAsString()).contains("id=\"opsn-review\" hx-swap-oob=\"innerHTML\"")
                .contains("name=\"previewCount\" value=\"2\"").contains("内容预览（ID）").contains("指定用户")
                .contains("2 人（发送时重新计算）").contains("立即发送").contains("App 内页面 · Toko 首页");
        assertThat(rowsWithTitle(title)).as("确认页不落库").isZero();

        MvcResult res = mvc.perform(htmx(form(post(BASE), title, "userIds", ids, "previewCount", "2"), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getHeader("HX-Redirect")).isEqualTo(BASE);
        assertThat(res.getFlashMap().get("toast")).isEqualTo("已提交，正在发送（操作留审计）");

        String token = (String) rowByTitle(title).get("public_token");
        assertThat(awaitFinalStatus(token)).isEqualTo("DONE");
        Map<String, Object> row = rowByTitle(title);
        assertThat(row.get("recipient_count")).isEqualTo(2);
        assertThat(row.get("estimated_count")).isEqualTo(2);
        assertThat(row.get("planned_count")).as("发送核心开始时写入").isEqualTo(2);
        assertThat(row.get("ids_source")).isEqualTo("PASTE");
        assertThat(row.get("skipped_count")).as("子表只存数字 ID：无效的那 1 个").isEqualTo(1);
        for (User u : List.of(a, b)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'OPS_ANNOUNCEMENT' "
                    + "AND target_ref = ? AND recipient_user_id = ?", Long.class, token, u.getId())).isEqualTo(1);
        }
        assertThat(auditCount("OPS_NOTIFY_CREATED", token)).isEqualTo(1);

        String list = mvc.perform(get(BASE).param("lang", "zh_CN").param("range", "7").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ops-notify-row-" + token).contains("已完成").contains("badge-ok")
                .doesNotContain(BASE + "/" + token + "/confirm-cancel")
                .doesNotContain(BASE + "/" + row.get("id") + "/");
    }

    @Test
    @DisplayName("I1 / I10：定时 + CSV 来源 → 列表「（定时）」「预计 1」「指定用户（CSV）」+ 取消链接 → 取消弹层 → 已取消；再取消 409")
    void scheduledThenCancelModal() throws Exception {
        Authentication ed = sender();
        User a = newUser();
        String title = "opsn-sched-" + SEQ.incrementAndGet();
        String at = tomorrowWib();
        MvcResult created = mvc.perform(htmx(form(post(BASE), title, "userIds", String.valueOf(a.getId()),
                                "idsSource", "CSV", "sendMode", "SCHEDULED", "scheduledAt", at), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(created.getFlashMap().get("toast"))
                .isEqualTo("已提交，将于 " + at.replace('T', ' ') + " WIB 发送（操作留审计）");
        Map<String, Object> row = rowByTitle(title);
        String token = (String) row.get("public_token");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("ids_source")).isEqualTo("CSV");
        assertThat(row.get("planned_count")).isNull();

        String shortAt = at.substring(5, 10).replace('-', '/') + " " + at.substring(11);
        String list = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("待发送").contains("badge-info").contains(shortAt + "（定时）").contains("预计 1")
                .contains("指定用户（CSV）").contains(BASE + "/" + token + "/confirm-cancel");

        MvcResult modal = mvc.perform(htmx(get(BASE + "/" + token + "/confirm-cancel"), "ops-notify-row-error")
                        .param("range", "30").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn();
        assertThat(modal.getResponse().getHeader("HX-Retarget")).isEqualTo("#confirm-host");
        assertThat(modal.getResponse().getContentAsString()).contains("取消这次定时发送？")
                .contains("「" + title + "」将不会在 " + shortAt + " 发出").contains("取消发送")
                .contains("hx-post=\"" + BASE + "/" + token + "/cancel\"").contains("btn btn-danger");

        MvcResult cancel = mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "confirm-modal-err")
                        .param("range", "30").param("page", "0").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(cancel.getResponse().getHeader("HX-Trigger-After-Swap")).isEqualTo("admin:confirm-close");
        assertThat(cancel.getResponse().getContentAsString()).contains("id=\"ops-notify-rows\" hx-swap-oob=\"true\"")
                .contains("已取消这次定时发送");
        assertThat(rowByTitle(title).get("status")).isEqualTo("CANCELED");
        assertThat(auditCount("OPS_NOTIFY_CANCELED", token)).isEqualTo(1);

        // 已取消：拉弹层 409、直接 POST 也 409；列表里它是「已取消」「—」，没有取消链接
        mvc.perform(htmx(get(BASE + "/" + token + "/confirm-cancel"), "ops-notify-row-error")
                        .with(authentication(ed)))
                .andExpect(status().isConflict());
        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "confirm-modal-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isConflict());
        String filtered = mvc.perform(htmx(get(BASE), "ops-notify-rows").param("status", "CANCELED")
                        .param("range", "all").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(filtered).doesNotContain("<html").contains("ops-notify-row-" + token).contains("已取消")
                .doesNotContain("badge-info");
        mvc.perform(htmx(get(BASE + "/nosuchtoken/confirm-cancel"), "ops-notify-row-error")
                        .with(authentication(ed)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("I5：上传 CSV 校验按原因计数（不存在 / 已注销 / 已封号 / 虚拟号）；非数字行计入不存在、不报 422；导出跳过清单")
    void csvCheckCountsPerReasonAndExport() throws Exception {
        Authentication ed = sender();
        User ok = newUser();
        User deleted = newUser();
        User banned = newUser();
        User virtual = newUser();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());
        // 虚拟号 sub 前缀须同步改成 virtual:，否则留下一行脏数据让 SyntheticAccountParityTest 全表护栏变红。
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                virtual.getId());
        String csv = "﻿user_id,name\r\n" + ok.getId() + ",Budi\r\n" + deleted.getId() + "\r\n"
                + banned.getId() + "\r\n" + virtual.getId() + "\r\n999999999999\r\n=cmd\r\n";
        MockMultipartFile file = new MockMultipartFile("file", "ids.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        String body = mvc.perform(multipart(BASE + "/ids/check").file(file).header("HX-Request", "true")
                        .header("HX-Target", "opsn-ids-result").param("lang", "zh_CN")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("data-valid=\"1\"")
                .contains("有效 1 人，跳过 5 个（不存在 2 · 已注销 1 · 已封号 1 · 虚拟号 1）"); // 首行表头 user_id 不计（验收 2026-10-15）

        // 粘贴同样内容 → 同样结果（文本参数）
        String pasted = mvc.perform(htmx(post(BASE + "/ids/check").param("userIds", csv), "opsn-ids-result")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(pasted).contains("data-valid=\"1\"").contains("不存在 2");

        MvcResult export = mvc.perform(post(BASE + "/ids/skipped.csv").param("userIds", csv).param("lang", "zh_CN")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(export.getResponse().getContentType()).startsWith("text/csv");
        assertThat(export.getResponse().getHeader("Content-Disposition")).contains("attachment");
        String out = export.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(out).startsWith("﻿\"原始内容\",\"原因\"")
                .doesNotContain("user_id").contains("\"" + deleted.getId() + "\",\"已注销\"")
                .contains("\"" + banned.getId() + "\",\"已封号\"").contains("\"" + virtual.getId() + "\",\"虚拟号\"")
                .contains("\"999999999999\",\"不存在\"")
                .as("CSV 公式注入防护").contains("\"'=cmd\"").doesNotContain("\"" + ok.getId() + "\"");

        // 超过 5 万行 → 422 落回结果槽
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 50_001; i++) {
            many.append(i).append('\n');
        }
        MvcResult tooMany = mvc.perform(htmx(post(BASE + "/ids/check").param("userIds", many.toString()),
                        "opsn-ids-result").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(tooMany.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.audience.ids-too-many\"");
    }

    @Test
    @DisplayName("5 万行指定 ID（urlencoded 整表提交）：ids/check 与新建都接得住；定时 + 立刻取消，绝不立即发")
    void fiftyThousandIdLinesAccepted() throws Exception {
        Authentication ed = sender();
        User ok = newUser();
        // 1 个现造账号 + 49,999 个库里不可能有的 ID（18 位以内、远超自增范围），共 50,000 行，CRLF 分隔（Excel 导出形状）
        StringBuilder sb = new StringBuilder().append(ok.getId()).append("\r\n");
        for (long i = 1; i < 50_000; i++) {
            sb.append(900_000_000_000_000L + i).append("\r\n");
        }
        String ids = sb.toString();
        assertThat(ids.getBytes(StandardCharsets.UTF_8).length).as("贴近真实上限的体量").isGreaterThan(800_000);
        // MockMvc 不经过 Tomcat：表单体上限只能钉配置值（默认 2MB，urlencode 后的 5 万行 CSV 会在进 controller 前解析失败）
        assertThat(DataSize.parse(env.getProperty("server.tomcat.max-http-form-post-size", "2MB")))
                .isGreaterThanOrEqualTo(DataSize.ofMegabytes(10));

        String check = mvc.perform(htmx(post(BASE + "/ids/check").param("userIds", ids), "opsn-ids-result")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(check).contains("data-valid=\"1\"");

        String title = "opsn-50k-" + SEQ.incrementAndGet();
        MvcResult created = mvc.perform(htmx(form(post(BASE), title, "userIds", ids, "idsSource", "CSV",
                        "sendMode", "SCHEDULED", "scheduledAt", tomorrowWib(), "previewCount", "1"), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(created.getResponse().getHeader("HX-Redirect")).isEqualTo(BASE);
        Map<String, Object> row = rowByTitle(title);
        assertThat(row.get("ids_source")).isEqualTo("CSV");
        String token = (String) row.get("public_token");
        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "confirm-modal-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(rowByTitle(title).get("status")).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("I4 相对时间预设：把现造账号的注册时间挪到 20 天前 → 近 7 天少 1 人、近 30 天不变（按此刻计算）")
    void relativeDaysFilterCounts() throws Exception {
        Authentication ed = sender();
        User u = newUser();
        long in7 = estimate(ed, "audienceMode", "FILTER", "registeredWithin", "7");
        long in30 = estimate(ed, "audienceMode", "FILTER", "registeredWithin", "30");
        jdbc.update("UPDATE users SET created_at = now() - interval '20 days' WHERE id = ?", u.getId());
        backdated.add(u.getId());
        assertThat(estimate(ed, "audienceMode", "FILTER", "registeredWithin", "7")).isEqualTo(in7 - 1);
        assertThat(estimate(ed, "audienceMode", "FILTER", "registeredWithin", "30")).isEqualTo(in30);

        // 非法预设 → 422 落回预计人数槽
        MvcResult bad = mvc.perform(htmx(form(post(BASE + "/preview"), "", "audienceMode", "FILTER",
                        "registeredWithin", "14"), "opsn-estimate").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(bad.getResponse().getHeader("HX-Retarget")).isEqualTo("#opsn-estimate");
        assertThat(bad.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.audience.invalid\"");
    }

    @Test
    @DisplayName("I8：全部用户须手输人数（定时 + 立刻取消，绝不立即发）：缺 → 输入确认弹层且不落库；不等 → 422；相等 → 建成")
    void typedCountModalRequiredForAll() throws Exception {
        Authentication ed = sender();
        String title = "opsn-all-" + SEQ.incrementAndGet();
        String at = tomorrowWib();
        long n = estimate(ed, "audienceMode", "ALL");
        String key = UUID.randomUUID().toString();
        String[] base = {"audienceMode", "ALL", "userIds", "", "sendMode", "SCHEDULED", "scheduledAt", at,
                "previewCount", String.valueOf(n), "submitKey", key};

        MvcResult modal = mvc.perform(htmx(form(post(BASE), title, base), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(modal.getResponse().getHeader("HX-Retarget")).isEqualTo("#confirm-host");
        assertThat(modal.getResponse().getHeader("HX-Redirect")).isNull();
        assertThat(modal.getResponse().getContentAsString()).contains("确认发送给").contains("这是一次大范围发送")
                .contains("cm-banner-warn").contains("data-expected=\"" + n + "\"").contains("name=\"confirmCount\"")
                .contains("请输入预计人数 " + n + " 以确认").contains("name=\"submitKey\" value=\"" + key + "\"")
                .contains("name=\"audienceMode\" value=\"ALL\"").contains("btn btn-primary");
        assertThat(rowsWithTitle(title)).isZero();

        MvcResult wrong = mvc.perform(htmx(form(post(BASE), title, concat(base, "confirmCount", String.valueOf(n + 1))),
                        "confirm-modal-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(wrong.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.confirm-count-mismatch\"");
        assertThat(rowsWithTitle(title)).isZero();

        MvcResult ok = mvc.perform(htmx(form(post(BASE), title, concat(base, "confirmCount", String.valueOf(n))),
                        "confirm-modal-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(ok.getResponse().getHeader("HX-Redirect")).isEqualTo(BASE);
        Map<String, Object> row = rowByTitle(title);
        String token = (String) row.get("public_token");
        // 立刻取消：全量活动绝不留在库里等扫描
        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "confirm-modal-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(rowByTitle(title).get("status")).isEqualTo("CANCELED");
        assertThat(row.get("estimated_count")).isNotNull();
        assertThat(row.get("ids_source")).isNull();
    }

    private static String[] concat(String[] a, String... more) {
        String[] out = java.util.Arrays.copyOf(a, a.length + more.length);
        System.arraycopy(more, 0, out, a.length, more.length);
        return out;
    }

    @Test
    @DisplayName("服务端按步骤报第一处错（422 落 #opsn-err 带码）：文案 / 外链非 https / 未选跳转 / ID 全无效 / 定时已过；均不落库")
    void reviewAndCreateValidation() throws Exception {
        Authentication ed = sender();
        String me = String.valueOf(newUser().getId());
        String title = "opsn-422-" + SEQ.incrementAndGet();
        Object[][] cases = {
                {new String[] {"titleEn", "", "userIds", me}, "admin.err.ops-notify.text.blank"},
                {new String[] {"bodyId", "x".repeat(256), "userIds", me}, "admin.err.ops-notify.text.too-long"},
                {new String[] {"userIds", me, "targetType", ""}, "admin.err.opsPopup.targetTypeRequired"},
                {new String[] {"userIds", me, "targetType", "EXTERNAL", "targetValue", "http://x.example/a"},
                        "admin.err.ops-notify.target.https"},
                {new String[] {"userIds", "abc\n999999999999"}, "admin.err.ops-notify.audience-empty"},
                {new String[] {"userIds", ""}, "admin.err.ops-notify.audience.ids-empty"},
                {new String[] {"userIds", me, "sendMode", "SCHEDULED", "scheduledAt", ""},
                        "admin.err.ops-notify.schedule.required"},
                {new String[] {"userIds", me, "sendMode", "SCHEDULED", "scheduledAt", "2020-01-01T00:00"},
                        "admin.err.ops-notify.schedule.past"},
        };
        for (String path : List.of(BASE + "/review", BASE)) {
            for (Object[] c : cases) {
                MvcResult res = mvc.perform(htmx(form(post(path), title, (String[]) c[0]), "opsn-err")
                                .with(authentication(ed)).with(csrf()))
                        .andExpect(status().isUnprocessableEntity()).andReturn();
                assertThat(res.getResponse().getHeader("HX-Retarget")).isEqualTo("#opsn-err");
                assertThat(res.getResponse().getContentAsString()).as(path + " " + c[1])
                        .contains("data-code=\"" + c[1] + "\"").doesNotContain("??");
            }
        }
        // 外链 https 通过确认页
        mvc.perform(htmx(form(post(BASE + "/review"), title, "userIds", me, "targetType", "EXTERNAL",
                        "targetValue", "https://promo.mitra-petshop.co.id/kucing"), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        assertThat(rowsWithTitle(title)).isZero();
    }

    @Test
    @DisplayName("一次性 submitKey：校验失败不锁；成功后同一键再提交 → 422 duplicate-submit，只建一条")
    void duplicateSubmitKeyRejected() throws Exception {
        Authentication ed = sender();
        String uid = String.valueOf(newUser().getId());
        String title = "opsn-dup-" + SEQ.incrementAndGet();
        String key = UUID.randomUUID().toString();
        String at = tomorrowWib();
        String[] f = {"userIds", uid, "sendMode", "SCHEDULED", "scheduledAt", at, "submitKey", key};

        mvc.perform(htmx(form(post(BASE), title, concat(f, "titleEn", "")), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(htmx(form(post(BASE), title, f), "opsn-err").with(authentication(ed)).with(csrf()))
                .andExpect(status().isOk());
        MvcResult again = mvc.perform(htmx(form(post(BASE), title, f), "opsn-err")
                        .with(authentication(ed)).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(again.getResponse().getContentAsString())
                .contains("data-code=\"admin.err.ops-notify.duplicate-submit\"");
        assertThat(rowsWithTitle(title)).isEqualTo(1);
    }

    @Test
    @DisplayName("I11 · 🔒 只读（仅 ops_notify.view）：看得到记录与只读提示，无新建 / 取消入口；新建页与全部写端点 403")
    void readOnlyAccount() throws Exception {
        Authentication ed = sender();
        User a = newUser();
        String title = "opsn-ro-" + SEQ.incrementAndGet();
        mvc.perform(form(post(BASE), title, "userIds", String.valueOf(a.getId()), "sendMode", "SCHEDULED",
                        "scheduledAt", tomorrowWib()).with(authentication(ed)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        String token = (String) rowByTitle(title).get("public_token");

        Authentication viewer = staffWith(AdminPermissions.OPS_NOTIFY_VIEW);
        String page = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(title).contains("只读：需要「发送系统通知」权限才能新建或取消。")
                .contains("系统通知发送").doesNotContain("href=\"" + BASE + "/new\"").doesNotContain("/confirm-cancel");
        String editorPage = mvc.perform(get(BASE).param("lang", "zh_CN").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(editorPage).contains("href=\"" + BASE + "/new\"").doesNotContain("只读：需要");

        String aId = String.valueOf(a.getId());
        mvc.perform(get(BASE + "/new").with(authentication(viewer))).andExpect(status().isForbidden());
        for (String path : List.of(BASE, BASE + "/preview", BASE + "/review", BASE + "/ids/check",
                BASE + "/ids/skipped.csv")) {
            mvc.perform(htmx(form(post(path), "", "userIds", aId), "opsn-err")
                            .with(authentication(viewer)).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(htmx(get(BASE + "/" + token + "/confirm-cancel"), "ops-notify-row-error")
                        .with(authentication(viewer)))
                .andExpect(status().isForbidden());
        mvc.perform(htmx(post(BASE + "/" + token + "/cancel"), "confirm-modal-err")
                        .with(authentication(viewer)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(rowByTitle(title).get("status")).isEqualTo("PENDING");

        // 只有 send 的人也进得来列表；没有 ops_notify.* 的进不来（不再沿用 config.*）
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.OPS_NOTIFY_SEND))))
                .andExpect(status().isOk());
        mvc.perform(get(BASE).with(authentication(staffWith(AdminPermissions.CONFIG_VIEW,
                AdminPermissions.CONFIG_EDIT)))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("I1 列表：筛选栏（状态 / 发送时间默认近 30 天）+ 列头；越界页码回落；非法筛选值按默认")
    void listFiltersAndColumns() throws Exception {
        Authentication ed = sender();
        String page = mvc.perform(get(BASE).param("lang", "zh_CN").param("status", "BOGUS").param("range", "x")
                        .param("page", "999").with(authentication(ed)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("状态：全部").contains("发送时间：近 30 天")
                .containsPattern("<option value=\"30\" selected=\"selected\">发送时间：近 30 天</option>")
                .contains("发送后写入用户 App 通知中心").contains("campaign_id").contains("id=\"confirm-host\"");
        if (page.contains("opsn-table")) {
            assertThat(page).contains("内容摘要").contains("人群").contains("发送时间（WIB）").contains("触达人数");
        }
    }
}
