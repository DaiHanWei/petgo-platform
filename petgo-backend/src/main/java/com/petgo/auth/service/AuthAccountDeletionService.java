package com.petgo.auth.service;

import com.petgo.auth.domain.SubjectType;
import com.petgo.auth.domain.User;
import com.petgo.auth.repository.RefreshTokenRepository;
import com.petgo.auth.repository.UserRepository;
import com.petgo.shared.media.PersonalMedia;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth 模块注销级联删除（Story 7.3）：用户账号（OAuth 绑定）+ refresh 句柄物理删除。
 *
 * <p>用户行删除后，其 UGC（content_posts/comments）的 author_id 由 {@code AccountQueryService} 解析为
 * {@link com.petgo.auth.dto.AuthorView#anonymized}（「已注销用户」+默认头像，不可触发 FR-26）——
 * 即 UGC 匿名化保留无需改 content 表（AC3）。返回头像公开图 URL 供 OSS 删除。
 */
@Service
public class AuthAccountDeletionService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

    public AuthAccountDeletionService(UserRepository users, RefreshTokenRepository refreshTokens) {
        this.users = users;
        this.refreshTokens = refreshTokens;
    }

    /** 删除用户 + refresh 句柄；返回头像公开图 URL（供级联删 OSS）。须在其它模块删除/匿名化之后调用。 */
    @Transactional
    public PersonalMedia deleteByUserId(long userId) {
        List<String> publicUrls = new ArrayList<>();
        Optional<User> user = users.findById(userId);
        if (user.isEmpty()) {
            return PersonalMedia.empty();
        }
        String avatar = user.get().getAvatarUrl();
        if (avatar != null && !avatar.isBlank()) {
            publicUrls.add(avatar);
        }
        refreshTokens.deleteByUserIdAndSubjectType(userId, SubjectType.USER);
        users.delete(user.get());
        return new PersonalMedia(new ArrayList<>(), publicUrls);
    }
}
