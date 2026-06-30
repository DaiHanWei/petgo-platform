package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.repository.UserRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号状态查询（跨模块 service 接口）。供 Story 2.6 名片失效判定、Story 3.2 Feed 作者投影经接口取数据，
 * **不让 profile/content 直接 join users 表**（架构 Architectural Boundaries）。
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

    /** Story 3.2：取用户宠物状态（A/B/C），供 Feed 硬过滤；不存在/未设返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<String> petStatusOf(long userId) {
        return users.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .map(u -> u.getPetStatus() == null ? null : u.getPetStatus().name());
    }

    /**
     * Story 3.2：批量取作者展示投影（Feed 卡片用），注销账号匿名化（NFR-8）。
     * 缺失/注销作者一律返回 {@link AuthorView#anonymized}，不泄漏曾否存在。
     */
    @Transactional(readOnly = true)
    public Map<Long, AuthorView> findAuthorViews(Collection<Long> userIds) {
        Map<Long, AuthorView> found = users.findAllById(userIds).stream()
                .map(AccountQueryService::toAuthorView)
                .collect(Collectors.toMap(AuthorView::userId, Function.identity()));
        // 缺失的（不存在）也按匿名化补齐，调用方按 id 取必有值。
        return userIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> found.getOrDefault(id, AuthorView.anonymized(id))));
    }

    /** Story 3.1：按 id 取普通用户（role=USER），供后台用户详情只读聚合。 */
    @Transactional(readOnly = true)
    public Optional<User> findUserById(long userId) {
        return users.findById(userId).filter(u -> u.getRole() == Role.USER);
    }

    /** Story 3.1：按注册邮箱精确取普通用户（role=USER），供后台搜索。 */
    @Transactional(readOnly = true)
    public Optional<User> findUserByEmail(String email) {
        return users.findByEmailAndRole(email, Role.USER);
    }

    private static AuthorView toAuthorView(User u) {
        if (u.getDeletedAt() != null) {
            return AuthorView.anonymized(u.getId());
        }
        String name = u.getNickname() != null ? u.getNickname() : u.getDisplayName();
        return new AuthorView(u.getId(), name, u.getAvatarUrl(), false);
    }
}
