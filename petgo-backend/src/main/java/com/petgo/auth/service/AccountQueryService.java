package com.petgo.auth.service;

import com.petgo.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号状态查询（跨模块 service 接口）。供 Story 2.6 名片失效判定经接口取注销态，
 * **不让 profile 直接 join users 表**（架构 Architectural Boundaries）。
 */
@Service
public class AccountQueryService {

    private final UserRepository users;

    public AccountQueryService(UserRepository users) {
        this.users = users;
    }

    /** 账号是否有效（存在且未注销 {@code deleted_at IS NULL}）。 */
    @Transactional(readOnly = true)
    public boolean isActive(long userId) {
        return users.findById(userId)
                .map(u -> u.getDeletedAt() == null)
                .orElse(false);
    }
}
