package com.tailtopia.mention.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.mention.dto.MentionView;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 把存下来的 @ userId 变成可渲染的一行（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
 *
 * <h2>🔴 可点与否在服务端判定（story Dev Notes）</h2>
 * 拉黑关系与注销状态都不该让客户端自己算 —— 客户端判定只是"看不见"，
 * 而且两处判定迟早分叉（同 NFR-2）。所以服务端在返回内容时就把
 * 「这个 @ 能不能点、显示什么昵称」算好下发。
 *
 * <h2>🔴 一页只判一次，不是一条一判（AD-6）</h2>
 * 用法固定是两步：先 {@link #resolveAll(Long, Collection)} 把**整页**（含内嵌二级回复）
 * 涉及的 id 一次批量判完，再对每条内容 / 每条评论调 {@link #pick(List, Map)} 取自己那几行。
 * ⚠️ 别在循环里调 resolveAll —— 评论区一页 10 条一级 + 30 条二级，逐条判就是 80 次查询。
 *
 * <h2>⚠️ 昵称取的是「此刻」的昵称，不是写入时的</h2>
 * AC1 的原文是「对方改名后自动跟着变」。所以这里每次都去投影层查，
 * <b>绝不缓存</b>（也没有可缓存的地方 —— 护栏禁通用缓存层）。
 */
@Service
public class MentionViewService {

    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;

    public MentionViewService(AccountQueryService accounts, UserHideRelationReader hideRelations) {
        this.accounts = accounts;
        this.hideRelations = hideRelations;
    }

    /**
     * 把一整页涉及的被 @ 人一次判完。
     *
     * @param viewerId 查看者；<b>游客为 null</b> —— 游客与谁都没有拉黑关系，
     *                 所以那一条批量查询整个跳过（而不是拿 null 去查）
     * @param ids      这一页所有内容 / 评论的 {@code mentionedUserIds} 并集
     * @return id → 渲染投影。空入参返回空 Map，且**一条查询都不发**
     */
    public Map<Long, MentionView> resolveAll(Long viewerId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Map.of();
        }

        // ① 昵称 —— **一次**批量投影。用不带运营标签的那个重载：@ 高亮只要昵称，
        //    为它多查一次 user_tag_assignments 是白跑（同 Story 3.1 的理由）。
        Map<Long, AuthorView> authors = accounts.findAuthorViewsWithoutTags(distinct);
        // ② 拉黑（AC3）—— **一次**批量、**双向**判定，走 social.read 的统一出口（AD-7）。
        //    ⚠️ 单向判的表现是「我拉黑了他，他 @ 我的那条对我仍然高亮可点」。
        Set<Long> hidden = viewerId == null
                ? Set.of() // 游客：没有拉黑关系可言，这条查询不必发
                : hideRelations.hiddenEitherWay(viewerId, distinct);

        Map<Long, MentionView> out = new HashMap<>(distinct.size());
        for (Long id : distinct) {
            AuthorView author = authors.get(id);
            boolean unavailable = author == null       // 库里没这个人
                    || author.deleted()               // 已注销（AC4）
                    || hidden.contains(id);           // 任一方向拉黑（AC3）
            out.put(id, unavailable ? MentionView.blocked(id)
                    : MentionView.tappable(id, author.nickname()));
        }
        return out;
    }

    /**
     * 取某一条内容 / 评论自己那几行，**保持写入顺序**。
     *
     * @param ids      该条的 {@code mentionedUserIds}（空表 / null 都可以）
     * @param resolved {@link #resolveAll(Long, Collection)} 的结果
     * @return 该条的投影；<b>没有 @ 时返回 null</b> —— DTO 是 NON_NULL，
     *         Feed 一页 20 行每行挂一个空数组是白占体积（同 authorTags 的既有口径）
     */
    public static List<MentionView> pick(List<Long> ids, Map<Long, MentionView> resolved) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        List<MentionView> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            MentionView v = resolved.get(id);
            // resolveAll 没覆盖到（调用方漏并 id）时退化成不可点，而不是抛 ——
            // 少一个高亮比详情页整页 500 好。
            out.add(v == null ? MentionView.blocked(id) : v);
        }
        return List.copyOf(out);
    }
}
