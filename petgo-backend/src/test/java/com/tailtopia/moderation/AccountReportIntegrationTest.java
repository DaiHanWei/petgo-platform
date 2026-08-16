package com.tailtopia.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.AccountReport;
import com.tailtopia.moderation.domain.AccountReportEntry;
import com.tailtopia.moderation.domain.AccountReportStatus;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.repository.UserHideRelationRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * L1：账号举报（Story 2.1，FR-58）—— 需 Docker postgres+redis。
 *
 * <p>覆盖 AC2 工单粒度 / AC3 明细每次追加 / AC4 五类枚举与 OTHER 必填 / AC5 举报即隐藏同事务 +
 * 不碰 BLOCK 行 / AC6 六处过滤自动生效 / AC7 不可解除不进黑名单 / AC8「已举报」持久化 /
 * AC9 已处置工单翻回 / AC10 边界与零自动预处置 / AC11 秒级去重。
 *
 * <p>⚠️ <b>「同一人多次举报」的用例要先把已有明细的时间往前拨</b>：服务端有一道 5 秒去重窗口
 * （防双击穿透），测试里连着提交 7 次会被它合成 1 次。拨时间模拟的是「这几次发生在不同时刻」，
 * <b>不是绕过被测逻辑</b> —— 去重窗口本身另有专门用例正面验证。
 */
class AccountReportIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AccountReportRepository reports;

    @Autowired
    private AccountReportEntryRepository entries;

    @Autowired
    private UserHideRelationRepository relations;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    private String body(long targetUserId, String reason) {
        return "{\"targetUserId\":" + targetUserId + ",\"reason\":\"" + reason + "\"}";
    }

    private String body(long targetUserId, String reason, String detail) {
        return "{\"targetUserId\":" + targetUserId + ",\"reason\":\"" + reason
                + "\",\"detail\":\"" + detail + "\"}";
    }

    private void report(long reporterId, String json) throws Exception {
        mvc.perform(post("/api/v1/account-reports")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporterId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNoContent());
    }

    /** 把某工单已有明细的时间整体前拨一分钟 —— 让下一次提交落在 5 秒去重窗口之外。 */
    private void backdateEntries(long reportId) {
        jdbc.update("UPDATE account_report_entries SET created_at = created_at - interval '1 minute' "
                + "WHERE report_id = ?", reportId);
    }

    private AccountReport ticketOf(long targetUserId) {
        return reports.findByTargetUserId(targetUserId).orElseThrow();
    }

    // ===== AC2 · 工单粒度 = 被举报账号 =====

    /** 12 个人举报同一个账号、其中甲报了 7 次 → <b>1 条工单 + 18 行明细</b>。 */
    @Test
    void ac2_oneTicketPerTargetWithEveryReportKept() throws Exception {
        User target = newUser();
        User first = newUser();

        report(first.getId(), body(target.getId(), "HARASSMENT"));
        long reportId = ticketOf(target.getId()).getId();

        // 甲再报 6 次（共 7 次），每次都拨一下时间避开去重窗口。
        for (int i = 0; i < 6; i++) {
            backdateEntries(reportId);
            report(first.getId(), body(target.getId(), "SPAM"));
        }
        // 另外 11 个人各报 1 次。
        for (int i = 0; i < 11; i++) {
            report(newUser().getId(), body(target.getId(), "SPAM"));
        }

        assertThat(reports.count()).isPositive();
        assertThat(reports.findByTargetUserId(target.getId())).isPresent();
        assertThat(entries.countByReportId(reportId)).isEqualTo(18); // 7 + 11
    }

    // ===== AC3 · 每一次的类型与说明都要留 =====

    @Test
    void ac3_everyReasonAndDetailIsAppendedNotOverwritten() throws Exception {
        User target = newUser();
        User reporter = newUser();

        report(reporter.getId(), body(target.getId(), "HARASSMENT"));
        long reportId = ticketOf(target.getId()).getId();
        backdateEntries(reportId);
        report(reporter.getId(), body(target.getId(), "OTHER", "他冒充我朋友"));

        List<AccountReportEntry> rows = entries.findByReportIdOrderByCreatedAtDesc(reportId);
        assertThat(rows).hasSize(2);
        // 两次的类型都在，第二次的说明追加保存、没有覆盖第一行。
        assertThat(rows.get(0).getReason().name()).isEqualTo("OTHER");
        assertThat(rows.get(0).getDetail()).isEqualTo("他冒充我朋友");
        assertThat(rows.get(1).getReason().name()).isEqualTo("HARASSMENT");
        assertThat(rows.get(1).getDetail()).isNull();
    }

    // ===== AC4 · 账号维度五类 + OTHER 必填 =====

    @Test
    void ac4_allFiveAccountReasonsAreAccepted() throws Exception {
        for (String reason : List.of("SPAM", "IMPERSONATION", "HARASSMENT", "VIOLATING_CONTENT")) {
            User target = newUser();
            report(newUser().getId(), body(target.getId(), reason));
            assertThat(reports.findByTargetUserId(target.getId())).isPresent();
        }
        User target = newUser();
        report(newUser().getId(), body(target.getId(), "OTHER", "说明"));
        assertThat(reports.findByTargetUserId(target.getId())).isPresent();
    }

    /** 内容维度的理由（如 MISINFO）不属于账号五类 → 反序列化直接失败，不会静默落库。 */
    @Test
    void ac4_contentReasonIsRejected() throws Exception {
        User target = newUser();
        mvc.perform(post("/api/v1/account-reports")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.getId(), "MISINFO")))
                .andExpect(status().is4xxClientError());
        assertThat(reports.findByTargetUserId(target.getId())).isEmpty();
    }

    @Test
    void ac4_otherRequiresDetail() throws Exception {
        User target = newUser();
        mvc.perform(post("/api/v1/account-reports")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.getId(), "OTHER")))
                .andExpect(status().is4xxClientError());
        assertThat(reports.findByTargetUserId(target.getId())).isEmpty();
    }

    /** 其余四类不保存补充说明（前端根本不展示那个输入框，静默丢弃而不是回一个 422）。 */
    @Test
    void ac4_detailIsNotStoredForNonOtherReasons() throws Exception {
        User target = newUser();
        report(newUser().getId(), body(target.getId(), "SPAM", "随手填的"));

        long reportId = ticketOf(target.getId()).getId();
        assertThat(entries.findByReportIdOrderByCreatedAtDesc(reportId).get(0).getDetail()).isNull();
    }

    // ===== AC5 · 举报即隐藏（同事务）=====

    @Test
    void ac5_reportCreatesReportSourcedHideRelation() throws Exception {
        User target = newUser();
        User reporter = newUser();

        report(reporter.getId(), body(target.getId(), "SPAM"));

        assertThat(relations.findByHolderIdAndTargetIdAndSource(
                reporter.getId(), target.getId(), HideSource.REPORT)).isPresent();
    }

    /**
     * ⚠️ 先拉黑、后举报：写 REPORT 行<b>不得改动 BLOCK 行的任何字段</b>（含时间戳）。
     *
     * <p>这条与 Story 1.5 AC2 的排序约束是一对：读侧钉死「排序取 BLOCK.created_at」，
     * 写侧钉死「举报不碰 BLOCK 行」。只做一边，另一边的实现一变就会重新穿帮 ——
     * 用户看到的是「我今天什么都没做，这个三个月前拉黑的人怎么跑到黑名单最前面了」。
     */
    @Test
    void ac5_reportDoesNotTouchExistingBlockRow() throws Exception {
        User target = newUser();
        User reporter = newUser();
        hideService.block(reporter.getId(), target.getId());
        var before = relations.findByHolderIdAndTargetIdAndSource(
                reporter.getId(), target.getId(), HideSource.BLOCK).orElseThrow();
        var beforeCreated = before.getCreatedAt();
        var beforeUpdated = before.getUpdatedAt();
        var beforeId = before.getId();

        Thread.sleep(20);
        report(reporter.getId(), body(target.getId(), "HARASSMENT"));

        var after = relations.findByHolderIdAndTargetIdAndSource(
                reporter.getId(), target.getId(), HideSource.BLOCK).orElseThrow();
        assertThat(after.getId()).isEqualTo(beforeId);
        assertThat(after.getCreatedAt()).isEqualTo(beforeCreated);
        assertThat(after.getUpdatedAt()).isEqualTo(beforeUpdated);
        // 两条关系是彼此独立的行，REPORT 行照常新增。
        assertThat(relations.findByHolderIdAndTargetIdAndSource(
                reporter.getId(), target.getId(), HideSource.REPORT)).isPresent();
    }

    // ===== AC6 · 复用 Epic 1 的六处过滤（回归验证）=====

    /** 举报之后，被举报者的帖子详情对举报人 404 —— 举报隐藏走的就是拉黑那套过滤。 */
    @Test
    void ac6_reportedUsersPostBecomesUnreachableForTheReporter() throws Exception {
        User target = newUser();
        User reporter = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(target.getId(), ContentType.DAILY, null, "正文", List.of()));

        mvc.perform(get("/api/v1/content-posts/" + post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isOk());

        report(reporter.getId(), body(target.getId(), "SPAM"));

        mvc.perform(get("/api/v1/content-posts/" + post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isNotFound());
        // 对其他人照常可见。
        mvc.perform(get("/api/v1/content-posts/" + post.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(newUser().getId())))
                .andExpect(status().isOk());
    }

    /** ⚠️ 唯一例外：举报之后<b>仍能进对方的迷你主页</b>——FR-58 的闭环全靠它（AD-11）。 */
    @Test
    void ac6_miniProfileStaysReachableAfterReporting() throws Exception {
        User target = newUser();
        User reporter = newUser();
        report(reporter.getId(), body(target.getId(), "SPAM"));

        mvc.perform(get("/api/v1/users/" + target.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isOk());
    }

    // ===== AC7 · 举报隐藏不可解除、不进黑名单页 =====

    @Test
    void ac7_reportHideIsNotUnblockableAndNotInBlockList() throws Exception {
        User target = newUser();
        User reporter = newUser();
        report(reporter.getId(), body(target.getId(), "SPAM"));

        // 解除拉黑只删 BLOCK 行；REPORT 行没有任何解除入口。
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/blocked-users/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isNoContent());
        assertThat(relations.findByHolderIdAndTargetIdAndSource(
                reporter.getId(), target.getId(), HideSource.REPORT)).isPresent();

        // 黑名单页只收录含 BLOCK 的条目。
        MvcResult r = mvc.perform(get("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).isEqualTo("[]");
    }

    // ===== AC8 ·「已举报」服务端持久化 =====

    @Test
    void ac8_miniProfileCarriesReportedFlagForTheReporter() throws Exception {
        User target = newUser();
        User reporter = newUser();
        User bystander = newUser();

        report(reporter.getId(), body(target.getId(), "SPAM"));

        mvc.perform(get("/api/v1/users/" + target.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(reporter.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reported").value(true));
        // 没举报过的人拿到 false。
        mvc.perform(get("/api/v1/users/" + target.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(bystander.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reported").value(false));
    }

    /**
     * ⚠️ 游客响应体的 key 集合<b>一字未变</b>（Story 1.1 AC6 的硬要求）。
     *
     * <p>装箱 {@code Boolean} + 游客置 null，靠全局 NON_NULL 把整个键省略掉。
     * 写成 primitive {@code boolean} 这条立刻红。
     */
    @Test
    void ac8_guestResponseKeysAreUnchanged() throws Exception {
        User target = newUser();
        report(newUser().getId(), body(target.getId(), "SPAM"));

        mvc.perform(get("/api/v1/users/" + target.getId() + "/mini-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reported").doesNotExist());
    }

    /**
     * 只拉黑、没举报 → 两个来源各管各的。
     *
     * <p>⚠️ 这一条<b>不能拿迷你主页去验</b>：主动拉黑之后主页本来就进不去了（403 blocked-user，
     * Story 1.1 AC6）——那正是「拉黑」与「举报」两条路径的分水岭。所以这里断言的是
     * 「主页被拦住」+「黑名单里那一条的 reported 为 false」。
     */
    @Test
    void ac8_blockAloneDoesNotSetReportedFlag() throws Exception {
        User target = newUser();
        User me = newUser();
        hideService.block(me.getId(), target.getId());

        mvc.perform(get("/api/v1/users/" + target.getId() + "/mini-profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isForbidden());

        MvcResult r = mvc.perform(get("/api/v1/me/blocked-users")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(target.getId()))
                .andExpect(jsonPath("$[0].reported").value(false))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("blockedAt");
    }

    // ===== AC9 · 已处置的工单再收到举报 =====

    @Test
    void ac9_handledTicketReopensWithoutCreatingASecondOne() throws Exception {
        User target = newUser();
        report(newUser().getId(), body(target.getId(), "SPAM"));
        AccountReport ticket = ticketOf(target.getId());
        long reportId = ticket.getId();
        var firstReportedAt = ticket.getFirstReportedAt();

        // 模拟运营处置：直接改库（真正的处置动作归 Epic 3）。
        jdbc.update("UPDATE account_reports SET status = 'RESOLVED', handled_by = 1, "
                + "handled_at = now() WHERE id = ?", reportId);

        report(newUser().getId(), body(target.getId(), "HARASSMENT"));

        AccountReport after = ticketOf(target.getId());
        assertThat(after.getId()).isEqualTo(reportId);                       // 不新建
        assertThat(after.getStatus()).isEqualTo(AccountReportStatus.PENDING); // 翻回待处置
        assertThat(after.getHandledBy()).isNull();
        assertThat(after.getHandledAt()).isNull();
        // 「第一次被举报是什么时候」不因翻回而被刷新。
        assertThat(after.getFirstReportedAt()).isEqualTo(firstReportedAt);
        assertThat(entries.countByReportId(reportId)).isEqualTo(2);
    }

    // ===== AC10 · 边界 =====

    @Test
    void ac10_cannotReportYourself() throws Exception {
        User me = newUser();
        mvc.perform(post("/api/v1/account-reports")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(me.getId(), "SPAM")))
                .andExpect(status().is4xxClientError());
        assertThat(reports.findByTargetUserId(me.getId())).isEmpty();
    }

    /**
     * ⚠️ 零自动预处置（AD-17）：15 个人举报同一个账号，他的内容<b>依旧 PUBLISHED</b>。
     *
     * <p>内容侧那两条自动通道（ILLEGAL 单次触发 / 举报人数 ≥ 10）只作用于内容举报，本 story 一行未改。
     */
    @Test
    void ac10_noAutoDisposalNoMatterHowManyReports() throws Exception {
        User target = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(target.getId(), ContentType.DAILY, null, "正文", List.of()));

        for (int i = 0; i < 15; i++) {
            report(newUser().getId(), body(target.getId(), "VIOLATING_CONTENT"));
        }

        assertThat(posts.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.PUBLISHED);
        assertThat(ticketOf(target.getId()).getStatus()).isEqualTo(AccountReportStatus.PENDING);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/account-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(newUser().getId(), "SPAM")))
                .andExpect(status().isUnauthorized());
    }

    // ===== AC11 · 秒级去重 =====

    /**
     * 双击穿透 / 提交中的网络重试 → 视为同一次举报，<b>不新增明细</b>，接口照常成功。
     *
     * <p>后果不是数据错误，是污染两个直接给运营看的数字：工单上的「12 人 / 27 次」，
     * 以及「同一人举报 ≥5 次」的高频加成判定 —— 一个报了 3 次的人被双击刷到 5 次就会被算成高频。
     */
    @Test
    void ac11_rapidDuplicateSubmitDoesNotAddASecondEntry() throws Exception {
        User target = newUser();
        User reporter = newUser();

        report(reporter.getId(), body(target.getId(), "SPAM"));
        report(reporter.getId(), body(target.getId(), "SPAM")); // 立刻再来一次

        assertThat(entries.countByReportId(ticketOf(target.getId()).getId())).isEqualTo(1);
    }

    /** 但去重<b>只针对秒级误触</b>：超出窗口后的再次举报照常追加一行（AC3 不受影响）。 */
    @Test
    void ac11_deduplicationDoesNotBlockLaterGenuineReports() throws Exception {
        User target = newUser();
        User reporter = newUser();

        report(reporter.getId(), body(target.getId(), "SPAM"));
        long reportId = ticketOf(target.getId()).getId();
        backdateEntries(reportId); // 让上一次落在窗口之外
        report(reporter.getId(), body(target.getId(), "IMPERSONATION"));

        assertThat(entries.countByReportId(reportId)).isEqualTo(2);
    }

    /** 去重是「同一人对同一账号」，不同举报人之间互不影响。 */
    @Test
    void ac11_deduplicationIsPerReporter() throws Exception {
        User target = newUser();
        report(newUser().getId(), body(target.getId(), "SPAM"));
        report(newUser().getId(), body(target.getId(), "SPAM"));

        assertThat(entries.countByReportId(ticketOf(target.getId()).getId())).isEqualTo(2);
    }
}
