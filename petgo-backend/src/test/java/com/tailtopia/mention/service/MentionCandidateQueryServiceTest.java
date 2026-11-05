package com.tailtopia.mention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.mention.domain.MentionRelation;
import com.tailtopia.mention.dto.MentionCandidateView;
import com.tailtopia.mention.repository.MentionScopeDao;
import com.tailtopia.mention.repository.MentionScopeDao.ScopedCandidate;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * L0：@ 候选范围查询服务（V1.4.2 social-topic Story 5.1 · AC1 / AC2 / AC6 · D-15）。
 *
 * <p>排序 / 去重 / 排除在 SQL 内（{@link MentionScopeDao}），只有 DB 跑得到 —— 由 L1
 * {@code MentionScopeIntegrationTest} 覆盖；本类钉服务层：分流、上限、转义、批量投影、relation 原样带出。
 * 末尾几条直接断言 SQL 原文（排除口径、快捷列表不含粉丝）。
 */
class MentionCandidateQueryServiceTest {

    private static final long ME = 7L;

    private MentionScopeDao scope;
    private AccountQueryService accounts;
    private MentionCandidateQueryService service;

    @BeforeEach
    void setUp() {
        scope = mock(MentionScopeDao.class);
        accounts = mock(AccountQueryService.class);
        service = new MentionCandidateQueryService(scope, accounts);
        when(accounts.findAuthorViewsWithoutTags(anyList())).thenAnswer(inv -> {
            Map<Long, AuthorView> out = new HashMap<>();
            for (Long id : inv.<List<Long>>getArgument(0)) {
                out.put(id, new AuthorView(id, "u" + id, null, false, List.of()));
            }
            return out;
        });
    }

    private static List<ScopedCandidate> rows(MentionRelation... rels) {
        List<ScopedCandidate> out = new ArrayList<>();
        for (int i = 0; i < rels.length; i++) {
            out.add(new ScopedCandidate(100L + i, rels[i]));
        }
        return out;
    }

    // ===== 分流 =====

    @Test
    void emptyScopeReturnsEmptyAndSkipsProjection() {
        when(scope.quickList(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.candidatesFor(ME)).isEmpty();
        verify(accounts, never()).findAuthorViewsWithoutTags(anyList());
    }

    @Test
    void nullOrBlankKeywordFallsBackToQuickList() {
        when(scope.quickList(anyLong(), anyInt())).thenReturn(List.of());

        service.search(ME, null);
        service.search(ME, "");
        service.search(ME, "   ");

        verify(scope, times(3)).quickList(ME, MentionCandidateQueryService.MAX_CANDIDATES);
        verify(scope, never()).search(anyLong(), anyString(), anyInt());
    }

    @Test
    void quickListIsCappedAtThirtyAndSearchAtTwenty() {
        when(scope.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        when(scope.quickList(anyLong(), anyInt())).thenReturn(List.of());

        service.candidatesFor(ME);
        service.search(ME, "  ra ");

        verify(scope).quickList(ME, 30);
        // 去首尾空白后再包 %…%
        verify(scope).search(ME, "%ra%", 20);
    }

    // ===== 422 =====

    @Test
    void keywordLongerThanTwentyIsRejectedWithoutQuerying() {
        String k21 = "a".repeat(21);
        assertThatThrownBy(() -> service.search(ME, k21))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(scope);
    }

    @Test
    void keywordWithNulIsRejectedAs422WithoutQuerying() {
        // PG 文本参数不接受 0x00，放下去 JDBC 抛错会被兜底成 500。
        for (String q : List.of("ra\u0000", "\u0000", "a\u0000b")) {
            assertThatThrownBy(() -> service.search(ME, q))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ex = (AppException) e;
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        // 错误类型与长度超限那条一致
                        assertThat(ex.getType()).isEqualTo(AppException.validation("x").getType());
                    });
        }
        verifyNoInteractions(scope);
    }

