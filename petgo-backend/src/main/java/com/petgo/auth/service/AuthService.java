package com.petgo.auth.service;

import com.petgo.auth.domain.RefreshToken;
import com.petgo.auth.domain.User;
import com.petgo.auth.dto.LoginResponse;
import com.petgo.auth.dto.TokenResponse;
import com.petgo.auth.dto.UserProfileResponse;
import com.petgo.auth.repository.RefreshTokenRepository;
import com.petgo.auth.repository.UserRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.security.GoogleIdentity;
import com.petgo.shared.security.GoogleTokenVerifier;
import com.petgo.shared.security.JwtService;
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

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
            GoogleTokenVerifier googleVerifier, JwtService jwt) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.jwt = jwt;
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

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> AppException.unauthorized("登录已过期，请重新登录"));

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user);
        return new TokenResponse(access, refresh);
    }

    private String issueRefresh(User user) {
        String raw = jwt.generateRefreshRaw();
        RefreshToken entity = new RefreshToken(user.getId(), jwt.hashRefresh(raw), jwt.refreshExpiry(Instant.now()));
        refreshTokens.save(entity);
        return raw;
    }
}
