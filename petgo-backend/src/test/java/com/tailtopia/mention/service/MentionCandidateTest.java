package com.tailtopia.mention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.mention.domain.MentionCandidate;
import com.tailtopia.mention.dto.MentionCandidateView;
import com.tailtopia.mention.repository.MentionCandidateRepository;
import com.tailtopia.social.read.UserHideRelationReader;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * L0：@ 候选集（V1.3.0 batch-b1 Story 3.1 · FR-119 · AD-10）。
 *
 * <h2>🔴 本文件的重心在 AC2（维护逻辑），不在查询</h2>
 * story 标题写的是「候选集」，容易被理解成"写个查询就行"。查询那部分很简单；
 * <b>难的是这张表怎么被喂饱、怎么不撑爆</b>。所以下面第一组用例钉的是
 * <b>写入方向</b>与<b>裁剪</b>，第二组才是取数。
 */
class MentionCandidateTest {

    private static final Instant AT = Instant.parse("2026-09-15T10:00:00Z");

    private MentionCandidateRepository repo;
    private MentionCandidateMaintenanceService maintenance;
    private MentionCandidateListener listener;

    private UserHideRelationReader hideRelations;
    private AccountQueryService accounts;
    private MentionCandidateQueryService query;

    @BeforeEach
    void setUp() {
        repo = mock(MentionCandidateRepository.class);
        maintenance = new MentionCandidateMaintenanceService(repo);
        listener = new MentionCandidateListener(maintenance);

        hideRelations = mock(UserHideRelationReader.class);
        accounts = mock(AccountQueryService.class);
        query = new MentionCandidateQueryService(repo, hideRelations, accounts);
    }

    // ===== AC2：写入时机与方向 =====

