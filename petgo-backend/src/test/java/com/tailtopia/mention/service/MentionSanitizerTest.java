package com.tailtopia.mention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.CommentCreateRequest;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.mention.repository.MentionCandidateRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：@ 名单的落库形态（V1.3.0 batch-b1 Story 3.2 · AC4 / AC5 · AD-10 Rule 4）。
 *
 * <h2>本文件钉的是 AC4 的那句「🔴 存 userId 不存昵称」</h2>
 * 这条 story 的另一半（选择器浮层）在 App 侧，由
 * {@code petgo_app/test/content/mention_picker_test.dart} 覆盖。后端这一半只有两件事：
 * <ol>
 *   <li><b>字段确实存在且存的是 id</b> —— 用反射正向 + 反向钉死，防哪天有人"顺手"
 *       加一个 {@code mentionedNicknames} 把昵称也存一份（那一份第二天就是过期数据）；</li>
 *   <li><b>客户端提交的名单必须被服务端重洗一遍</b> —— 候选集端点只是 UI。</li>
 * </ol>
 */
class MentionSanitizerTest {

    private static final long ME = 1L;

    private AccountQueryService accounts;
    private UserHideRelationReader hideRelations;
    private MentionCandidateRepository candidates;
    private FollowReader follows;
    private MentionSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        hideRelations = mock(UserHideRelationReader.class);
        candidates = mock(MentionCandidateRepository.class);
        follows = mock(FollowReader.class);
        sanitizer = new MentionSanitizer(accounts, hideRelations, candidates, follows);
        // 默认：提交的人都在候选集里、与我互不关注、谁都没拉黑谁、所有人都有效。各用例按需覆盖。
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection()))
                .thenAnswer(inv -> List.copyOf(inv.<java.util.Collection<Long>>getArgument(1)));
        when(follows.followStates(anyLong(), anyCollection())).thenAnswer(inv -> states(
                inv.<java.util.Collection<Long>>getArgument(1), Map.of()));
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
        when(accounts.activeIdsAmong(anyCollection()))
                .thenAnswer(inv -> Set.copyOf(inv.<java.util.Collection<Long>>getArgument(0)));
    }

    /** 同 FollowReaderImpl：入参里每个 id 都有值，缺失的填 NONE。 */
    private static Map<Long, FollowState> states(Iterable<Long> ids, Map<Long, FollowState> known) {
        Map<Long, FollowState> out = new HashMap<>();
        for (Long id : ids) {
            out.put(id, known.getOrDefault(id, FollowState.NONE));
        }
        return out;
    }

    private void onlyInScopeVia(Map<Long, FollowState> rel) {
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection())).thenReturn(List.of());
        when(follows.followStates(anyLong(), anyCollection())).thenAnswer(inv -> states(
                inv.<java.util.Collection<Long>>getArgument(1), rel));
    }

    // ===== AC5：上限 5 人 =====

    @Test
    void 正好五人放行() {
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L, 4L, 5L, 6L)))
                .containsExactly(2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void 超过五人直接拒绝而不是截断() {
        // 🔴 截断的话用户以为 6 个人都 @ 到了，第 6 个永远收不到通知，且完全无感 ——
        // AC5 是产品规则（防骚扰），该给反馈。
        assertThatThrownBy(() -> sanitizer.sanitize(ME, List.of(2L, 3L, 4L, 5L, 6L, 7L)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("5");
    }

    @Test
    void 超限时不发任何查询() {
        // 上限是本地就能判死的，没理由先查三次库。
        assertThatThrownBy(() -> sanitizer.sanitize(ME, List.of(2L, 3L, 4L, 5L, 6L, 7L)))
                .isInstanceOf(AppException.class);
        verify(candidates, never()).findExistingCandidateIds(anyLong(), anyCollection());
        verify(follows, never()).followStates(anyLong(), anyCollection());
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
        verify(accounts, never()).activeIdsAmong(anyCollection());
    }

    @Test
    void 六个重复的同一个人也算超限() {
        // ⚠️ 先去重再判上限的话，这串畸形输入会被洗成合法的 1 个人悄悄放过去。
        assertThatThrownBy(() -> sanitizer.sanitize(ME, List.of(2L, 2L, 2L, 2L, 2L, 2L)))
                .isInstanceOf(AppException.class);
    }

    // ===== D-15：@ 不到范围外的人（服务端闸门；范围 = 互动候选 ∪ 我关注 ∪ 关注我）=====

    @Test
    void 三类都不是被静默剔除() {
        // 🔴 前端那个选择器只能点到范围内的人，但**接口自己必须拦** ——
        //    否则一个 for 循环遍历 userId 就成了对任意陌生人的定向推送口（Story 3.4 发通知）。
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection()))
                .thenReturn(List.of(3L));
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).containsExactly(3L);
    }

    @Test
    void 只是粉丝也放行() {
        onlyInScopeVia(Map.of(2L, new FollowState(false, true)));
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).containsExactly(2L);
    }

    @Test
    void 只是我关注的也放行() {
        onlyInScopeVia(Map.of(4L, new FollowState(true, false)));
        assertThat(sanitizer.sanitize(ME, List.of(3L, 4L))).containsExactly(4L);
    }

    @Test
    void 互动候选关注粉丝三类混合时都放行且保序() {
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection())).thenReturn(List.of(5L));
        when(follows.followStates(anyLong(), anyCollection())).thenAnswer(inv -> states(
                inv.<java.util.Collection<Long>>getArgument(1),
                Map.of(2L, new FollowState(false, true), 4L, new FollowState(true, true))));
        assertThat(sanitizer.sanitize(ME, List.of(4L, 9L, 2L, 5L))).containsExactly(4L, 2L, 5L);
    }

    @Test
    void 全是范围外的人时后两条查询都不发() {
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection()))
                .thenReturn(List.of());
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).isEmpty();
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
        verify(accounts, never()).activeIdsAmong(anyCollection());
    }

    @Test
    void 范围外同样是静默剔除不报错() {
        // 报错 = 把「这个 id 跟我有没有关系」做成一个可以逐个试探的问答口。
        when(candidates.findExistingCandidateIds(anyLong(), anyCollection()))
                .thenReturn(List.of());
        assertThat(sanitizer.sanitize(ME, List.of(999L))).isEmpty();
    }

    // ===== AC4：洗掉几类不该落库的 id（一律静默丢弃，不报错）=====

    @Test
    void 去重且保序() {
        assertThat(sanitizer.sanitize(ME, List.of(4L, 2L, 4L, 3L))).containsExactly(4L, 2L, 3L);
    }

    @Test
    void 自己at自己被剔除() {
        // 自 @ 不是攻击，只是没意义 —— 还会在 Story 3.4 给自己发一条「有人 @ 了你」。
        assertThat(sanitizer.sanitize(ME, List.of(ME, 2L))).containsExactly(2L);
    }

    @Test
    void 已注销的人被剔除() {
        // activeIdsAmong 与 isActive 同口径：注销（deleted_at 非空）不在返回集合里。
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(3L));
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).containsExactly(3L);
    }

    @Test
    void DEACTIVATED被剔除() {
        // V1.4.2 Story 5.1 新增：封号同样剔除（原来只判注销）—— 判据改走 activeIdsAmong。
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(2L));
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).containsExactly(2L);
        verify(accounts, never()).findAuthorViewsWithoutTags(anyCollection());
    }

    @Test
    void 不存在的id被剔除() {
        when(accounts.activeIdsAmong(anyCollection())).thenReturn(Set.of(3L));
        assertThat(sanitizer.sanitize(ME, List.of(999L, 3L))).containsExactly(3L);
    }

    @Test
    void 任一方向有拉黑关系的被剔除() {
        // 走 social.read 的统一出口（AD-7），且是**双向**判定 ——
        // 只判单向的表现是「我拉黑了他，还能在正文里 @ 他并给他发通知」。
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of(2L));
        assertThat(sanitizer.sanitize(ME, List.of(2L, 3L))).containsExactly(3L);
    }

    @Test
    void 被剔除时不报错只是少存一个() {
        // 🔴 报错等于把「这个 id 存不存在 / 他有没有拉黑我」做成可以逐个试探的问答口
        //    （同 Story 2.5 的口径）。用户看到的是发出去了、那个名字点不动。
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of(2L));
        assertThat(sanitizer.sanitize(ME, List.of(2L))).isEmpty();
    }

    @Test
    void 空与null都得到空表而不是null() {
        assertThat(sanitizer.sanitize(ME, null)).isEmpty();
        assertThat(sanitizer.sanitize(ME, List.of())).isEmpty();
    }

    @Test
    void 名单里的null元素不会炸() {
        assertThat(sanitizer.sanitize(ME, Arrays.asList(null, 2L))).containsExactly(2L);
    }

    @Test
    void 没有有效候选时不发多余查询() {
        assertThat(sanitizer.sanitize(ME, List.of(ME))).isEmpty();
        verify(candidates, never()).findExistingCandidateIds(anyLong(), anyCollection());
        verify(follows, never()).followStates(anyLong(), anyCollection());
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
    }

    @Test
    void 取数恰好四条查询与人数无关() {
        // AD-6：批量，不逐个查。这条路径是发布/评论的**同步写**路径。
        // 候选 + 关注两条判范围 → 隐藏一条 → 有效账号一条。
        sanitizer.sanitize(ME, List.of(2L, 3L, 4L, 5L, 6L));
        verify(candidates, times(1)).findExistingCandidateIds(anyLong(), anyCollection());
        verify(follows, times(1)).followStates(anyLong(), anyCollection());
        verify(follows, never()).followState(anyLong(), anyLong());
        verify(hideRelations, times(1)).hiddenEitherWay(anyLong(), anyCollection());
        verify(accounts, times(1)).activeIdsAmong(anyCollection());
        verify(accounts, never()).findAuthorViewsWithoutTags(anyCollection());
    }

    // ===== AC4：存储形态 —— 存 id，且只存 id =====

    @Test
    void 帖子与评论都存得下at名单且空表归一为空表() {
        ContentPost post = ContentPost.publish(ME, ContentType.DAILY, null, "@u2 走起", List.of());
        post.setMentionedUserIds(List.of(2L, 3L));
        assertThat(post.getMentionedUserIds()).containsExactly(2L, 3L);

        Comment comment = Comment.create(1L, null, ME, "@u2 你看");
        comment.setMentionedUserIds(List.of(2L));
        assertThat(comment.getMentionedUserIds()).containsExactly(2L);
    }

    @Test
    void 存量内容读出来是空表不是null() {
        // ⚠️ AD-10 Rule 6：存量文本不回溯解析，列里一律 NULL。渲染（3.3）与通知（3.4）
        //    都会直接遍历它，返回 null 就是两处各写一遍判空。
        assertThat(ContentPost.publish(ME, ContentType.DAILY, null, "老帖", List.of())
                .getMentionedUserIds()).isEmpty();
        assertThat(Comment.create(1L, null, ME, "老评论").getMentionedUserIds()).isEmpty();
    }

    @Test
    void 两个请求体里没有任何昵称字段() throws Exception {
        // 🔴 AC4 的反向约束：昵称一旦被存下来，对方改名后历史 @ 全部失效、点不动。
        //    所以提交口上**只能有 id**，不许出现任何"顺手也传一份昵称"的字段。
        assertThat(fieldNames(ContentPostCreateRequest.class))
                .contains("mentionedUserIds")
                .noneMatch(n -> n.toLowerCase().contains("nickname") || n.toLowerCase().contains("mentionedname"));
        assertThat(fieldNames(CommentCreateRequest.class))
                .contains("mentionedUserIds")
                .noneMatch(n -> n.toLowerCase().contains("nickname") || n.toLowerCase().contains("mentionedname"));
    }

    @Test
    void 两个实体里没有任何昵称字段() {
        assertThat(fieldNames(ContentPost.class))
                .contains("mentionedUserIds")
                .noneMatch(n -> n.toLowerCase().contains("mentionednickname"));
        assertThat(fieldNames(Comment.class))
                .contains("mentionedUserIds")
                .noneMatch(n -> n.toLowerCase().contains("mentionednickname"));
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(java.lang.reflect.Field::getName).toList();
    }
}
