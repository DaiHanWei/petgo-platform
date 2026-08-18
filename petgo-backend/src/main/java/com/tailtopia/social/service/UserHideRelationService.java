package com.tailtopia.social.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import com.tailtopia.social.repository.UserHideRelationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 隐藏关系写服务（Story 1.1，FR-94 / FR-58）。
 *
 * <p><b>幂等口径一句话：幂等只在同一来源之间成立。</b>
 * 重复拉黑不新增记录、<b>不刷新拉黑时间</b>；重复解除静默成功；
 * <b>但已存在举报隐藏时再拉黑，必须照常建立拉黑关系</b>（决策 C-91）。
 *
 * <p><b>服务端不依赖前端防连点</b> —— 前端另做按钮防抖，本服务自身幂等。
 */
@Service
public class UserHideRelationService {

    private final UserHideRelationRepository relations;

    public UserHideRelationService(UserHideRelationRepository relations) {
        this.relations = relations;
    }

    /**
     * 主动拉黑（FR-94）。幂等：已存在 {@code BLOCK} 行则直接返回，<b>不新增、不刷新时间戳</b>。
     *
     * <p>⚠️ 已存在 {@code REPORT} 行<b>不构成短路条件</b> —— 三元唯一键让两行并存，
     * 必须照常建 {@code BLOCK} 行，否则用户点了拉黑、Toast 说「请在黑名单里查看」，去了却找不到人。
     *
     * @throws AppException 目标为本人时校验错误（不可拉黑自己）
     */
    @Transactional
    public void block(long holderId, long targetId) {
        requireNotSelf(holderId, targetId, "不能拉黑自己");
        insertIfAbsent(holderId, targetId, HideSource.BLOCK);
    }

    /**
     * 解除拉黑（FR-94）。<b>只删 {@code BLOCK} 行</b>；{@code REPORT} 行永不删除、无任何解除入口。
     * 重复解除<b>静默成功</b>（不报错）。
     */
    @Transactional
    public void unblock(long holderId, long targetId) {
        relations.deleteByHolderIdAndTargetIdAndSource(holderId, targetId, HideSource.BLOCK);
    }

    /**
     * 举报触发建立隐藏关系（FR-58「举报即隐藏」，方案甲）。
     *
     * <p>⚠️ <b>供 {@code moderation} 模块在举报提交事务内调用</b>（Story 2.1 接入）——
     * 写工单/明细与写本行<b>必须同一事务</b>，任一失败整体回滚。
     * 依赖方向：{@code moderation → social}，<b>不反向依赖</b>（AD-8）。
     *
     * <p>⚠️ 若该 {@code (holder, target)} 已存在 {@code BLOCK} 行（先拉黑、后举报），
     * 本方法<b>不得修改 BLOCK 行的任何字段</b>（含时间戳）—— 否则黑名单页的排序会被举报动作搅乱，
     * 用户会看到「我今天什么都没做，这人怎么跑到最前面了」。三元唯一键从结构上保证了这一点。
     */
    @Transactional
    public void hideByReport(long holderId, long targetId) {
        requireNotSelf(holderId, targetId, "不能举报自己");
        insertIfAbsent(holderId, targetId, HideSource.REPORT);
    }

    /**
     * 同源幂等插入：该 {@code (holder, target, source)} 已存在则什么都不做。
     *
     * <p>并发下两个线程可能同时通过 exists 检查，此时由三元唯一约束兜底 ——
     * 捕获后视为「已存在」静默返回，与先到线程的结果一致（幂等语义不变）。
     */
    private void insertIfAbsent(long holderId, long targetId, HideSource source) {
        if (relations.existsByHolderIdAndTargetIdAndSource(holderId, targetId, source)) {
            return; // 幂等：不新增、不刷新时间戳
        }
        try {
            relations.save(UserHideRelation.create(holderId, targetId, source));
        } catch (DataIntegrityViolationException e) {
            // 并发撞三元唯一约束：本次未新增，结果与胜出线程一致，幂等吞掉。
        }
    }

    private static void requireNotSelf(long holderId, long targetId, String message) {
        if (holderId == targetId) {
            throw AppException.validation(message);
        }
    }
}
