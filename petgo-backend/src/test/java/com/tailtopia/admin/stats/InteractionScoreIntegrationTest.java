package com.tailtopia.admin.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.stats.dto.InteractionScoreRow;
import com.tailtopia.admin.stats.dto.StatsScope;
import com.tailtopia.admin.stats.service.InteractionScoreService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * L1 集成：内容互动积分与双口径统计（V1.1.6 Story 15.1 · AB-3G）。
 *
 * <h2>🔴 本类最重要的一条</h2>
 * {@link #theTwoScopesGiveDifferentAnswersOnTheSameData()} ——
 * 两个口径回答的是**不同问题**，不是同一个数的两种算法。
 * 用"老帖子被重新带火"这个场景证明它们**必须**给出不同结果：
 * 口径B 看得见，口径A 完全看不见。
 *
 * <h2>🛡 AC4 的边界</h2>
 * {@link #diaryThatWasNotSyncedPubliclyIsExcludedFromBothScopes()} ——
 * 未同步为公开的 Diary 的赞评<b>既不代表内容质量也不代表公开活跃度</b>，
 * 且不可能成为装饰标签候选（装饰标签的意义在于公开曝光）。
 */
class InteractionScoreIntegrationTest extends ApiIntegrationTest {

    /** 与服务层同一时区口径。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private InteractionScoreService stats;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentLikeRepository likes;

    @Autowired
    private CommentRepository comments;

    @Autowired
    private AdminAccountRepository adminAccounts;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long adminId() {
        long n = SEQ.incrementAndGet();
        return adminAccounts.save(AdminAccount.newSuperAdmin(
                "stats-" + n + "@tailtopia.test", "积分测试员", "{bcrypt}x")).getId();
    }

    private Authentication auth(AdminAccountType type, String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "statsview-" + n + "@tailtopia.test", "积分查看员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), type);
        if (type == AdminAccountType.SUPER_ADMIN) {
            return new TestingAuthenticationToken(principal, null,
                    new java.util.ArrayList<>(principal.getAuthorities()));
        }
        List<GrantedAuthority> auths = new java.util.ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private ContentPost publicPost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
    }

    private ContentPost privateDiary(long authorId, String text) {
        ContentPost p = ContentPost.publish(authorId, ContentType.GROWTH_MOMENT, null, text,
                List.of());
        p.setVisibility(ContentVisibility.PRIVATE);
        return posts.save(p);
    }

    /**
     * 把内容的发布时间挪到 n 天前。
     *
     * <p>⚠️ 走 SQL：{@code created_at} 由实体在创建时自己写，从外面改会被下一次 save 覆盖。
     */
    private void agePost(long postId, int days) {
        jdbc.update("update content_posts set created_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now()
                        .minus(days, java.time.temporal.ChronoUnit.DAYS)), postId);
    }

    private void ageLike(long postId, int days) {
        jdbc.update("update content_likes set created_at = ? where post_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now()
                        .minus(days, java.time.temporal.ChronoUnit.DAYS)), postId);
    }

    private void like(ContentPost p, int n) {
        for (int i = 0; i < n; i++) {
            likes.save(ContentLike.of(p.getId(), newUser().getId()));
        }
    }

    private void comment(ContentPost p, int n) {
        for (int i = 0; i < n; i++) {
            comments.save(Comment.create(p.getId(), null, newUser().getId(),
                    "评论-" + SEQ.incrementAndGet()));
        }
    }

    /**
     * 查榜，**按作者筛**。
     *
     * <p>🔴 <b>必须按作者筛</b>：库是共享的、不回滚，里面已经有 50 条分数 ≥15 的存量内容 ——
     * 不筛的话本用例造的 2~15 分数据会被挤出前 50 页，断言全部找不到行。
     * 第一版没筛，红了 6 条；那不是功能坏了，是**又一次把共享状态当独占状态**。
     */
    private List<InteractionScoreRow> rankOf(StatsScope scope, long authorId, int daysBack) {
        LocalDate today = LocalDate.now(WIB);
        return stats.rank(scope, today.minusDays(daysBack), today, authorId, 0);
    }

    private InteractionScoreRow find(List<InteractionScoreRow> rows, long postId) {
        return rows.stream().filter(r -> r.postId() == postId).findFirst().orElse(null);
    }

    // ——————————————————— AC1 公式 ———————————————————

    /** 积分 = 赞 × 1 + 评 × 5。 */
    @Test
    void theScoreFormulaIsLikesPlusFiveTimesComments() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "算分的-" + SEQ.incrementAndGet());
        like(p, 3);
        comment(p, 2);

        InteractionScoreRow row = find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1),
                p.getId());

        assertThat(row).isNotNull();
        assertThat(row.likes()).isEqualTo(3);
        assertThat(row.comments()).isEqualTo(2);
        assertThat(row.score()).isEqualTo(3 + 2 * 5);
    }

    /**
     * ⚠️ 赞与评**各自子查询**，不 join 两张表。
     *
     * <p>两个一对多 join 在一起会让计数相乘（3 赞 × 2 评 = 6/6）——
     * 而那种错<b>看起来只是"数偏大"，不会报错</b>。本例的 3/2 恰好能抓住它（相乘会变 6/6）。
     */
    @Test
    void likesAndCommentsAreCountedSeparatelyNotMultiplied() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "防相乘的-" + SEQ.incrementAndGet());
        like(p, 3);
        comment(p, 2);

        InteractionScoreRow row = find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1),
                p.getId());

        assertThat(row.likes()).as("join 相乘会变成 6").isEqualTo(3);
        assertThat(row.comments()).as("join 相乘会变成 6").isEqualTo(2);
    }

    /** 默认按积分降序。 */
    @Test
    void resultsAreSortedByScoreDescending() {
        User author = newUser();
        ContentPost low = publicPost(author.getId(), "低分-" + SEQ.incrementAndGet());
        ContentPost high = publicPost(author.getId(), "高分-" + SEQ.incrementAndGet());
        like(low, 2);
        comment(high, 3);

        List<InteractionScoreRow> rows = rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1);
        int lowIdx = rows.stream().map(InteractionScoreRow::postId).toList().indexOf(low.getId());
        int highIdx = rows.stream().map(InteractionScoreRow::postId).toList().indexOf(high.getId());

        assertThat(highIdx).isGreaterThanOrEqualTo(0);
        assertThat(highIdx).as("15 分应排在 2 分之前").isLessThan(lowIdx);
    }

    /** 软删的评论不计分 —— 它已经不在公开视图里了。 */
    @Test
    void softDeletedCommentsDoNotCount() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "有删评的-" + SEQ.incrementAndGet());
        comment(p, 2);
        var all = comments.findByPostIdAndDeletedAtIsNull(p.getId());
        all.get(0).softDelete();
        comments.save(all.get(0));

        assertThat(find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1), p.getId())
                .comments()).isEqualTo(1);
    }

    // ——————————————————— 🔴 AC2 双口径给出不同答案 ———————————————————

    /**
     * 🔴 <b>两个口径必须给出不同结果</b>（AC2）。
     *
     * <p>场景就是 AC2 点名的那个：**老帖子被重新带火**。
     * 一条 30 天前发的帖子，本周被点了 5 个赞：
     * <ul>
     *   <li>口径A（按发布时间筛本周）⇒ <b>看不到它</b>，因为它不是本周发的</li>
     *   <li>口径B（不筛发布时间、只算本周新增互动）⇒ <b>看到它，5 分</b></li>
     * </ul>
     * 这正是"两个口径回答不同问题"的实证。
     */
    @Test
    void theTwoScopesGiveDifferentAnswersOnTheSameData() {
        User author = newUser();
        ContentPost oldPost = publicPost(author.getId(), "老帖被带火-" + SEQ.incrementAndGet());
        agePost(oldPost.getId(), 30);
        like(oldPost, 5); // 赞是"现在"发生的

        List<InteractionScoreRow> quality =
                rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 6);
        List<InteractionScoreRow> activity =
                rankOf(StatsScope.PLATFORM_ACTIVITY, author.getId(), 6);

        assertThat(find(quality, oldPost.getId()))
                .as("口径A 只看这段时间**发布**的帖子 —— 30 天前的不在其中").isNull();
        assertThat(find(activity, oldPost.getId()))
                .as("口径B 看得见老帖被重新带火").isNotNull()
                .satisfies(r -> assertThat(r.score()).isEqualTo(5));
    }

    /**
     * 口径A 的积分是**至今累计**，不是区间内新增。
     *
     * <p>本周发的帖子、上周（更早）的赞也要算进来 —— 它衡量的是"这条内容到今天为止多受欢迎"。
     */
    @Test
    void scopeAUsesAllTimeCountsNotWindowCounts() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "累计口径-" + SEQ.incrementAndGet());
        like(p, 2);
        ageLike(p.getId(), 40); // 赞发生在很久以前

        InteractionScoreRow row = find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1),
                p.getId());

        assertThat(row).isNotNull();
        assertThat(row.likes()).as("口径A 取至今累计 —— 区间外的赞也算").isEqualTo(2);
    }

    /**
     * 🔴 口径B 排除**区间内零互动**的帖子。
     *
     * <p>不排除的话，零互动的老帖会以 0 分挤满榜单，而这个口径要看的恰恰是"这段时间谁被互动了"。
     */
    @Test
    void scopeBExcludesPostsWithNoInteractionInTheWindow() {
        User author = newUser();
        ContentPost quiet = publicPost(author.getId(), "没人理的-" + SEQ.incrementAndGet());
        like(quiet, 1);
        ageLike(quiet.getId(), 40); // 那个赞在区间外

        assertThat(find(rankOf(StatsScope.PLATFORM_ACTIVITY, author.getId(), 6), quiet.getId()))
                .as("区间内零互动不该占榜位").isNull();
    }

    // ——————————————————— 🛡 AC4 未同步的 Diary 不纳入 ———————————————————

    /**
     * 🛡 <b>未同步为公开的 Diary 不进任一口径</b>（AC4）。
     *
     * <p>它们只在作者自己的成长档案时间线里展示、不在公开 Feed 流通 ——
     * 其赞/评量**既不代表内容质量也不代表平台公开活跃度**，
     * 且不可能成为装饰标签候选（装饰标签的意义在于公开曝光）。
     */
    @Test
    void diaryThatWasNotSyncedPubliclyIsExcludedFromBothScopes() {
        User author = newUser();
        ContentPost hidden = privateDiary(author.getId(), "没同步的日记-" + SEQ.incrementAndGet());
        like(hidden, 9);
        comment(hidden, 9);

        assertThat(find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1), hidden.getId()))
                .as("🛡 口径A 不该有它").isNull();
        assertThat(find(rankOf(StatsScope.PLATFORM_ACTIVITY, author.getId(), 1), hidden.getId()))
                .as("🛡 口径B 也不该有它").isNull();
    }

    /** ⚠️ 同步为公开的 Diary **要**纳入 —— 排除的判据是可见性，不是内容类型。 */
    @Test
    void publiclySyncedDiaryIsIncluded() {
        User author = newUser();
        ContentPost shared = posts.save(ContentPost.publish(author.getId(),
                ContentType.GROWTH_MOMENT, null, "同步了的日记-" + SEQ.incrementAndGet(), List.of()));
        like(shared, 4);

        assertThat(find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1), shared.getId()))
                .as("判据是可见性，不是类型").isNotNull();
    }

    /** 已删除的内容退出统计（沿用既有的已删内容处理规则）。 */
    @Test
    void deletedContentDropsOutOfTheStats() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "会被删的-" + SEQ.incrementAndGet());
        like(p, 3);
        p.softDelete();
        posts.save(p);

        assertThat(find(rankOf(StatsScope.CONTENT_QUALITY, author.getId(), 1), p.getId()))
                .isNull();
    }

    // ——————————————————— 区间边界 ———————————————————

    /**
     * 🔴 结束日**含当天**（半开区间 {@code [from 00:00, to+1 00:00)}）。
     *
     * <p>写成闭区间会漏掉结束日当天 00:00 之后的全部互动 ——
     * 而运营选"到今天"时，今天恰恰是数据最多的一天。
     */
    @Test
    void theEndDateIsInclusive() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "今天发的-" + SEQ.incrementAndGet());
        like(p, 1);
        LocalDate today = LocalDate.now(WIB);

        // 起止都选今天。
        assertThat(find(stats.rank(StatsScope.CONTENT_QUALITY, today, today, author.getId(), 0),
                p.getId()))
                .as("起止都是今天，今天发的必须在里面").isNotNull();
    }

    /** 起止颠倒被拒（而不是静默返回空表 —— 空表会让运营以为"这段时间没数据"）。 */
    @Test
    void aReversedDateRangeIsRejected() {
        LocalDate today = LocalDate.now(WIB);

        assertThatThrownBy(() ->
                        stats.rank(StatsScope.CONTENT_QUALITY, today, today.minusDays(3), null, 0))
                .isInstanceOf(AppException.class);
    }

    // ——————————————————— AC1 导出 / AC6 双权限 ———————————————————

    /** 导出带表头与数据行；⚠️ 服务层写审计，所以它不能是 readOnly 事务。 */
    @Test
    void exportProducesCsvWithHeaderAndRows() {
        User author = newUser();
        ContentPost p = publicPost(author.getId(), "要导出的-" + SEQ.incrementAndGet());
        like(p, 2);
        LocalDate today = LocalDate.now(WIB);

        String csv = stats.exportCsv(StatsScope.CONTENT_QUALITY, today.minusDays(1), today,
                author.getId(), adminId());

        assertThat(csv).startsWith("内容ID,类型,作者ID,正文摘要,发布时间,点赞,评论,互动积分");
        assertThat(csv).contains(String.valueOf(p.getId()));
    }

    /** ⚠️ 带逗号的正文要被引号包住，否则整行列数错开。 */
    @Test
    void csvQuotesBodiesContainingCommas() {
        User author = newUser();
        publicPost(author.getId(), "有,逗号,的正文-" + SEQ.incrementAndGet());
        LocalDate today = LocalDate.now(WIB);

        String csv = stats.exportCsv(StatsScope.CONTENT_QUALITY, today.minusDays(1), today,
                author.getId(), adminId());

        assertThat(csv).contains("\"有,逗号,的正文");
    }

    /** 🛡 只有查看权限 ⇒ 能看榜，**不能导出**（AC6 双权限码）。 */
    @Test
    void viewPermissionAloneCannotExport() throws Exception {
        Authentication viewer = auth(AdminAccountType.STAFF, AdminPermissions.CONTENT_STATS_VIEW);
        LocalDate today = LocalDate.now(WIB);

        mvc.perform(get("/admin/content-stats").with(authentication(viewer)))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/content-stats/export").with(authentication(viewer))
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString()))
                .andExpect(status().isForbidden());
    }

    /** 🛡 没有查看权限连榜都看不到。 */
    @Test
    void withoutTheViewPermissionThePageIsForbidden() throws Exception {
        mvc.perform(get("/admin/content-stats")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    /** 有导出权限 ⇒ 拿得到 CSV。 */
    @Test
    void exportPermissionYieldsCsv() throws Exception {
        LocalDate today = LocalDate.now(WIB);

        String body = mvc.perform(get("/admin/content-stats/export")
                        .with(authentication(auth(AdminAccountType.STAFF,
                                AdminPermissions.CONTENT_STATS_EXPORT)))
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("互动积分");
    }

    /** 页面上要说清两个口径分别在算什么 —— 否则运营对着数字猜。 */
    @Test
    void thePageExplainsWhatEachScopeMeasures() throws Exception {
        String html = mvc.perform(get("/admin/content-stats")
                        .with(authentication(auth(AdminAccountType.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("口径A").contains("口径B");
        // 🛡 AC7：界面上写明它与推荐算法是两套东西，免得有人去"对齐"。
        assertThat(html).contains("推荐算法");
    }
}