    /**
     * 🔴 **评论是双向的**：我评论了他 → 他进我的候选；同时也是「评论过我」→ 我进他的候选。
     *
     * <p>PRD §2.3 的口径里「评论过我」与「我评论过」两条都在。
     */
    @Test
    void aCommentPutsBothPeopleInEachOthersList() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 9L, null, AT, null));

        verify(repo).touch(5L, 9L, AT);
        verify(repo).touch(9L, 5L, AT);
    }

    /**
     * 🔴 **点赞是单向的**：只有「赞过我的」，**没有「我赞过的」**。
     *
     * <p>顺手写成双向的话，一个只会点赞、从不说话的人的候选集里会塞满他赞过的所有作者 ——
     * 而他跟那些人其实并没有"打过交道"。
     */
    @Test
    void aLikeOnlyPutsTheLikerIntoTheAuthorsList() {
        listener.onContentLiked(new ContentLikedEvent(1L, 5L, 9L, AT));

        verify(repo).touch(9L, 5L, AT);
        verify(repo, never()).touch(eq(5L), eq(9L), any());
    }

    /**
     * 🔴 **「与我互相回复对话过」= 二级回复时那第二对关系**。
     *
     * <p>少了它，在别人帖子下聊了半天的两个人互相 @ 不到 —— 而那恰恰是最需要 @ 的场景。
     */
    @Test
    void replyingToSomeoneElsesCommentPairsTheTwoOfThemToo() {
        // 5 在 9 的帖子下回复了 7 的评论。
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 9L, 7L, AT, null));

        verify(repo).touch(5L, 9L, AT);   // 我评论过（帖主）
        verify(repo).touch(9L, 5L, AT);   // 评论过我（帖主视角）
        verify(repo).touch(5L, 7L, AT);   // 与我互相回复对话过
        verify(repo).touch(7L, 5L, AT);
    }

    /**
     * ⚠️ 同一次互动的两个方向**用同一个时间戳**。
     *
     * <p>各自取 {@code now()} 会差出几微秒，排序上就成了两个"不同新旧"的人。
     */
    @Test
    void bothDirectionsShareOneTimestamp() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 9L, null, AT, null));

        verify(repo, times(2)).touch(anyLong(), anyLong(), eq(AT));
    }

    /** 🛡 自评 / 自赞不进候选集（库里那条 CHECK 也会拦，但不该让它走到数据库才报错）。 */
    @Test
    void interactingWithYourselfWritesNothing() {
        maintenance.recordOneWay(5L, 5L, AT);
        maintenance.recordMutual(5L, 5L, AT);

        verify(repo, never()).touch(anyLong(), anyLong(), any());
        verify(repo, never()).trimToNewest(anyLong(), anyInt());
    }

    // ===== AC2：裁剪 =====

    /**
     * 🔴 **写入时顺手淘汰**：每次记完互动就把该 owner 最新 N 条之外的删掉。
     *
     * <p>表的上界因此是**硬的**（行数 ≤ 用户数 × N，与互动量无关）。
     * 定时任务则意味着「两次扫描之间可以涨到任意大」，还多一个会挂掉的东西。
     */
    @Test
    void everyWriteTrimsTheOwnersListInPlace() {
        maintenance.recordOneWay(9L, 5L, AT);

        verify(repo).trimToNewest(9L, MentionCandidateMaintenanceService.KEEP_PER_OWNER);
    }

    /** 双向互动 → 两个 owner **各自**裁剪（只裁一边的话另一边会无界增长）。 */
    @Test
    void aMutualInteractionTrimsBothSides() {
        maintenance.recordMutual(5L, 9L, AT);

        verify(repo).trimToNewest(5L, MentionCandidateMaintenanceService.KEEP_PER_OWNER);
        verify(repo).trimToNewest(9L, MentionCandidateMaintenanceService.KEEP_PER_OWNER);
    }

    /**
     * 🔴 **每人留 50，对外只给 30** —— 差值是留给拉黑 / 注销排除的冗余。
     *
     * <p>表里正好只存 30 人时，其中 3 个被拉黑，用户就只剩 27 个候选。
     */
    @Test
    void theStoredCapLeavesHeadroomAboveTheServedCap() {
        assertThat(MentionCandidateMaintenanceService.KEEP_PER_OWNER)
                .as("留冗余才能保证拉黑排除后仍有 30 个可选")
                .isGreaterThan(MentionCandidateQueryService.MAX_CANDIDATES);
        assertThat(MentionCandidateQueryService.MAX_CANDIDATES).isEqualTo(30);
    }

    /**
     * 🔴 **裁剪与取数用同一把尺子排序**。
     *
     * <p>不一致的话，被裁掉的可能正是要显示的那一批。谓词写在 SQL / JPQL 里，
     * DB 才跑得到 —— L0 只能直接断言那两串查询。
     */
    @Test
    void trimAndReadOrderByTheSameKey() throws Exception {
        String trim = queryOf("trimToNewest");
        String read = queryOf("findRecent");
        assertThat(trim).contains("ORDER BY last_interacted_at DESC, id DESC");
        assertThat(read).contains("ORDER BY c.lastInteractedAt DESC, c.id DESC");
    }

    /**
     * 🔴 写入必须是 {@code ON CONFLICT DO UPDATE}，不能是 find-then-save。
     *
     * <p>两个人几乎同时互动会并发走到这里；find-then-save 在那一刻撞唯一约束，
     * 而约束异常穿出 repo 代理时**共享事务已被标记 rollback-only** ——
     * catch 了也救不回来，外层提交时 500（同 `UserHideRelationRepository#insertIfAbsent` 踩过的坑）。
     */
    @Test
    void theWriteIsAnUpsertNotAFindThenSave() throws Exception {
        assertThat(queryOf("touch"))
                .contains("ON CONFLICT (owner_id, candidate_id)")
                .contains("DO UPDATE SET last_interacted_at");
    }

    /** 注销级联（D1/D2）：**两个方向**都删 —— 只删 owner 侧他还会出现在别人的候选里。 */
    @Test
    void purgingAUserRemovesBothDirections() throws Exception {
        assertThat(queryOf("deleteAllForUser"))
                .contains("c.ownerId = :userId")
                .contains("c.candidateId = :userId");

        maintenance.purgeUser(7L);
        verify(repo).deleteAllForUser(7L);
    }

    /**
     * 🔴 **乱序到达时排序键不许倒退**（code-review 2026-09-15）。
     *
     * <p>写入走 `@Async` 线程池，同一对 (owner, candidate) 的两个事件完全可能乱序提交。
     * 无条件覆盖会把排序键**倒退回更旧的时间** —— 表现是一个刚跟你聊过的人在 @ 列表里
     * 莫名靠后，越过 50 条边界时甚至被当成"最旧的"直接淘汰。
     * <p>保序靠的是 SQL 里那个 `GREATEST`，只有 DB 跑得到，所以这里断言的是那串 SQL。
     */
    @Test
    void anOutOfOrderWriteCannotMoveTheSortKeyBackwards() throws Exception {
        assertThat(queryOf("touch"))
                .as("无条件覆盖会让迟到的旧事件把排序键推回过去")
                .contains("GREATEST(mention_candidates.last_interacted_at");
    }

    /**
     * 🔴 二级回复的**两对关系各包一层容错** —— 包在一起的话，第一对写失败就会把
     * 第二对整个跳过，而第二对正是「与我互相回复对话过」这条口径唯一的落点。
     */
    @Test
    void aFailureOnTheFirstPairDoesNotSkipTheConversationPair() {
        // 第一对（5↔9）写失败，第二对（5↔7）必须照写。
        when(repo.touch(eq(5L), eq(9L), any())).thenThrow(new IllegalStateException("boom"));

        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 9L, 7L, AT, null));

        verify(repo).touch(5L, 7L, AT);
        verify(repo).touch(7L, 5L, AT);
    }

    /**
     * 🛡 写候选集失败**不往外抛**：这张表是 comments / content_likes 的派生物，
     * 写丢一次的后果只是「那个人这次没进候选集」，重新互动一次就回来了。
     */
    @Test
    void aFailedWriteIsSwallowed() {
        when(repo.touch(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("boom"));

        listener.onContentLiked(new ContentLikedEvent(1L, 5L, 9L, AT));
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 9L, null, AT, null));
        // 没抛出来就算通过。
    }

    /** 监听器必须同时带 {@code @Async} 与 {@code @TransactionalEventListener}（两个都不能少）。 */
    @Test
    void theListenerIsAsyncAndRunsAfterCommit() throws Exception {
        for (String name : List.of("onContentLiked", "onContentCommented")) {
            Method m = findMethod(MentionCandidateListener.class, name);
            assertThat(m.getAnnotation(org.springframework.scheduling.annotation.Async.class))
                    .as("%s 少了 @Async —— 评论/点赞是热路径", name)
                    .isNotNull();
            assertThat(m.getAnnotation(
                    org.springframework.transaction.event.TransactionalEventListener.class))
                    .as("%s 少了 @TransactionalEventListener —— 互动事务回滚时不该留下候选集痕迹", name)
                    .isNotNull();
        }
    }

    // ===== AC3 / AC4 / AC5：取数 =====

    /** 最近互动的在前，最多 30 人。 */
    @Test
    void candidatesComeBackNewestFirstCappedAtThirty() {
        stubRows(40);
        stubAuthorsAllActive(40);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());

        List<MentionCandidateView> out = query.candidatesFor(1L);

        assertThat(out).hasSize(MentionCandidateQueryService.MAX_CANDIDATES);
        assertThat(out.get(0).userId()).isEqualTo(100L); // 仓储已按时间倒序给
        assertThat(out.get(0).nickname()).isEqualTo("u100");
    }

    /**
     * 🔴 **任一方向存在隐藏关系的双方互不出现**，且判定走**既有统一出口**（AD-7）。
     *
     * <p>只判单向的表现是「我拉黑了他，他还能 @ 到我」。
     */
    @Test
    void anyoneHiddenInEitherDirectionIsDropped() {
        stubRows(5);
        stubAuthorsAllActive(5);
        when(hideRelations.hiddenEitherWay(eq(1L), anyCollection())).thenReturn(Set.of(102L, 104L));

        assertThat(query.candidatesFor(1L)).extracting(MentionCandidateView::userId)
                .containsExactly(100L, 101L, 103L);
    }

    /** 已注销不进候选（被 @ 出来点进去是「用户不存在」）。 */
    @Test
    void deactivatedUsersAreDropped() {
        stubRows(3);
        Map<Long, AuthorView> authors = new HashMap<>();
        authors.put(100L, new AuthorView(100L, "u100", null, false, List.of()));
        authors.put(101L, AuthorView.anonymized(101L));
        authors.put(102L, new AuthorView(102L, "u102", null, false, List.of()));
        when(accounts.findAuthorViewsWithoutTags(anyList())).thenReturn(authors);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());

        assertThat(query.candidatesFor(1L)).extracting(MentionCandidateView::userId)
                .containsExactly(100L, 102L);
    }

    /**
     * 🔴 **AC4：一次取数只发三条查询，与候选人数无关**。
     *
     * <p>逐个判拉黑、逐个查昵称是这个接口最容易写坏的地方 ——
     * 它服务的是「打字时的即时交互」。
     */
    @Test
    void oneFetchCostsAConstantNumberOfQueries() {
        stubRows(50);
        stubAuthorsAllActive(50);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());

        query.candidatesFor(1L);

        verify(repo, times(1)).findRecent(eq(1L), any(Pageable.class));
        verify(hideRelations, times(1)).hiddenEitherWay(anyLong(), anyCollection());
        verify(accounts, times(1)).findAuthorViewsWithoutTags(anyList());
        // 🔴 用的是**不带标签**的那个重载：@ 列表一行只有头像 + 昵称，
        //    为 50 个人多查一次运营标签是白跑（code-review 2026-09-15）。
        verify(accounts, never()).findAuthorViews(anyList());
        // 逐条的那两个一次都不许出现。
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /** 🛡 没互动过的人（新用户）：空表直接返回，一次多余的查询都不发。 */
    @Test
    void someoneWithNoInteractionsCostsNoExtraQueries() {
        when(repo.findRecent(anyLong(), any(Pageable.class))).thenReturn(List.of());

        assertThat(query.candidatesFor(1L)).isEmpty();
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
        verify(accounts, never()).findAuthorViews(anyList());
    }

    /**
     * 🔴 **AC5：没有全局用户搜索**。
     *
     * <p>候选服务与端点**都不接受任何关键词 / 分页参数** —— 加一个 {@code keyword} 形参，
     * 这一刻它就变成了全局用户搜索接口，而搜索留在 1.6.0、**未前移**。
     */
    @Test
    void thereIsNoWayToPassAKeywordIntoTheCandidateApi() {
        for (Method m : MentionCandidateQueryService.class.getDeclaredMethods()) {
            if (!m.getName().equals("candidatesFor")) {
                continue;
            }
            assertThat(m.getParameterTypes())
                    .as("候选查询只接受 ownerId —— 多一个参数就是全局用户搜索的入口")
                    .containsExactly(long.class);
        }
        for (Method m : com.tailtopia.mention.web.MentionCandidateController.class
                .getDeclaredMethods()) {
            if (m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class) == null) {
                continue;
            }
            for (var p : m.getParameters()) {
                assertThat(p.getAnnotation(
                        org.springframework.web.bind.annotation.RequestParam.class))
                        .as("端点不得有任何查询参数（%s）", p.getName())
                        .isNull();
            }
        }
    }

    // ===== helpers =====

    private void stubRows(int n) {
        List<MentionCandidate> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(row(1L, 100L + i, AT.minusSeconds(i)));
        }
        when(repo.findRecent(anyLong(), any(Pageable.class))).thenReturn(rows);
    }

    private void stubAuthorsAllActive(int n) {
        Map<Long, AuthorView> authors = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long id = 100L + i;
            authors.put(id, new AuthorView(id, "u" + id, null, false, List.of()));
        }
        when(accounts.findAuthorViewsWithoutTags(anyList())).thenReturn(authors);
    }

    private static MentionCandidate row(long ownerId, long candidateId, Instant at) {
        MentionCandidate c = new MentionCandidate() {
        };
        set(c, "ownerId", ownerId);
        set(c, "candidateId", candidateId);
        set(c, "lastInteractedAt", at);
        return c;
    }

    private static void set(MentionCandidate c, String field, Object v) {
        try {
            var f = MentionCandidate.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(c, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MentionCandidate." + field + " 字段名变了，改这里", e);
        }
    }

    /** 取某个仓储方法上 {@code @Query} 的原文（谓词与排序只有 DB 跑得到，L0 只能这么钉）。 */
    private static String queryOf(String method) throws Exception {
        Method m = findMethod(MentionCandidateRepository.class, method);
        var q = m.getAnnotation(org.springframework.data.jpa.repository.Query.class);
        assertThat(q).as(method + " 应当带 @Query").isNotNull();
        return q.value();
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError(type.getSimpleName() + " 上找不到方法 " + name);
    }
}
