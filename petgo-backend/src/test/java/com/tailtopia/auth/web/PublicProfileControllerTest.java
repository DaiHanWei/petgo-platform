package com.tailtopia.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.PublicProfileResponse;
import com.tailtopia.auth.dto.UserTagView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0（mock 服务，无 Spring MVC / 无 DB）：公开主页端点
 * （V1.3.0 batch-b1 Story 2.1 · AC1/AC5 + Story 2.2 · AC1/AC2）。
 *
 * <p>入口收口 / 操作抽屉 / 收尾行为在 App 侧；这里守的是
 * <b>投影字段 + 注销匿名化 + 拉黑拦截 + viewer 解析 + 两个计数的口径</b>。
 */
class PublicProfileControllerTest {

    private AccountQueryService accounts;
    private ContentService content;
    private FeedService feed;
    private UserHideRelationReader hideRelations;
    private PublicProfileController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        content = mock(ContentService.class);
        feed = mock(FeedService.class);
        hideRelations = mock(UserHideRelationReader.class);
        controller = new PublicProfileController(accounts, content, feed, hideRelations);
    }

    private static Jwt guest() {
        return null;
    }

    /** ⚠️ {@code role=USER} 不能省 —— 缺了它 viewerId 返回 null，拉黑守卫整段跳过（测试会假绿）。 */
    private static Jwt viewer(long userId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("role", "USER")
                .build();
    }

    /** 兽医 token：{@code sub=vetId}，与 {@code users.id} 是两个会碰撞的命名空间。 */
    private static Jwt vet(long vetId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(vetId))
                .claim("role", "VET")
                .build();
    }

    // ===== AC1 =====

    /** AC1：头像 / 昵称 / 标签 / **加入时间** / 签名 / 发帖数 / 本人视角标识。 */
    @Test
    void activeUserReturnsIdentityJoinedAtAndPostCount() {
        Instant joined = Instant.parse("2025-03-04T05:06:07Z");
        UserTagView tag = new UserTagView("KOL", "Kreator", "⭐", "Kreator pilihan", "#F6A609");
        stubActive(9L, "Rina", "https://cdn/r.jpg", List.of(tag), joined);
        when(accounts.activeSignatureOf(9L)).thenReturn(Optional.of("爱猫的人运气都不会太差"));
        when(content.countPublicPostsByAuthor(9L)).thenReturn(3L);
        when(content.sumLikesOnPublicPostsByAuthor(9L)).thenReturn(342L);

        PublicProfileResponse r = controller.profile(guest(), 9L);

        assertThat(r.isDeactivated()).isFalse();
        assertThat(r.nickname()).isEqualTo("Rina");
        assertThat(r.avatarUrl()).isEqualTo("https://cdn/r.jpg");
        assertThat(r.signature()).isEqualTo("爱猫的人运气都不会太差");
        assertThat(r.tags()).extracting(UserTagView::code).containsExactly("KOL");
        assertThat(r.postCount()).isEqualTo(3L);
        // Story 2.2 · AC2：获赞总数是本批次新补的字段（发帖总数是复用的）。
        assertThat(r.likeCount()).isEqualTo(342L);
        // 🔴 加入时间是本批次新补的字段，取 users.created_at。
        assertThat(r.joinedAt()).isEqualTo(joined);
        assertThat(r.self()).isFalse();
    }

    /**
     * 🔴 {@code self} 由**服务端**算：让客户端拿本地 id 自己比，两边口径迟早会漂
     * （Story 2.4 靠它切两种视角）。
     */
    @Test
    void ownProfileIsMarkedSelf() {
        stubActive(9L, "Rina", null, List.of(), Instant.parse("2025-03-04T05:06:07Z"));
        when(accounts.activeSignatureOf(9L)).thenReturn(Optional.empty());
        when(content.countPublicPostsByAuthor(9L)).thenReturn(0L);

        assertThat(controller.profile(viewer(9L), 9L).self()).isTrue();
        assertThat(controller.profile(viewer(5L), 9L).self()).isFalse();
    }

    /** 游客一律不查隐藏关系，{@code reported} 为 null（NON_NULL 会把这个键整个省略）。 */
    @Test
    void guestNeverConsultsHideRelationsAndGetsNoReportedKey() {
        stubActive(9L, "Rina", null, List.of(), Instant.EPOCH);
        when(accounts.activeSignatureOf(9L)).thenReturn(Optional.empty());
        when(content.countPublicPostsByAuthor(9L)).thenReturn(0L);

        PublicProfileResponse r = controller.profile(guest(), 9L);

        assertThat(r.reported()).isNull();
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
        verify(hideRelations, never()).isReported(anyLong(), anyLong());
    }

    /**
     * 🔴 兽医 token 按**游客**投影。
     *
     * <p>本端点 permitAll，兽医 token 也进得来；它的 {@code sub=vetId} 与 {@code users.id}
     * 会碰撞 —— 拿它查 {@code isBlocked}/{@code isReported} 就是用无关用户的隐藏关系做判断。
     */
    @Test
    void aVetTokenIsTreatedAsAGuest() {
        stubActive(9L, "Rina", null, List.of(), Instant.EPOCH);
        when(accounts.activeSignatureOf(9L)).thenReturn(Optional.empty());
        when(content.countPublicPostsByAuthor(9L)).thenReturn(0L);

        PublicProfileResponse r = controller.profile(vet(9L), 9L);

        assertThat(r.self()).as("vetId 撞上 users.id 时不能被当成本人").isFalse();
        assertThat(r.reported()).isNull();
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    // ===== AC5 注销 =====

    /** AC5 / NFR-8：注销 → 昵称 / 头像 / 签名 / 标签 / **加入时间**一个都不给。 */
    @Test
    void deactivatedUserLeaksNoIdentityAtAll() {
        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        PublicProfileResponse r = controller.profile(guest(), 8L);

        assertThat(r.isDeactivated()).isTrue();
        assertThat(r.nickname()).isNull();
        assertThat(r.avatarUrl()).isNull();
        assertThat(r.signature()).isNull();
        assertThat(r.tags()).isEmpty();
        // 「这个账号是 2024 年注册的」同样是身份信息 —— 注销的含义是这个人站内不再可识别。
        assertThat(r.joinedAt()).isNull();
        // 不查发布数、不查签名、不查 users 行（不暴露信息，也不白打三次库）。
        assertThat(r.likeCount()).isZero();
        verify(content, never()).countPublicPostsByAuthor(anyLong());
        verify(content, never()).sumLikesOnPublicPostsByAuthor(anyLong());
        verify(accounts, never()).activeSignatureOf(anyLong());
        verify(accounts, never()).findUserById(anyLong());
    }

    /**
     * 🔴 **从没存在过的 id 与已注销用户：逐字段同一个响应**。
     *
     * <p>能区分这两者，就等于给了一个按 id 遍历、确认谁注册过的枚举口子。
     * <p>⚠️ 这里 stub 的是 {@link AccountQueryService#findAuthorViews} 的**真实行为** ——
     * 它对查不到的 id **补齐匿名投影**，所以「map 里没有这个 key」是一个线上根本到不了的状态，
     * 拿它去断言 404 是**假绿**（早先版本就是这么写的）。
     */
    @Test
    void anUnknownIdIsIndistinguishableFromADeactivatedUser() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(404L, AuthorView.anonymized(404L)));

        PublicProfileResponse unknown = controller.profile(guest(), 404L);

        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of(8L, AuthorView.anonymized(8L)));
        PublicProfileResponse deactivated = controller.profile(guest(), 8L);

        assertThat(unknown).isEqualTo(deactivated);
        assertThat(unknown.isDeactivated()).isTrue();
    }

    /**
     * 🛡 万一 {@code findAuthorViews} 将来不再补齐缺失 id，这里也不能 NPE 成 500
     * （顺带把"查了个不存在的 id"变成一条堆栈日志）。
     */
    @Test
    void aMissingMapEntryFallsBackToTheAnonymizedProjectionInsteadOfNpe() {
        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of());

        assertThat(controller.profile(guest(), 404L).isDeactivated()).isTrue();
    }

    // ===== 拉黑拦截（FR-94 第 4 条 / AD-11）=====

    /** 主动拉黑者不可进入对方主页：拦在取数之前，**一个展示字段都不查**。 */
    @Test
    void blockedTargetThrows403AndTouchesNoProfileData() {
        when(hideRelations.isBlocked(5L, 9L)).thenReturn(true);

        assertThatThrownBy(() -> controller.profile(viewer(5L), 9L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ex = (AppException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
                });

        verify(accounts, never()).findAuthorViews(anyList());
        verify(accounts, never()).activeSignatureOf(anyLong());
        verify(content, never()).countPublicPostsByAuthor(anyLong());
        verify(content, never()).sumLikesOnPublicPostsByAuthor(anyLong());
    }

    // ===== Story 2.2：内容区端点 =====

    /**
     * 🔴 内容区端点**自己也要拦拉黑**。
     *
     * <p>只在 {@code /profile} 上拦，等于留了一个「绕过主页直接拉他内容列表」的口子 ——
     * 而 FR-94 第 4 条要挡的就是「主动拉黑者不该再看到对方」。
     */
    @Test
    void thePostsEndpointBlocksTheSameWayTheProfileDoes() {
        when(hideRelations.isBlocked(5L, 9L)).thenReturn(true);

        assertThatThrownBy(() -> controller.posts(viewer(5L), 9L, null))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ex = (AppException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
                });

        verify(feed, never()).userPublicPosts(anyLong(), any(), any());
    }

    /** 游客（无 viewer）照常读得到，且**不查隐藏关系**。 */
    @Test
    void aGuestReadsThePostsWithoutAnyViewerScopedLookup() {
        FeedPageResponse page = new FeedPageResponse(List.of(), null, false, null);
        when(feed.userPublicPosts(9L, null, null)).thenReturn(page);

        assertThat(controller.posts(guest(), 9L, null)).isSameAs(page);
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /** viewer 与 cursor 原样透传（viewer 只影响「我赞过没」，**不影响可见范围**）。 */
    @Test
    void viewerAndCursorArePassedThrough() {
        controller.posts(viewer(5L), 9L, "c2");

        verify(feed).userPublicPosts(9L, 5L, "c2");
    }

    /** 🔴 兽医 token 在内容区同样按游客走（{@code sub=vetId} 与 {@code users.id} 会碰撞）。 */
    @Test
    void aVetTokenReadsThePostsAsAGuest() {
        controller.posts(vet(5L), 9L, null);

        verify(feed).userPublicPosts(9L, null, null);
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /**
     * ⚠️ **只举报过、未主动拉黑 → 照常返回主页**，并带 {@code reported=true}。
     *
     * <p>写成「存在隐藏关系即拦」会把举报隐藏一并拦掉 —— 主页上的「已举报」状态
     * 与重复举报入口（AC3 的抽屉）当场作废。
     */
    @Test
    void reportOnlyTargetStillOpensAndIsMarkedReported() {
        when(hideRelations.isBlocked(5L, 9L)).thenReturn(false);
        when(hideRelations.isReported(5L, 9L)).thenReturn(true);
        stubActive(9L, "Rina", null, List.of(), Instant.EPOCH);
        when(accounts.activeSignatureOf(9L)).thenReturn(Optional.empty());
        when(content.countPublicPostsByAuthor(9L)).thenReturn(3L);

        PublicProfileResponse r = controller.profile(viewer(5L), 9L);

        assertThat(r.reported()).isTrue();
        assertThat(r.nickname()).isEqualTo("Rina");
    }

    private void stubActive(long userId, String nickname, String avatarUrl, List<UserTagView> tags,
            Instant joinedAt) {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(userId, new AuthorView(userId, nickname, avatarUrl, false, tags)));
        User user = mock(User.class);
        when(user.getCreatedAt()).thenReturn(joinedAt);
        when(accounts.findUserById(userId)).thenReturn(Optional.of(user));
    }
}
