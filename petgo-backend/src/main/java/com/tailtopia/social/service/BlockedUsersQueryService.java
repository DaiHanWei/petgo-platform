package com.tailtopia.social.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import com.tailtopia.social.dto.BlockedUserItem;
import com.tailtopia.social.repository.UserHideRelationRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 黑名单列表查询（Story 1.5，FR-94）。写侧在 {@link UserHideRelationService}。
 *
 * <p><b>只收录主动拉黑（{@code source=BLOCK}）</b>——举报产生的隐藏<b>不出现在这一页</b>：
 * 黑名单是「我主动拉黑了谁」的清单，举报隐藏没有解除入口，混进来会给用户一个他解除不了的条目。
 *
 * <p><b>模块边界（AD-8）</b>：展示字段（昵称/头像/注销匿名态）一律经 {@link AccountQueryService}
 * <b>批量</b>取，{@code social} <b>不直连用户侧仓储、不 join {@code users} 表</b>。
 * 这里直接构造器注入具体 {@code @Service} 而不另抽端口接口——本仓跨模块取用户投影一贯如此
 * （{@code auth} / {@code content} / {@code profile} / {@code consult} 四个模块都这么注）；
 * 唯一那个真端口接口是为「提供方 story 还没做、消费方要先上线」才破的例，本 story 不适用。
 */
@Service
public class BlockedUsersQueryService {

    private final UserHideRelationRepository relations;
    private final AccountQueryService accountQueryService;

    public BlockedUsersQueryService(UserHideRelationRepository relations,
            AccountQueryService accountQueryService) {
        this.relations = relations;
        this.accountQueryService = accountQueryService;
    }

    /**
     * 我主动拉黑的人，按<b>拉黑时间倒序</b>。
     *
     * <p>⚠️ 排序取 {@code BLOCK} 行的 {@code created_at}，<b>不取 {@code updated_at}</b>，
     * 也不受事后追加 {@code REPORT} 行的影响——三元唯一键让举报写的是另一行，碰不到这一行。
     * 取错字段（或哪天有人给这张表加了 touch 逻辑）的表现是：用户什么都没做，
     * 三个月前拉黑的人忽然跑到列表最前面。
     *
     * <p><b>全量返回、不分页</b>：{@code /api/v1/me/*} 的小列表本仓一贯裸 {@code List<T>} 返回
     * （{@code MeRefundController} 同款），拉黑量级天然极小。列表长度就是设置页要显示的计数，
     * <b>不另加 {@code total} 字段</b>（全仓没有任何列表响应带 total，全量返回时它与 items.length 冗余）。
     */
    @Transactional(readOnly = true)
    public List<BlockedUserItem> listBlocked(long holderId) {
        List<UserHideRelation> rows =
                relations.findByHolderIdAndSourceOrderByCreatedAtDesc(holderId, HideSource.BLOCK);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> targetIds = rows.stream().map(UserHideRelation::getTargetId).toList();

        // 批量取展示投影：对任何入参 id 必有返回值（不存在/已注销一律 anonymized），无需判空。
        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(targetIds);
        // 「已举报」标记：同 (holder, target) 下是否还有一条 REPORT 行。一次查询解决整页，不逐行 exists。
        Set<Long> reported = Set.copyOf(
                relations.findTargetIdsByHolderAndSourceIn(holderId, HideSource.REPORT, targetIds));

        return rows.stream()
                .map(r -> BlockedUserItem.of(authors.get(r.getTargetId()),
                        reported.contains(r.getTargetId()), r.getCreatedAt()))
                .toList();
    }
}
