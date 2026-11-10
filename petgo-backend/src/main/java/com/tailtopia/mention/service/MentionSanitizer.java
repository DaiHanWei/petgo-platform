package com.tailtopia.mention.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.mention.repository.MentionCandidateRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 落库前把客户端提交的 @ 名单洗一遍（V1.3.0 batch-b1 Story 3.2 · AC4/AC5）。
 *
 * <h2>🔴 客户端的候选集是 UI，不是权限</h2>
 * {@code GET /api/v1/me/mention-candidates}（Story 3.1）只决定**选择器里能点到谁**。
 * 请求体里的 {@code mentionedUserIds} 是纯客户端输入 —— 手改一下就能 @ 到任何一个
 * 数字 id：已注销的、把自己拉黑了的、乃至自己。所以服务端在写库前**自己再判一遍**
 * （护栏：安全规则层只升不降不可绕过）。
 *
 * <h2>🔴 「@ 不到范围外的人」是服务端闸门，不是前端约定</h2>
 * 范围 = <b>互动候选 ∪ 我关注 ∪ 关注我</b>（V1.4.2 social-topic Story 5.1 · D-15 · FR-157 AC5；
 * 原 AD-10 Rule 3「@ 不到没打过交道的陌生人」在此基础上扩大，追更宠物不算）。前端选择器只能点到范围内的人，但
 * <b>接口自己必须拦</b> —— 否则一个 for 循环遍历 userId 就成了对任意陌生人的
 * 定向推送口（Story 3.4 会把每个 @ 变成一条通知）。
 * 所以本类第一步就是<b>与范围求交集</b>（按<b>提交这一刻</b>的关系判），交集之外的 id 一律丢掉。
 * ⚠️ 别把这一步挪到后面"顺手一起判"：它是几个过滤里唯一挡得住陌生人的那个。
 * <p>官方号（{@code role=ADMIN}）不特殊处理：它的粉丝是全体自动关注了它的用户，自然都在范围内（D-15）。
 * <p>发布之后取关 / 被取关<b>不影响</b>已落库的名单 —— 读路径不重新按关注校验。
 *
 * <h2>🔴 越界拒绝，不越界的异常一律「静默丢弃」</h2>
 * 只有**超过 {@value #MAX_MENTIONS} 人**才 422（AC5 是产品规则，用户看得见、该给反馈）；
 * 其余几类（范围外的、自己、已注销、封号、有拉黑关系）都是**悄悄剔掉**：
 * <ul>
 *   <li>报错等于把「这个 id 存不存在 / 他有没有拉黑我」做成一个可以逐个试探的问答口
 *       —— 与 Story 2.5「被拉黑与没内容逐字段相等」同一条口径；</li>
 *   <li>剔掉之后正文里那串「@昵称」只是退化成普通文字，用户看到的是发出去了、
 *       那个名字点不动 —— 与「对方发完之后才把我拉黑」的结果完全一致。</li>
 * </ul>
 *
 * <h2>⚠️ 常数四次批量查询，与人数无关（AD-6）</h2>
 * 一次批量求候选交集 + 一次批量取关注关系（{@code social.read.FollowReader}，双向一次）判范围，
 * 一次批量判拉黑（走 {@code social.read} 的统一出口，AD-7），一次批量判账号有效
 * （{@code activeIdsAmong}：注销 + 封号，与 {@code isActive} 同口径）。逐个查在这里代价不高（≤5 人），但它服务的是
 * **发布/评论的同步写路径**，没有理由破例。范围交集为空时后两条不发。
 */
@Service
public class MentionSanitizer {

    /** 单条内容 / 单条评论最多 @ 几个人（AC5 · AD-10 Rule 6，防骚扰）。 */
    public static final int MAX_MENTIONS = 5;

    /** 超限文案。前端按 error type 映射 toast，不展示原文。 */
    static final String TOO_MANY_MESSAGE = "最多只能提及 5 人";

    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;
    private final MentionCandidateRepository candidates;
    private final FollowReader follows;

    public MentionSanitizer(AccountQueryService accounts, UserHideRelationReader hideRelations,
            MentionCandidateRepository candidates, FollowReader follows) {
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.candidates = candidates;
        this.follows = follows;
    }

    /**
     * 洗一份可以直接落库的 @ 名单。
     *
     * @param authorId 发内容 / 发评论的人
     * @param raw      客户端提交的 userId 列表，可为 null
     * @return 去重、保序、已剔除非法项（范围外 / 自己 / 已注销 / 封号 / 有拉黑关系）的列表；
     *         没有任何有效 @ 时返回**空表**（不是 null）
     * @throws AppException 422，当提交人数超过 {@value #MAX_MENTIONS}
     */
    public List<Long> sanitize(long authorId, Collection<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // ⚠️ 先按**原始条数**判上限，再去重：一次提交 6 个同一个人是畸形输入，
        // 先去重再判会把它洗成合法的 1 个人悄悄放过去。DTO 上的 @Size 是同一口径，
        // 这里是 service 层权威（运营/后台链路不经 @Valid）。
        if (raw.size() > MAX_MENTIONS) {
            throw AppException.validation(TOO_MANY_MESSAGE);
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : raw) {
            // null 元素 / 自己 @ 自己：直接跳过。自 @ 不是攻击，只是没意义 ——
            // 它还会在 Story 3.4 给自己发一条「有人 @ 了你」的通知。
            if (id != null && id != authorId) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        // ① 🔴 范围交集 —— 互动候选 **一次** + 关注关系（双向）**一次**，按提交这一刻判（D-15）。
        //    这一步挡的是「绕过前端、直接对任意 userId 发定向通知」。
        Set<Long> inScope = new HashSet<>(candidates.findExistingCandidateIds(authorId, ids));
        for (Map.Entry<Long, FollowState> e : follows.followStates(authorId, ids).entrySet()) {
            FollowState st = e.getValue();
            if (st != null && (st.iFollow() || st.followsMe())) {
                inScope.add(e.getKey());
            }
        }
        inScope.retainAll(ids);
        if (inScope.isEmpty()) {
            // 全是范围外的人 → 后面两条查询都不必发。
            return List.of();
        }
        // ② 拉黑 / 举报隐藏 —— **一次**批量、**双向**判定，走统一出口（AD-7）。
        //    ⚠️ 候选集查询侧已经排过拉黑，但那是**取候选那一刻**的状态；
        //    用户可能在选完之后、提交之前把对方拉黑（或被拉黑），所以写入侧还要再判。
        Set<Long> hidden = hideRelations.hiddenEitherWay(authorId, inScope);
        // ③ 注销 / 封号 / 不存在 —— **一次**批量，与 isActive 同口径（V1.4.2 Story 5.1 起封号也剔除）。
        Set<Long> active = accounts.activeIdsAmong(inScope);

        List<Long> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (!inScope.contains(id) || hidden.contains(id) || !active.contains(id)) {
                continue;
            }
            out.add(id);
        }
        return List.copyOf(out);
    }
}
