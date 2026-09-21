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
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.visitor.PublicProfilePetController;
import com.tailtopia.profile.visitor.VisitorProjectionService;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0：**被拉黑者看拉黑者的主页**（V1.3.0 batch-b1 Story 2.5 · FR-118.5）。
 *
 * <h2>🔴 本文件守的是一件事：两种情况必须给出同一个响应</h2>
 * ①「这个人真的没发过公开内容」与 ②「这个人拉黑了我」——
 * 服务端<b>逐字段一致</b>，客户端因此压根没有"被拉黑"这个概念。
 * <p>两者只要有任何差别（多一个标记字段、计数没归零、状态码不同……），
 * 用户一对比就能推断出自己被拉黑，而整条 FR-118.5 的目的正是不让他确认这件事、进而去闹。
 *
 * <h2>AC6：这是对 V1.1.4 FR-94「被拉黑方完全无感」的有意例外，且仅限主页维度</h2>
 * Feed 与评论区<b>一字不改</b>：被拉黑者照常看得到对方的内容与互动。
 * 1.1.4 的文档不回改，以 FR-118.5 为准。
 */
class BlockedViewerProfileTest {

    private static final long VIEWER = 5L;
    private static final long OWNER = 9L;

    private AccountQueryService accounts;
    private ContentService content;
    private FeedService feed;
    private UserHideRelationReader hideRelations;
    private PublicProfileController profileController;

    private ProfileService profiles;
    private VisitorProjectionService visitors;
    private PublicProfilePetController petCard;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        content = mock(ContentService.class);
        feed = mock(FeedService.class);
        hideRelations = mock(UserHideRelationReader.class);
        profileController = new PublicProfileController(accounts, content, feed, hideRelations);

        profiles = mock(ProfileService.class);
        visitors = mock(VisitorProjectionService.class);
        petCard = new PublicProfilePetController(profiles, visitors, accounts, hideRelations);

