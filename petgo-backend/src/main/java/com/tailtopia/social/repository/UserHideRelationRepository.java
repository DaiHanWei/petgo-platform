package com.tailtopia.social.repository;

import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 隐藏关系读写（Story 1.1）。
 *
 * <p>⚠️ <b>本仓储只允许 {@code com.tailtopia.social} 模块内部引用</b>。
 * {@code content} / {@code notify} / {@code auth} 三侧一律经 {@code social.read} 的只读端口，
 * 禁止直读本接口（AD-8 模块边界）。
 */
public interface UserHideRelationRepository extends JpaRepository<UserHideRelation, Long> {

    /** 是否存在任意来源的隐藏（五处过滤用：Feed / 运营干预位 / 评论 R1·R2 / 搜索列表 / 通知抑制）。 */
    boolean existsByHolderIdAndTargetId(long holderId, long targetId);

    /** 是否存在指定来源的隐藏。主页访问校验只认 {@link HideSource#BLOCK}（AD-11）。 */
    boolean existsByHolderIdAndTargetIdAndSource(long holderId, long targetId, HideSource source);

    /** 取指定来源的那一行（解除拉黑 / 幂等判定用）。 */
    Optional<UserHideRelation> findByHolderIdAndTargetIdAndSource(long holderId, long targetId,
            HideSource source);

    /** 某人主动拉黑的全部对象，按拉黑时间倒序（黑名单页，Story 1.5 消费）。 */
    List<UserHideRelation> findByHolderIdAndSourceOrderByCreatedAtDesc(long holderId, HideSource source);

    /** 删除指定来源的那一行；返回受影响行数（解除拉黑只删 BLOCK 行）。 */
    long deleteByHolderIdAndTargetIdAndSource(long holderId, long targetId, HideSource source);
}
