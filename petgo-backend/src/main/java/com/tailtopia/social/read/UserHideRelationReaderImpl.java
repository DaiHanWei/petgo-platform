package com.tailtopia.social.read;

import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.repository.UserHideRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserHideRelationReader} 实现（Story 1.1）。
 *
 * <p>每次查库、走唯一索引，<b>不引任何缓存</b>（AD-18）。因 {@code open-in-view: false}，
 * 两个方法均标 {@code @Transactional(readOnly = true)}，数据在 service 层事务内取完。
 */
@Service
public class UserHideRelationReaderImpl implements UserHideRelationReader {

    private final UserHideRelationRepository relations;

    public UserHideRelationReaderImpl(UserHideRelationRepository relations) {
        this.relations = relations;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHidden(long holderId, long targetId) {
        return relations.existsByHolderIdAndTargetId(holderId, targetId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBlocked(long holderId, long targetId) {
        // ⚠️ 只认 BLOCK：举报隐藏必须放行，否则打死 FR-58 闭环（AD-11）。
        return relations.existsByHolderIdAndTargetIdAndSource(holderId, targetId, HideSource.BLOCK);
    }
}