    @Test
    void exactlyTwentyAfterTrimIsAccepted() {
        when(scope.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        service.search(ME, "  " + "b".repeat(20) + "  ");
        verify(scope).search(ME, "%" + "b".repeat(20) + "%", 20);
    }

    // ===== 转义 =====

    @Test
    void likeWildcardsAndBackslashAreEscaped() {
        when(scope.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        service.search(ME, "a%_\\b");

        verify(scope).search(eq(ME), eq("%a\\%\\_\\\\b%"), eq(20));
    }

    @Test
    void likePatternHelperEscapesBackslashFirst() {
        // 先转义反斜杠，否则 % 的转义符会被二次转义成字面量反斜杠 + 通配符。
        assertThat(MentionCandidateQueryService.likePattern("%")).isEqualTo("%\\%%");
        assertThat(MentionCandidateQueryService.likePattern("\\%")).isEqualTo("%\\\\\\%%");
        assertThat(MentionCandidateQueryService.likePattern("Rina")).isEqualTo("%Rina%");
    }

    // ===== 投影 =====

    @Test
    void projectionIsOneBatchRegardlessOfSize() {
        MentionRelation[] rels = new MentionRelation[20];
        java.util.Arrays.fill(rels, MentionRelation.FOLLOWER);
        when(scope.search(anyLong(), anyString(), anyInt())).thenReturn(rows(rels));

        assertThat(service.search(ME, "u")).hasSize(20);
        verify(accounts, times(1)).findAuthorViewsWithoutTags(anyList());
        verify(accounts, never()).findAuthorViews(anyList());
    }

    @Test
    void concurrentlyDeletedRowsInProjectionAreDropped() {
        when(scope.quickList(anyLong(), anyInt())).thenReturn(
                rows(MentionRelation.FOLLOWING, MentionRelation.INTERACTED, MentionRelation.INTERACTED));
        Map<Long, AuthorView> authors = new HashMap<>();
        authors.put(100L, new AuthorView(100L, "u100", null, false, List.of()));
        authors.put(101L, AuthorView.anonymized(101L));
        // 102 缺失（投影之前已被物理清掉）
        when(accounts.findAuthorViewsWithoutTags(anyList())).thenReturn(authors);

        assertThat(service.candidatesFor(ME)).extracting(MentionCandidateView::userId)
                .containsExactly(100L);
    }

    @Test
    void relationAndOrderAreCarriedThroughVerbatim() {
        when(scope.search(anyLong(), anyString(), anyInt())).thenReturn(
                rows(MentionRelation.FOLLOWING, MentionRelation.FOLLOWER, MentionRelation.INTERACTED));

        List<MentionCandidateView> out = service.search(ME, "u");

        assertThat(out).extracting(MentionCandidateView::userId).containsExactly(100L, 101L, 102L);
        assertThat(out).extracting(MentionCandidateView::relation).containsExactly(
                MentionRelation.FOLLOWING, MentionRelation.FOLLOWER, MentionRelation.INTERACTED);
        assertThat(out.get(0).nickname()).isEqualTo("u100");
    }

    // ===== SQL 原文守卫（谓词只有 DB 跑得到，L0 只能断言字符串；行为由 L1 钉）=====

    @Test
    void quickListSqlHasNoFollowerBranchAndNoKeyword() {
        String sql = MentionScopeDao.QUICK_LIST_SQL;
        assertThat(sql).contains("f.follower_id = :me").contains("mention_candidates c");
        assertThat(sql).as("快捷列表不含「关注我的人」（拍板点 ①）").doesNotContain("f.followee_id = :me");
        assertThat(sql).doesNotContain(":pattern").doesNotContain("ILIKE");
    }

    @Test
    void searchSqlCoversThreeScopesWithEscapedIlike() {
        String sql = MentionScopeDao.SEARCH_SQL;
        assertThat(sql).contains("f.follower_id = :me").contains("f.followee_id = :me")
                .contains("c.owner_id = :me");
        assertThat(sql).contains("COALESCE(u.nickname, u.display_name) ILIKE :pattern ESCAPE '\\'");
    }

    @Test
    void bothSqlsExcludeHiddenEitherWayDeletedDeactivatedAndSelf() {
        for (String sql : List.of(MentionScopeDao.QUICK_LIST_SQL, MentionScopeDao.SEARCH_SQL)) {
            assertThat(sql)
                    .contains("u.id <> :me")
                    .contains("u.deleted_at IS NULL")
                    .contains("u.status = 'ACTIVE'")
                    // 双向、不分来源 —— 与 hiddenEitherWay 逐字一致
                    .contains("(h.holder_id = :me AND h.target_id = u.id)")
                    .contains("(h.holder_id = u.id AND h.target_id = :me)")
                    .doesNotContain("h.source")
                    .contains("DISTINCT ON (uid)")
                    .contains("ORDER BY b.rk, b.at DESC, b.tb DESC")
                    .contains("LIMIT :limit");
        }
    }
}
