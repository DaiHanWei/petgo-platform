package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.RefreshToken;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.SubjectType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.LoginResponse;
import com.tailtopia.auth.dto.TokenResponse;
import com.tailtopia.auth.dto.UserProfileResponse;
import com.tailtopia.auth.dto.VetLoginResponse;
import com.tailtopia.auth.repository.RefreshTokenRepository;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.security.AppleIdentity;
import com.tailtopia.shared.security.AppleTokenVerifier;
import com.tailtopia.shared.security.GoogleIdentity;
import com.tailtopia.shared.security.GoogleTokenVerifier;
import com.tailtopia.shared.security.JwtService;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetAccountService;
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

    /** Story 3.2：停用账号登录/刷新被拒的用户文案（App 据此引导外部联系渠道；用户已无法进入 App 工单）。 */
    static final String DEACTIVATED_MESSAGE =
            "账号已被停用，如有疑问请联系客服：WhatsApp 081290906953 / 邮箱 cs@tailtopia.id";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final GoogleTokenVerifier googleVerifier;
    private final AppleTokenVerifier appleVerifier;
    private final JwtService jwt;
    private final VetAccountService vetAccounts;
    private final PetProfileRepository petProfiles;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
            GoogleTokenVerifier googleVerifier, AppleTokenVerifier appleVerifier,
            JwtService jwt, VetAccountService vetAccounts, PetProfileRepository petProfiles) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.appleVerifier = appleVerifier;
        this.jwt = jwt;
        this.vetAccounts = vetAccounts;
        this.petProfiles = petProfiles;
    }

    @Transactional
    public LoginResponse loginWithGoogle(String idToken) {
        GoogleIdentity id = googleVerifier.verify(idToken);

        boolean[] isNew = {false};
        User user = users.findByGoogleSub(id.sub()).orElseGet(() -> {
            isNew[0] = true;
            return users.save(User.newGoogleUser(id.sub(), id.email(), id.displayName(), id.avatarUrl()));
        });

        // Story 3.2：停用账号即时不可登录（即便是已存在用户）。
        if (!user.isActiveStatus()) {
            throw AppException.forbidden(DEACTIVATED_MESSAGE);
        }

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user);

        // hasPetProfile 返回真实值：首授权建号的新用户必无档案；老用户按 pet_profiles 查询
        // （与 /me 同源 MeService.hasPetProfile，避免登录响应 stale=false 误导前端「去建档」引导）。
        UserProfileResponse profile = UserProfileResponse.from(user, hasPetProfile(user, isNew[0]));
        return new LoginResponse(access, refresh, user.getRole().name(),
                isNew[0], user.isOnboardingCompleted(), profile);
    }

    /**
     * Apple 登录（FR-44）：校验 identity token → 按 apple_sub 取号/首登建号 → 签发自签 JWT。
     * 与 {@link #loginWithGoogle} 同构；校验失败短路抛异常，绝不建号/不签发。
     */
    @Transactional
    public LoginResponse loginWithApple(String identityToken) {
        AppleIdentity id = appleVerifier.verify(identityToken);

        boolean[] isNew = {false};
        User user = users.findByAppleSub(id.sub()).orElseGet(() -> {
            isNew[0] = true;
            return users.save(User.newAppleUser(id.sub(), id.email()));
        });

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user);

        UserProfileResponse profile = UserProfileResponse.from(user, hasPetProfile(user, isNew[0]));
        return new LoginResponse(access, refresh, user.getRole().name(),
                isNew[0], user.isOnboardingCompleted(), profile);
    }

    /**
     * 登录响应的 hasPetProfile：首授权建号的新用户必无档案（短路免查）；老用户按 pet_profiles 实查。
     * 与 {@link MeService#hasPetProfile} 同源（owner_id 维度），保证登录/刷新/{@code /me} 三处一致。
     */
    private boolean hasPetProfile(User user, boolean isNew) {
        return !isNew && user.getId() != null && petProfiles.existsByOwnerId(user.getId());
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

    /**
     * 退出登录（Story 7.3，AC1）：作废当前 refresh 句柄（access 短时自然过期）。<b>不触碰任何业务数据</b>。
     * 未知/已失效句柄静默成功（幂等，退出不报错）。
     */
    @Transactional
    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(jwt.hashRefresh(rawRefresh)).ifPresent(token -> {
            token.revoke();
            refreshTokens.save(token);
        });
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
        // Story 3.2：停用账号刷新被拒（带停用语义，App 据此展示外部联系渠道）。
        if (!user.isActiveStatus()) {
            throw AppException.forbidden(DEACTIVATED_MESSAGE);
        }

        String access = jwt.issueAccessToken(user);
        String refresh = issueRefresh(user.getId(), SubjectType.USER);
        return new TokenResponse(access, refresh);
    }

    /** Story 3.2：停用普通用户——置 DEACTIVATED + 撤销其全部 refresh 句柄（既有令牌不可续期）。 */
    @Transactional
    public void deactivateUser(long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        user.deactivate();
        users.save(user);
        refreshTokens.deleteByUserIdAndSubjectType(userId, SubjectType.USER);
    }

    /** Story 3.2：重新激活普通用户——恢复登录权（不恢复已撤销令牌，用户重新登录即可）。 */
    @Transactional
    public void reactivateUser(long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        user.reactivate();
        users.save(user);
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
