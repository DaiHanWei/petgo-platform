package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.SubjectType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.RefreshTokenRepository;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.shared.media.PersonalMedia;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth 模块注销级联（Story 7.3，决策 D1/A）：refresh 句柄物理删除 + 用户账号<b>就地匿名化</b>（软删 + 擦 PII）。
 *
 * <p><b>不物理删 user 行</b>：content_posts/comments/content_likes/content_reports 均以 NOT NULL + RESTRICT 外键
 * 指向 {@code users(id)}，硬删会抛 DataIntegrityViolationException（历史缺陷）。改为 {@link User#anonymizeForDeletion}
 * 软删 + 擦 PII + google_sub 置墓碑；其 UGC 由 {@code AccountQueryService} 解析为
 * {@link com.tailtopia.auth.dto.AuthorView#anonymized}（「已注销用户」+默认头像，不可触发 FR-26），匿名化保留（AC3）。
 * 返回头像公开图 URL 供 OSS 删除。个人数据（档案/health/triage/私密图）由各 owning service 另删。
 */
@Service
public class AuthAccountDeletionService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

    public AuthAccountDeletionService(UserRepository users, RefreshTokenRepository refreshTokens) {
        this.users = users;
        this.refreshTokens = refreshTokens;
    }

    /**
     * 就地匿名化用户 + 删 refresh 句柄；返回头像公开图 URL（供级联删 OSS）。须在其它模块删除/匿名化之后调用。
     * 幂等：已匿名化的用户重跑安全（头像已空则不再收 URL）。
     */
    @Transactional
    public PersonalMedia deleteByUserId(long userId) {
        List<String> publicUrls = new ArrayList<>();
        Optional<User> user = users.findById(userId);
        if (user.isEmpty()) {
            return PersonalMedia.empty();
        }
        User u = user.get();
        String avatar = u.getAvatarUrl();
        if (avatar != null && !avatar.isBlank()) {
            publicUrls.add(avatar);
        }
        refreshTokens.deleteByUserIdAndSubjectType(userId, SubjectType.USER);
        // 就地匿名化（不物理删行）：避免 content 模块 NOT NULL+RESTRICT 外键阻断；UGC 匿名化保留。
        u.anonymizeForDeletion(Instant.now());
        users.save(u);
        return new PersonalMedia(new ArrayList<>(), publicUrls);
    }
}
