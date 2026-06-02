package com.petgo.auth.service;

import com.petgo.auth.domain.RefreshToken;
import com.petgo.auth.domain.Role;
import com.petgo.auth.domain.SubjectType;
import com.petgo.auth.domain.User;
import com.petgo.auth.dto.LoginResponse;
import com.petgo.auth.dto.TokenResponse;
import com.petgo.auth.dto.UserProfileResponse;
import com.petgo.auth.dto.VetLoginResponse;
import com.petgo.auth.repository.RefreshTokenRepository;
import com.petgo.auth.repository.UserRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.security.GoogleIdentity;
import com.petgo.shared.security.GoogleTokenVerifier;
import com.petgo.shared.security.JwtService;
import com.petgo.vet.domain.VetAccount;
import com.petgo.vet.service.VetAccountService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth 业务：Google 登录建号/取号 + 自签 JWT 签发；refresh 轮换。
 *
 * <p>refresh 轮换持久化采用 {@code refresh_tokens} 表（决策：相较 Redis 句柄更易跨重启验证、
 * 不引入额外运行依赖；Redis 仅留作限流）。轮换 = 旧句柄置 revoked + 发新句柄，防重放。
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final GoogleTokenVerifier googleVerifier;
    private final JwtService jwt;
    private final VetAccountService vetAccounts;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
            GoogleTokenVerifier googleVerifier, JwtService jwt, VetAccountService vetAccounts) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.jwt = jwt;
        this.vetAccounts = vetAccounts;
    }

    @Transactional
    public LoginResponse loginWithGoogle(String idToken) {
        GoogleIdentity id = googleVerifier.verify(idToken);

        boolean[] isNew = {false};
        User user = users.findByGoogleSub(id.sub()).orElseGet(() -> {
            isNew[0] = true;
            return users.save(User.newGoogleUser(id.sub(), id.email(), id.displayName(), id.avatarUrl()));
        });

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user);

        // hasPetProfile：本 Story 期恒 false（pet_profiles 表在 Epic 2）。
        UserProfileResponse profile = UserProfileResponse.from(user, false);
        return new LoginResponse(access, refresh, user.getRole().name(),
                isNew[0], user.isOnboardingCompleted(), profile);
    }

    /**
     * 兽医账密登录（Story 5.1）：与用户侧 Google 流程隔离，签发 {@code role=VET} JWT。
     * 校验失败统一 401（不区分账号不存在/密码错/封禁，防枚举）。
     */
    @Transactional
    public VetLoginResponse loginVet(String username, String rawPassword) {
        VetAccount vet = vetAccounts.authenticate(username, rawPassword);
        String access = jwt.issueAccessToken(vet.getId(), Role.VET);
        String refresh = issueRefresh(vet.getId(), SubjectType.VET);
        return new VetLoginResponse(access, refresh, vet.getDisplayName(), Role.VET.name());
    }

    @Transactional
    public TokenResponse rotateRefresh(String rawRefresh) {
        String hash = jwt.hashRefresh(rawRefresh);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> AppException.unauthorized("登录已过期，请重新登录"));

        Instant now = Instant.now();
        if (!token.isUsable(now)) {
            throw AppException.unauthorized("登录已过期，请重新登录");
        }
        // 轮换：作废旧句柄，发新句柄（旧 refresh 重放将命中 revoked → 401）。
        token.revoke();
        refreshTokens.save(token);

        // 按主体类型分派签发（防 vet 的 refresh 误签 user token）。
        if (token.getSubjectType() == SubjectType.VET) {
            VetAccount vet = vetAccounts.getById(token.getUserId());
            if (!vet.isActive()) {
                throw AppException.unauthorized("登录已过期，请重新登录"); // 被封禁 → refresh 失效
            }
            String access = jwt.issueAccessToken(vet.getId(), Role.VET);
            String refresh = issueRefresh(vet.getId(), SubjectType.VET);
            return new TokenResponse(access, refresh);
        }

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> AppException.unauthorized("登录已过期，请重新登录"));

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user.getId(), SubjectType.USER);
        return new TokenResponse(access, refresh);
    }

    private String issueRefresh(User user) {
        return issueRefresh(user.getId(), SubjectType.USER);
    }

    private String issueRefresh(Long subjectId, SubjectType subjectType) {
        String raw = jwt.generateRefreshRaw();
        RefreshToken entity = new RefreshToken(subjectId, subjectType,
                jwt.hashRefresh(raw), jwt.refreshExpiry(Instant.now()));
        refreshTokens.save(entity);
        return raw;
    }
}
