package com.tailtopia.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：统一工单队列的联合查询与优先级公式（Story 3.1）—— 需 Docker postgres。
 *
 * <p><b>优先级公式的四个校验用例逐一钉死</b>（AC4）——公式的设计目的是让<b>众怒排在纠缠前面</b>：
 * 一个人刷 100 次也就 2 分，十个人各报一次是 10 分。改公式前先看这四条会不会红。
 *
 * <p>⚠️ 造「同一人多次举报」的数据要把已有明细的时间往前拨：服务端有 5 秒去重窗口（Story 2.1 AC11），
 * 连着提交会被合成一次。拨时间模拟的是「这几次发生在不同时刻」，不是绕过被测逻辑。
 */
class UnifiedTicketQueryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UnifiedTicketQueryService query;

    @Autowired
    private AccountReportService accountReports;

    @Autowired
    private AccountReportRepository reports;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    private void backdate(long reportId) {
        jdbc.update("UPDATE account_report_entries SET created_at = created_at - interval '1 minute' "
                + "WHERE report_id = ?", reportId);
    }

    /** 让某人对某账号举报 n 次（每次前把已有明细拨走，避开 5 秒去重窗口）。 */
    private void reportTimes(long reporterId, long targetId, int times) {
        for (int i = 0; i < times; i++) {
            reports.findByTargetUserId(targetId).ifPresent(r -> backdate(r.getId()));
            accountReports.submit(reporterId, targetId, AccountReportReason.SPAM, null);
        }
    }

    /**
     * 按类别取某账号那一行。
     *
     * <p>⚠️ 两处都不能省，否则用例会**随类内其它用例造的数据量而随机红**：
     * <ul>
     *   <li><b>必须按 targetUserId 精确过滤</b> —— 检索词是纯数字时，服务端除了按 id 精确匹配，
     *       还会按<b>昵称模糊匹配</b>（数字昵称是印尼市场常见形态，那是有意为之）。
     *       测试用户的昵称是「用户+19 位 nanoTime」，搜 id「1172」会顺带命中昵称里含 1172 的别人。
     *       {@code findFirst()} 于是可能抓到另一个账号的行。</li>
     *   <li><b>页要开够大</b> —— 排序是分倒序，那些误命中的行分可能更高，把要找的行挤下第一页。</li>
     * </ul>
     */
    private UnifiedTicketRow rowOf(TicketType type, long targetUserId) {
        return query.search(type, null, String.valueOf(targetUserId), PageRequest.of(0, 500))
                .getContent().stream()
                .filter(r -> r.targetUserId() != null && r.targetUserId() == targetUserId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没查到 " + targetUserId + " 的 " + type + " 工单"));
    }

    private UnifiedTicketRow accountTicketOf(long targetUserId) {
        return rowOf(TicketType.ACCOUNT_REPORT, targetUserId);
    }

    // ===== AC4 · 优先级公式的四个校验用例 =====

    /** 甲报 3 次 + 乙报 7 次 → **3 分**（2 人 + 1 个高频）。 */
    @Test
    void ac4_threePlusSeven() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 3);
        reportTimes(newUser().getId(), target.getId(), 7);

        UnifiedTicketRow row = accountTicketOf(target.getId());
        assertThat(row.reporterCount()).isEqualTo(2);
        assertThat(row.reportCount()).isEqualTo(10);
        assertThat(row.frequentCount()).isEqualTo(1); // 只有乙 ≥5 次
        assertThat(row.score()).isEqualTo(3);
    }

    /** 甲报 5 次 + 乙报 6 次 → **4 分**（2 人 + 2 个高频；5 次<b>含</b>在内）。 */
    @Test
    void ac4_fivePlusSix() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 5);
        reportTimes(newUser().getId(), target.getId(), 6);

        UnifiedTicketRow row = accountTicketOf(target.getId());
        assertThat(row.frequentCount()).isEqualTo(2); // 门槛是「≥5」不是「>5」
        assertThat(row.score()).isEqualTo(4);
    }

    /** 10 个人各报 1 次 → **10 分**（众怒）。 */
    @Test
    void ac4_tenPeopleOnceEach() {
        User target = newUser();
        for (int i = 0; i < 10; i++) {
            reportTimes(newUser().getId(), target.getId(), 1);
        }

        UnifiedTicketRow row = accountTicketOf(target.getId());
        assertThat(row.reporterCount()).isEqualTo(10);
        assertThat(row.reportCount()).isEqualTo(10);
        assertThat(row.frequentCount()).isZero();
        assertThat(row.score()).isEqualTo(10);
    }

    /**
     * ⚠️ 1 个人报 100 次 → **2 分**，不是 100 分。
     *
     * <p>单个举报人对分数的贡献上限就是 2（1 基础 + 1 高频）—— 这是公式的设计目的，不是巧合。
     * 少了这条封顶，一个纠缠的人就能把自己顶到队首，把十个人的众怒压下去。
     */
    @Test
    void ac4_onePersonHundredTimes() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 100);

        UnifiedTicketRow row = accountTicketOf(target.getId());
        assertThat(row.reporterCount()).isEqualTo(1);
        assertThat(row.reportCount()).isEqualTo(100);
        assertThat(row.frequentCount()).isEqualTo(1);
        assertThat(row.score()).isEqualTo(2);
    }

    // ===== AC2 / AC4 · 排序与联合 =====

    /**
     * 分倒序；**同分按最早一次举报时间升序**（先报的先处理）。
     *
     * <p>⚠️ 三个目标都改成同一个唯一昵称前缀，再按它检索 —— <b>不能直接查全队列</b>：
     * L1 打的是同一个真实库，别的用例造的工单会把这三条挤出第一页，
     * 断言就变成了「运气好不好」（2026-08-16 全量回归时正是这么红的）。
     */
    @Test
    void sortsByScoreThenEarliestFirst() {
        // ⚠️ nickname 列是 varchar(20)，标签要短：base36 压一下再拼后缀（"sc"+10+"-early" = 18）。
        String tag = "sc" + Long.toString(SEQ.incrementAndGet(), 36);
        User loud = renamed(newUser(), tag + "-loud");    // 3 人 → 3 分
        User early = renamed(newUser(), tag + "-early");  // 1 人，但报得更早
        User late = renamed(newUser(), tag + "-late");    // 1 人，报得更晚

        reportTimes(newUser().getId(), early.getId(), 1);
        for (int i = 0; i < 3; i++) {
            reportTimes(newUser().getId(), loud.getId(), 1);
        }
        reportTimes(newUser().getId(), late.getId(), 1);

        List<Long> ids = query.search(TicketType.ACCOUNT_REPORT, TicketStatusBucket.PENDING, tag,
                        PageRequest.of(0, 20))
                .getContent().stream().map(UnifiedTicketRow::targetUserId).toList();

        assertThat(ids).containsExactly(loud.getId(), early.getId(), late.getId());
    }

    private User renamed(User u, String nickname) {
        u.setNickname(nickname);
        return users.save(u);
    }

    /** 内容举报**按帖聚合**：3 个人举报同一条帖 = 一条工单、3 分，不是三条工单。 */
    @Test
    void contentReportsAreAggregatedPerPost() {
        User author = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(author.getId(), ContentType.DAILY, null, "被举报的正文", List.of()));
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO content_reports (post_id, reporter_id, reason_type, status, "
                    + "created_at, updated_at) VALUES (?, ?, 'HARASSMENT', 'PENDING', now(), now())",
                    post.getId(), newUser().getId());
        }

        List<UnifiedTicketRow> rows = query
                .search(TicketType.CONTENT_REPORT, TicketStatusBucket.PENDING,
                        String.valueOf(author.getId()), PageRequest.of(0, 20))
                .getContent();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sourceId()).isEqualTo(post.getId());
        assertThat(rows.get(0).reporterCount()).isEqualTo(3);
        assertThat(rows.get(0).score()).isEqualTo(3);
        assertThat(rows.get(0).preview()).contains("被举报的正文");
    }

    // ===== AC6 · 账号标识字段没有举报人，分数按 priority 映射 =====

    /**
     * ⚠️ `HIGH → 10` / `NORMAL → 2`（C-102）。
     *
     * <p>锚点取自公式自身的两个例子：HIGH 等同于十人举报的众怒、NORMAL 等同于一个纠缠的举报者。
     * <b>NORMAL 绝不能是 0</b> —— 它是这两张表的 DEFAULT 值，绝大多数记录都是它，
     * 映射成 0 会让这类工单几乎全部永远沉底没人看。
     */
    @Test
    void ac6_identityTicketsScoreByPriority() {
        User high = newUser();
        User normal = newUser();
        jdbc.update("INSERT INTO name_moderation_records (target_type, target_ref_id, revision, "
                + "submitted_value, status, priority, submitted_at) "
                + "VALUES ('NICKNAME', ?, 1, '送审昵称', 'MANUAL_PENDING', 'HIGH', now())", high.getId());
        jdbc.update("INSERT INTO avatar_reviews (subject_type, subject_id, avatar_url, status, priority) "
                + "VALUES ('USER_AVATAR', ?, 'https://cdn/x.jpg', 'MANUAL_PENDING', 'NORMAL')",
                normal.getId());

        UnifiedTicketRow highRow = identityRowOf(high.getId());
        UnifiedTicketRow normalRow = identityRowOf(normal.getId());

        assertThat(highRow.score()).isEqualTo(10);
        assertThat(normalRow.score()).isEqualTo(2);
        assertThat(normalRow.score()).isNotZero(); // 沉底守卫
        // 没有举报人这回事，三个计数都是 0。
        assertThat(highRow.reporterCount()).isZero();
        assertThat(highRow.reportCount()).isZero();
        assertThat(highRow.frequentCount()).isZero();
    }

    /** 账号标识字段两表**只收 MANUAL_PENDING 与两个终态**：自动过 / 陈旧作废不进运营队列。 */
    @Test
    void ac2_identityAutoPassedRowsNeverEnterTheQueue() {
        User autoPassed = newUser();
        jdbc.update("INSERT INTO name_moderation_records (target_type, target_ref_id, revision, "
                + "submitted_value, status, priority, submitted_at) "
                + "VALUES ('NICKNAME', ?, 1, '自动过的昵称', 'AUTO_PASSED', 'NORMAL', now())",
                autoPassed.getId());

        assertThat(query.search(TicketType.ACCOUNT_IDENTITY, null,
                String.valueOf(autoPassed.getId()), PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    /** 终态映射：名称的 `RESOLVED_PASS` → **无需处置**（不是「已处理」）。 */
    @Test
    void ac2_identityTerminalStatesMapToTheRightBuckets() {
        User passed = newUser();
        User violated = newUser();
        jdbc.update("INSERT INTO name_moderation_records (target_type, target_ref_id, revision, "
                + "submitted_value, status, priority, submitted_at) "
                + "VALUES ('NICKNAME', ?, 1, 'ok', 'RESOLVED_PASS', 'NORMAL', now())", passed.getId());
        jdbc.update("INSERT INTO name_moderation_records (target_type, target_ref_id, revision, "
                + "submitted_value, status, priority, submitted_at) "
                + "VALUES ('NICKNAME', ?, 1, 'bad', 'RESOLVED_VIOLATION', 'HIGH', now())",
                violated.getId());

        assertThat(identityRowOf(passed.getId()).status()).isEqualTo(TicketStatusBucket.NO_ACTION);
        assertThat(identityRowOf(violated.getId()).status()).isEqualTo(TicketStatusBucket.RESOLVED);
    }

    private UnifiedTicketRow identityRowOf(long userId) {
        return rowOf(TicketType.ACCOUNT_IDENTITY, userId);
    }

    // ===== AC9 · 筛选与检索 =====

    @Test
    void ac9_filterByTypeAndSearchByAccount() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 1);

        // 按账号 id 检索
        assertThat(query.search(null, null, String.valueOf(target.getId()), PageRequest.of(0, 20))
                .getContent()).isNotEmpty();
        // 按昵称模糊检索
        assertThat(query.search(null, null, target.getNickname(), PageRequest.of(0, 20))
                .getContent()).isNotEmpty();
        // 类型筛选：这条是账号举报，按内容举报筛就不该出现
        assertThat(query.search(TicketType.CONTENT_REPORT, null, String.valueOf(target.getId()),
                PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    /** 已处置的工单落到「已处理」档，不再混在待处理里。 */
    @Test
    void ac9_statusFilterSeparatesHandledTickets() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 1);
        long reportId = reports.findByTargetUserId(target.getId()).orElseThrow().getId();
        jdbc.update("UPDATE account_reports SET status = 'RESOLVED' WHERE id = ?", reportId);

        assertThat(query.search(null, TicketStatusBucket.PENDING, String.valueOf(target.getId()),
                PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(query.search(null, TicketStatusBucket.RESOLVED, String.valueOf(target.getId()),
                PageRequest.of(0, 20)).getContent()).hasSize(1);
    }

    /** 历史处置次数**含每一次警告**（Story 3.2 写入前恒为 0，这里直接造数验读取口径）。 */
    @Test
    void disposalCountIncludesWarnings() {
        User target = newUser();
        reportTimes(newUser().getId(), target.getId(), 1);
        jdbc.update("INSERT INTO account_disposals (target_user_id, disposal_type, created_at) "
                + "VALUES (?, 'WARNING', now())", target.getId());
        jdbc.update("INSERT INTO account_disposals (target_user_id, disposal_type, created_at) "
                + "VALUES (?, 'SUSPEND', now())", target.getId());

        assertThat(accountTicketOf(target.getId()).disposalCount()).isEqualTo(2);
    }

    // ===== 内容送审（2026-08-19 并入混排列表）=====

    /**
     * 送审分数按 P0/P1/P2 三档映射，且**没有举报人**（三个计数都是 0）。
     *
     * <p>分档锚点：P0(12) 压过标识字段的 HIGH(10)，P2(2) 与 NORMAL 齐平 ——
     * 送审是「内容还没面世、卡在发布链路上」，同等紧迫度下应比事后审核先看。
     */
    @Test
    void submissionScoresByPriority() {
        User author = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(author.getId(), ContentType.DAILY, null, "等放行的正文", List.of()));
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, status, "
                + "priority, created_at, updated_at) "
                + "VALUES (?, 'CONTENT_POST', now(), 'PENDING', 'P0', now(), now())", post.getId());

        UnifiedTicketRow row = submissionRowOf(author.getId());
        assertThat(row.score()).isEqualTo(12);
        assertThat(row.preview()).contains("等放行的正文");
        assertThat(row.subType()).isEqualTo("P0 · CONTENT_POST");
        assertThat(row.reporterCount()).isZero();
        assertThat(row.reportCount()).isZero();
        assertThat(row.frequentCount()).isZero();
    }

    /**
     * ⚠️ 回归守卫：{@code manual_review_queue} 是**多态**表，{@code content_id} 在
     * 帖子与评论两个命名空间里各自计数 —— 一条评论的 id 完全可能等于某条无关帖子的 id。
     *
     * <p>联合查询若漏掉 {@code content_type} 判别，这一行就会显示<b>另一个人的帖子正文和作者</b>，
     * 审核员据此下的每一个判都落在错的对象上。这条用例造的正是那个撞号：
     * 先取一条已存在帖子的 id，再造一条 id 与它相同的评论送审项。
     */
    @Test
    void commentSubmissionNeverMisJoinsAPostWithTheSameId() {
        User postAuthor = newUser();
        ContentPost decoy = posts.save(
                ContentPost.publish(postAuthor.getId(), ContentType.DAILY, null, "无关的别人的帖子", List.of()));

        // 造一条 id 恰好等于 decoy.id 的评论（撞号是本用例的全部意义，故显式指定 id）。
        User commentAuthor = newUser();
        jdbc.update("INSERT INTO comments (id, post_id, author_id, body, created_at, updated_at) "
                + "VALUES (?, ?, ?, '等放行的评论', now(), now())",
                decoy.getId(), decoy.getId(), commentAuthor.getId());
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, status, "
                + "priority, created_at, updated_at) "
                + "VALUES (?, 'COMMENT', now(), 'PENDING', 'P1', now(), now())", decoy.getId());

        UnifiedTicketRow row = submissionRowOf(commentAuthor.getId());
        assertThat(row.targetUserId()).isEqualTo(commentAuthor.getId());
        assertThat(row.preview()).contains("等放行的评论");
        assertThat(row.preview()).doesNotContain("无关的别人的帖子");
        assertThat(row.subType()).isEqualTo("P1 · COMMENT");
        assertThat(row.score()).isEqualTo(6);
    }

    /** 终态映射：{@code TIMED_OUT}（挂到超时被系统放掉）→ **无需处置**，不是「已处理」。 */
    @Test
    void submissionTimedOutMapsToNoAction() {
        User author = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(author.getId(), ContentType.DAILY, null, "超时的正文", List.of()));
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, status, "
                + "priority, created_at, updated_at) "
                + "VALUES (?, 'CONTENT_POST', now(), 'TIMED_OUT', 'P2', now(), now())", post.getId());

        assertThat(submissionRowOf(author.getId()).status()).isEqualTo(TicketStatusBucket.NO_ACTION);
    }

    /** 超 24h 未处置 → 模板高亮（原独立送审表 AC7，随行迁入混排后阈值不变）。 */
    @Test
    void submissionOverdueAfter24Hours() {
        User fresh = newUser();
        User stale = newUser();
        ContentPost freshPost = posts.save(
                ContentPost.publish(fresh.getId(), ContentType.DAILY, null, "刚送审", List.of()));
        ContentPost stalePost = posts.save(
                ContentPost.publish(stale.getId(), ContentType.DAILY, null, "挂了两天", List.of()));
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, status, "
                + "priority, created_at, updated_at) "
                + "VALUES (?, 'CONTENT_POST', now(), 'PENDING', 'P1', now(), now())", freshPost.getId());
        jdbc.update("INSERT INTO manual_review_queue (content_id, content_type, submitted_at, status, "
                + "priority, created_at, updated_at) VALUES (?, 'CONTENT_POST', "
                + "now() - interval '2 days', 'PENDING', 'P1', now(), now())", stalePost.getId());

        assertThat(submissionRowOf(fresh.getId()).overdue()).isFalse();
        assertThat(submissionRowOf(stale.getId()).overdue()).isTrue();
    }

    private UnifiedTicketRow submissionRowOf(long userId) {
        return rowOf(TicketType.CONTENT_SUBMISSION, userId);
    }
}
