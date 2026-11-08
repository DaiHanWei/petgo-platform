package com.tailtopia.mention.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.mention.dto.MentionCandidateView;
import com.tailtopia.mention.repository.MentionScopeDao;
import com.tailtopia.mention.repository.MentionScopeDao.ScopedCandidate;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取 @ 候选（V1.3.0 batch-b1 Story 3.1；V1.4.2 social-topic Story 5.1 · D-15 · FR-157 范围扩大）。
 *
 * <h2>两种取法</h2>
 * <ul>
 *   <li>{@link #candidatesFor}（不带关键词）：快捷列表 —— 先放<b>我关注的人</b>（关注时间倒序，
 *       系统自动关注的官方号也算），不足 {@value #MAX_CANDIDATES} 人用<b>互动候选</b>补齐，不重复；
 *       <b>不含「关注我的人」</b>；</li>
 *   <li>{@link #search}（带关键词）：只在「我关注 ∪ 关注我 ∪ 互动候选」三类范围内按昵称包含匹配，
 *       最多 {@value #MAX_SEARCH_RESULTS} 条。</li>
 * </ul>
 *
 * <h2>🔴 不是全局用户搜索</h2>
 * 关键词<b>只在上述三类范围内</b>查，范围外的人（昵称完全一致也一样）搜不到。全局用户搜索归 1.5.4。
 * ⚠️ 别给这里加「任意 userId 列表 / 全局开关」之类的参数 —— 那一刻它就变成了全局用户搜索接口。
 *
 * <h2>🔴 一次请求常数次查询，与人数无关（AD-6）</h2>
 * <ol>
 *   <li>范围查询<b>一条</b>（{@link MentionScopeDao}；排序、去重、拉黑 / 注销 / 封号排除全在 SQL 内，
 *       保证排除之后上限仍成立）；</li>
 *   <li>昵称 / 头像<b>一次</b>批量投影（{@code findAuthorViewsWithoutTags}，不带运营标签 ——
 *       这一行只有头像 + 昵称，多查一次标签是白跑）。</li>
 * </ol>
 * 范围为空时只发第一条。
 */
@Service
public class MentionCandidateQueryService {

    /** 快捷列表最多给多少人（PRD：最近 30 人）。 */
    public static final int MAX_CANDIDATES = 30;

    /** 带关键词时最多给多少条（PRD FR-157 AC2）。上限即边界，接口不翻页。 */
    public static final int MAX_SEARCH_RESULTS = 20;

    /**
     * 关键词最长多少字（去首尾空白后）。与 App {@code MentionDraft._maxKeywordLength = 20} 同口径；
     * 超过 → 422，不截断（截断会让结果与用户输入对不上）。
     */
    public static final int MAX_KEYWORD_LENGTH = 20;

    static final String KEYWORD_TOO_LONG_MESSAGE = "关键词最多 20 个字";

    static final String KEYWORD_INVALID_MESSAGE = "关键词包含非法字符";

    private final MentionScopeDao scope;
    private final AccountQueryService accounts;

    public MentionCandidateQueryService(MentionScopeDao scope, AccountQueryService accounts) {
        this.scope = scope;
        this.accounts = accounts;
    }

    /**
     * 快捷列表：我关注的（关注时间倒序）+ 互动候选补齐（互动时间倒序），最多 {@value #MAX_CANDIDATES} 人。
     *
     * <p>排除（SQL 内）：任一方向隐藏关系（拉黑 / 举报隐藏）、已注销、封号（{@code DEACTIVATED}）、自己。
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateView> candidatesFor(long ownerId) {
        return present(scope.quickList(ownerId, MAX_CANDIDATES));
    }

    /**
     * 范围内查询。{@code q} 为 null 或去空白后为空 → 等同 {@link #candidatesFor}。
     *
     * @throws AppException 422，去空白后长度超过 {@value #MAX_KEYWORD_LENGTH}
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateView> search(long ownerId, String q) {
        String keyword = q == null ? "" : q.strip();
        if (keyword.isEmpty()) {
            return candidatesFor(ownerId);
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw AppException.validation(KEYWORD_TOO_LONG_MESSAGE);
        }
        // 🛡 NUL（\u0000）：PG 文本参数不接受 0x00，JDBC 会抛错并被兜底成 500 —— 按畸形输入 422。
        if (keyword.indexOf('\u0000') >= 0) {
            throw AppException.validation(KEYWORD_INVALID_MESSAGE);
        }
        return present(scope.search(ownerId, likePattern(keyword), MAX_SEARCH_RESULTS));
    }

    /** {@code %关键词%}，并把 {@code \ % _} 转义成字面量（与 SQL 里的 {@code ESCAPE '\'} 配套）。 */
    static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private List<MentionCandidateView> present(List<ScopedCandidate> rows) {
        if (rows.isEmpty()) {
            // 🛡 范围为空（新用户）：直接返回，不查投影。
            return List.of();
        }
        List<Long> ids = rows.stream().map(ScopedCandidate::userId).toList();
        Map<Long, AuthorView> authors = accounts.findAuthorViewsWithoutTags(ids);

        List<MentionCandidateView> out = new ArrayList<>(rows.size());
        for (ScopedCandidate row : rows) {
            AuthorView author = authors.get(row.userId());
            // 并发注销窗口：SQL 之后、投影之前被注销 → 不放一行空白昵称进去。
            if (author == null || author.deleted()) {
                continue;
            }
            out.add(new MentionCandidateView(row.userId(), author.nickname(), author.avatarUrl(),
                    author.badgeLevel(), author.badgeTier(), author.badgeFrame(), row.relation()));
        }
        return List.copyOf(out);
    }
}
