package com.tailtopia.mention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.dto.ContentDetailResponse;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.mention.dto.MentionView;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：@ 的渲染投影（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
 *
 * <h2>本文件钉的是 story Dev Notes 那句「🔴 可点与否在服务端判定」</h2>
 * 拉黑与注销都不该让客户端自己算。所以「什么情况下不可点」必须在**后端**有用例，
 * 而不是只在 App 侧靠 L2 看一眼。
 */
class MentionViewServiceTest {

    private static final long ME = 1L;

    private AccountQueryService accounts;
    private UserHideRelationReader hideRelations;
    private MentionViewService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        hideRelations = mock(UserHideRelationReader.class);
        service = new MentionViewService(accounts, hideRelations);
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of());
        when(accounts.findAuthorViewsWithoutTags(anyCollection()))
                .thenAnswer(inv -> alive(inv.getArgument(0)));
    }

    private static Map<Long, AuthorView> alive(Iterable<Long> ids) {
        Map<Long, AuthorView> out = new HashMap<>();
        for (Long id : ids) {
            out.put(id, new AuthorView(id, "nick" + id, "http://a/" + id, false, List.of()));
        }
        return out;
    }

    // ===== AC1：高亮 + 当前昵称 =====

    @Test
    void 可点并带当前昵称() {
        MentionView v = service.resolveAll(ME, List.of(2L)).get(2L);
        assertThat(v.tappable()).isTrue();
        assertThat(v.nickname()).isEqualTo("nick2");
    }

    @Test
    void 昵称每次都去投影层查而不是用写入时那份() {
        // AC1「对方改名后自动跟着变」：改掉投影层的返回，同一个 id 下发的昵称就得跟着变。
        when(accounts.findAuthorViewsWithoutTags(anyCollection()))
                .thenReturn(Map.of(2L, new AuthorView(2L, "改过的名", null, false, List.of())));
        assertThat(service.resolveAll(ME, List.of(2L)).get(2L).nickname()).isEqualTo("改过的名");
    }

    // ===== AC3：拉黑不高亮 =====

    @Test
    void 任一方向有拉黑关系就不可点且不下发昵称() {
        // 🔴 单向判的表现是「我拉黑了他，他 @ 我的那条对我仍然高亮可点」。
        when(hideRelations.hiddenEitherWay(anyLong(), anyCollection())).thenReturn(Set.of(2L));
        MentionView v = service.resolveAll(ME, List.of(2L, 3L)).get(2L);
        assertThat(v.tappable()).isFalse();
        // ⚠️ 不下发昵称：给一个已拉黑他的人下发当前昵称，等于把「对方改名了」告诉他。
        assertThat(v.nickname()).isNull();
        assertThat(service.resolveAll(ME, List.of(2L, 3L)).get(3L).tappable()).isTrue();
    }

    // ===== AC4：注销用户 =====

    @Test
    void 已注销不可点且不下发昵称() {
        when(accounts.findAuthorViewsWithoutTags(anyCollection()))
                .thenReturn(Map.of(2L, AuthorView.anonymized(2L)));
        MentionView v = service.resolveAll(ME, List.of(2L)).get(2L);
        assertThat(v.tappable()).isFalse();
        assertThat(v.nickname()).isNull();
    }

    @Test
    void 库里查不到的id退化成不可点而不是抛() {
        when(accounts.findAuthorViewsWithoutTags(anyCollection())).thenReturn(Map.of());
        assertThat(service.resolveAll(ME, List.of(999L)).get(999L).tappable()).isFalse();
    }

    // ===== 游客 =====

    @Test
    void 游客不发拉黑那条查询() {
        // 游客与谁都没有拉黑关系，拿 null 去查是白跑一次（沿用 Feed 的既有惯例）。
        assertThat(service.resolveAll(null, List.of(2L)).get(2L).tappable()).isTrue();
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
    }

    // ===== 取数形态（AD-6）=====

    @Test
    void 整页只发两条查询与被at人数无关() {
        service.resolveAll(ME, List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L));
        verify(accounts, times(1)).findAuthorViewsWithoutTags(anyCollection());
        verify(hideRelations, times(1)).hiddenEitherWay(anyLong(), anyCollection());
    }

    @Test
    void 空入参一条查询都不发() {
        assertThat(service.resolveAll(ME, List.of())).isEmpty();
        assertThat(service.resolveAll(ME, null)).isEmpty();
        assertThat(service.resolveAll(ME, Arrays.asList((Long) null))).isEmpty();
        verify(accounts, never()).findAuthorViewsWithoutTags(anyCollection());
        verify(hideRelations, never()).hiddenEitherWay(anyLong(), anyCollection());
    }

    @Test
    void 重复的id去重后只判一次() {
        // 一页里好几条内容 @ 了同一个人是常态（并集之后必须去重）。
        service.resolveAll(ME, List.of(2L, 2L, 3L, 2L));
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(accounts).findAuthorViewsWithoutTags(captor.capture());
        assertThat(captor.getValue()).containsExactly(2L, 3L);
    }

    // ===== pick：按条取自己那几行 =====

    @Test
    void pick保持写入顺序() {
        Map<Long, MentionView> all = service.resolveAll(ME, List.of(2L, 3L));
        assertThat(MentionViewService.pick(List.of(3L, 2L), all))
                .extracting(MentionView::userId).containsExactly(3L, 2L);
    }

    @Test
    void pick对没有at的条目返回null而不是空表() {
        // DTO 是 NON_NULL：Feed 一页 20 行每行挂一个空数组是白占体积（同 authorTags 的既有口径）。
        assertThat(MentionViewService.pick(null, Map.of())).isNull();
        assertThat(MentionViewService.pick(List.of(), Map.of())).isNull();
    }

    @Test
    void pick遇到没解析过的id退化成不可点而不是抛() {
        // 调用方漏并 id 时，少一个高亮比详情页整页 500 好。
        assertThat(MentionViewService.pick(List.of(42L), Map.of()))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.userId()).isEqualTo(42L);
                    assertThat(v.tappable()).isFalse();
                });
    }

    // ===== 三个出口都带得上（契约）=====

    @Test
    void 三个响应dto都有mentions字段() {
        // AC1 说的是「正文**或**评论」——Feed 卡片、详情页、评论区三处都渲染文字，
        // 漏掉任何一处的表现都是「同一条内容在这儿能点、在那儿点不动」。
        assertThat(fieldNames(FeedItemResponse.class)).contains("mentions");
        assertThat(fieldNames(ContentDetailResponse.class)).contains("mentions");
        assertThat(fieldNames(CommentResponse.class)).contains("mentions");
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
    }
}