        when(accounts.findAuthorViews(anyList())).thenReturn(
                Map.of(OWNER, new AuthorView(OWNER, "Rani", "https://cdn/r.jpg", false, List.of())));
        when(accounts.activeSignatureOf(OWNER)).thenReturn(Optional.of("Pecinta kucing"));
        User user = mock(User.class);
        when(user.getCreatedAt()).thenReturn(Instant.parse("2026-03-04T05:06:07Z"));
        when(accounts.findUserById(OWNER)).thenReturn(Optional.of(user));
    }

    private static Jwt viewer(long userId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId)).claim("role", "USER").build();
    }

    // ===== AC1 / AC2：主页 =====

    /**
     * 🔴 **被拉黑者拿到的主页，与「这个人真的没发过公开内容」逐字段一致**。
     *
     * <p>身份区照常（头像 / 昵称 / 加入时间 / 签名都在，AC1 明说不做模糊、不隐藏），
     * 两个计数归零 —— 因为那正是一个没发过公开内容的人的样子。
     */
    @Test
    void aBlockedViewerGetsExactlyTheSameProfileAsSomeoneWithNoPublicContent() {
        // ① 对方拉黑了我。
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(false); // 我没拉黑他
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);  // 他拉黑了我
        when(content.countPublicPostsByAuthor(OWNER)).thenReturn(18L);
        when(content.sumLikesOnPublicPostsByAuthor(OWNER)).thenReturn(342L);
        PublicProfileResponse blocked = profileController.profile(viewer(VIEWER), OWNER);

        // ② 一个真的没发过公开内容的人（没有任何拉黑关系）。
        setUp();
        when(hideRelations.isBlocked(anyLong(), anyLong())).thenReturn(false);
        when(content.countPublicPostsByAuthor(OWNER)).thenReturn(0L);
        when(content.sumLikesOnPublicPostsByAuthor(OWNER)).thenReturn(0L);
        PublicProfileResponse empty = profileController.profile(viewer(VIEWER), OWNER);

        assertThat(blocked)
                .as("🔴 两者可区分，FR-118.5 这条设计就作废了")
                .isEqualTo(empty);
        // 身份区**照常展示**（AC1：不做模糊、不隐藏）。
        assertThat(blocked.nickname()).isEqualTo("Rani");
        assertThat(blocked.avatarUrl()).isEqualTo("https://cdn/r.jpg");
        assertThat(blocked.joinedAt()).isEqualTo(Instant.parse("2026-03-04T05:06:07Z"));
        assertThat(blocked.signature()).isEqualTo("Pecinta kucing");
        // 计数归零 —— 写着「18 postingan」配一个空网格，用户一眼就知道自己被拉黑了。
        assertThat(blocked.postCount()).isZero();
        assertThat(blocked.likeCount()).isZero();
        // 🔴 响应里**没有任何"被拉黑"标识**：客户端压根没有这个概念。
        assertThat(blocked.isDeactivated()).isFalse();
    }

    /** 🛡 被拉黑时**连计数都不查** —— 查了再丢弃只是白打两次库。 */
    @Test
    void aBlockedViewerCostsNoCountQueries() {
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);

        profileController.profile(viewer(VIEWER), OWNER);

        verify(content, never()).countPublicPostsByAuthor(anyLong());
        verify(content, never()).sumLikesOnPublicPostsByAuthor(anyLong());
    }

    /**
     * 🔴 内容区：**空页**，且信封与「真的没有公开内容」逐字段一致
     * （空 items + hasMore=false + 无 nextCursor + 无 rankMode）。
     */
    @Test
    void aBlockedViewerGetsAnEmptyPageIdenticalToTheNoContentEnvelope() {
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);

        FeedPageResponse page = profileController.posts(viewer(VIEWER), OWNER, null);

        assertThat(page).isEqualTo(new FeedPageResponse(List.of(), null, false, null));
        // 🛡 一次库都不打：形状本来就与空结果一样，查了再丢弃没有意义。
        verify(feed, never()).userPublicPosts(anyLong(), any(), any());
    }

    /**
     * 🔴 **畸形游标对两种人给同一个错**（code-review 2026-09-15）。
     *
     * <p>短路如果发生在解游标之前，一个 `?cursor=@@@` 对普通访客是 422、
     * 对被拉黑者却是 200 空页 —— 两个请求就能问出「我是不是被他拉黑了」，
     * 而这条设计的全部意义就是别让他确认。
     */
    @Test
    void aMalformedCursorFailsTheSameWayForABlockedViewer() {
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);

        assertThatThrownBy(() -> profileController.posts(viewer(VIEWER), OWNER, "@@@not-a-cursor"))
                .as("被拉黑者不能因为「反正也是空页」就吃下一个非法游标")
                .isInstanceOf(RuntimeException.class);
    }

    /** 🔴 宠物卡：**204**，与「他没养宠物」同一个响应。 */
    @Test
    void aBlockedViewerSeesNoPetCardJustLikeSomeoneWithoutAPet() {
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);

        var resp = petCard.pet(viewer(VIEWER), OWNER);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(resp.getBody()).isNull();
        // 连档案都不查。
        verify(profiles, never()).findByOwnerId(anyLong());
    }

    // ===== AC3：判定在服务端，且走既有的统一出口 =====

    /**
     * 🔴 判定用的是 {@link UserHideRelationReader#isBlocked} —— **既有的统一出口**，
     * 只是把两个参数掉过来（AD-7：四处共用一个出口，禁各写各的查询）。
     *
     * <p>⚠️ 同时钉住**方向**：`isBlocked(OWNER, VIEWER)` 是「他拉黑了我」，
     * 反过来是「我拉黑了他」（那一条走 403）。两个方向写反的表现是
     * 「我拉黑了谁，谁的主页就变空」—— 而他的主页本该我根本进不去。
     */
    @Test
    void theDecisionUsesTheSharedReaderAndTheDirectionMatters() {
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);

        profileController.profile(viewer(VIEWER), OWNER);

        verify(hideRelations).isBlocked(VIEWER, OWNER); // 我拉黑他？（403 那条）
        verify(hideRelations).isBlocked(OWNER, VIEWER); // 他拉黑我？（本 story 这条）
        // 只读这一个出口，不碰 isHidden（那个不分来源，会把举报隐藏一并算进来）。
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
    }

    /** 游客不可能被谁拉黑 —— 整段跳过，一次隐藏关系都不查（游客行为一字不变）。 */
    @Test
    void aGuestIsNeverTestedForBeingBlocked() {
        when(content.countPublicPostsByAuthor(OWNER)).thenReturn(18L);
        when(content.sumLikesOnPublicPostsByAuthor(OWNER)).thenReturn(342L);

        PublicProfileResponse r = profileController.profile(null, OWNER);

        assertThat(r.postCount()).isEqualTo(18L);
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /**
     * ⚠️ **只举报过、没拉黑 → 主页照常有内容**。
     *
     * <p>把判据写成 {@code isHidden}（不分来源）会把举报隐藏一并算进来：
     * 举报过我的人，他的主页在我这儿会莫名其妙变空。
     */
    @Test
    void aReportOnlyRelationDoesNotEmptyTheProfile() {
        when(hideRelations.isBlocked(anyLong(), anyLong())).thenReturn(false);
        when(content.countPublicPostsByAuthor(OWNER)).thenReturn(18L);
        when(content.sumLikesOnPublicPostsByAuthor(OWNER)).thenReturn(342L);

        assertThat(profileController.profile(viewer(VIEWER), OWNER).postCount()).isEqualTo(18L);
    }

    // ===== AC5：已注销 =====

    /** 已注销 → 统一「什么都不给」的投影，与不存在的 id 同一个响应（Story 2.1 建立，此处回归）。 */
    @Test
    void aDeactivatedOwnerLeaksNothing() {
        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of(OWNER, AuthorView.anonymized(OWNER)));

        PublicProfileResponse r = profileController.profile(viewer(VIEWER), OWNER);

        assertThat(r.isDeactivated()).isTrue();
        assertThat(r.nickname()).isNull();
        assertThat(r.avatarUrl()).isNull();
        assertThat(r.signature()).isNull();
        assertThat(r.joinedAt()).isNull();
        assertThat(r.postCount()).isZero();
        assertThat(r.likeCount()).isZero();
    }

    @SuppressWarnings("unused")
    private static PetProfile pet() {
        return PetProfile.create(OWNER, PetType.CAT, "Miu", null, null,
                LocalDate.of(2024, 6, 1), null, "tok");
    }
}
