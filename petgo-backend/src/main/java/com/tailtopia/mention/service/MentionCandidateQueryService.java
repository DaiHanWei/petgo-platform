package com.tailtopia.mention.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.mention.domain.MentionCandidate;
import com.tailtopia.mention.dto.MentionCandidateView;
import com.tailtopia.mention.repository.MentionCandidateRepository;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取 @ 候选集（V1.3.0 batch-b1 Story 3.1 · AC3/AC4/AC5）。
 *
 * <h2>🔴 一次取数只发三条查询，与候选人数无关（AC4）</h2>
 * <ol>
 *   <li>取该 owner 最近 {@value MentionCandidateMaintenanceService#KEEP_PER_OWNER} 条候选；</li>
 *   <li><b>一次</b>批量判拉黑（走 {@code social.read} 的统一出口，AD-7）；</li>
 *   <li><b>一次</b>批量取昵称 / 头像（走 {@code AccountQueryService} 的
 *       {@code findAuthorViewsWithoutTags}，不直 join users 表）。</li>
 * </ol>
 * ⚠️ 第三条刻意用**不带标签**的那个重载：这一行只有头像 + 昵称，
 * 为 50 个人多查一次运营标签是白跑（查完即丢）。
 * 逐个判、逐个查是这个接口最容易写坏的地方 —— 它服务的是<b>打字时的即时交互</b>。
 *
 * <h2>🔴 没有全局用户搜索（AC5）</h2>
 * 本服务<b>不接受任何关键词参数</b>。昵称过滤由客户端在这 {@value #MAX_CANDIDATES} 人之内做。
 * 搜索留在 1.6.0，<b>未前移</b>；界面上那个输入框不是搜索框。
 * ⚠️ 别"顺手"加一个 {@code keyword} 形参 —— 那一刻它就变成了全局用户搜索接口。
 */
@Service
public class MentionCandidateQueryService {

    /**
     * 对外最多给多少人（PRD §2.3：最近 30 人）。
     *
     * <p>⚠️ 与表里保留的 {@value MentionCandidateMaintenanceService#KEEP_PER_OWNER} 不是一个数：
     * 那个是<b>存量上限</b>，这个是<b>下发上限</b>。差值就是留给拉黑 / 注销排除的冗余 ——
     * 表里正好只存 30 人时，排掉 3 个被拉黑的就只剩 27 个可选。
     */
    public static final int MAX_CANDIDATES = 30;

    private final MentionCandidateRepository candidates;
    private final UserHideRelationReader hideRelations;
    private final AccountQueryService accounts;

    public MentionCandidateQueryService(MentionCandidateRepository candidates,
            UserHideRelationReader hideRelations, AccountQueryService accounts) {
        this.candidates = candidates;
        this.hideRelations = hideRelations;
        this.accounts = accounts;
    }

    /**
     * 某人的 @ 候选，最近互动的在前，最多 {@value #MAX_CANDIDATES} 人。
     *
     * <p>排除两类人：
     * <ul>
     *   <li>🔴 <b>任一方向存在隐藏关系的</b>（拉黑或举报隐藏都算，双向判定）——
     *       只判单向的表现是「我拉黑了他，他还能 @ 到我」；</li>
     *   <li><b>已注销的</b>（AC3）—— 被 @ 出来点进去是「用户不存在」。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<MentionCandidateView> candidatesFor(long ownerId) {
        List<MentionCandidate> rows = candidates.findRecent(ownerId,
                PageRequest.of(0, MentionCandidateMaintenanceService.KEEP_PER_OWNER));
        if (rows.isEmpty()) {
            // 🛡 没互动过的人（新用户）：直接返回空表，一次多余的查询都不发。
            return List.of();
        }
        List<Long> ids = rows.stream().map(MentionCandidate::getCandidateId).toList();

        // ① 拉黑排除 —— **一次**批量查询，走统一出口（AD-7）。
        Set<Long> hidden = hideRelations.hiddenEitherWay(ownerId, ids);
        // ② 昵称 / 头像 —— **一次**批量投影，顺带拿到「是否已注销」。
        // ⚠️ 用**不带标签**的那个重载：这一行只有头像 + 昵称，为 50 个人多查一次
        //    user_tag_assignments 是白跑（查完即丢），而这是个延迟敏感的接口。
        Map<Long, AuthorView> authors = accounts.findAuthorViewsWithoutTags(ids);

        List<MentionCandidateView> out = new ArrayList<>(MAX_CANDIDATES);
        for (MentionCandidate row : rows) {
            if (out.size() >= MAX_CANDIDATES) {
                break; // 排除之后仍然够 30 人就到此为止（多存的那些是给排除留的冗余）。
            }
            long id = row.getCandidateId();
            if (hidden.contains(id)) {
                continue;
            }
            AuthorView author = authors.get(id);
            // 注销 → 不进候选（AC3）。⚠️ `deleted()` 为 true 时 nickname 本就是 null，
            // 放进去只会是一行空白。
            if (author == null || author.deleted()) {
                continue;
            }
            out.add(new MentionCandidateView(id, author.nickname(), author.avatarUrl()));
        }
        return List.copyOf(out);
    }
}
