package com.petgo.shared.security;

import com.petgo.auth.domain.Role;
import com.petgo.auth.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * 自签 JWT 签发 + refresh 令牌生成/哈希（Story 1.3）。
 *
 * <ul>
 *   <li>access：短时 HMAC JWT，claim {@code sub=userId}、{@code role}、{@code exp}。</li>
 *   <li>refresh：不可枚举随机串（256 bit）；仅其 SHA-256 hash 入库（明文绝不落库/日志）。</li>
 * </ul>
 * refresh 轮换的「旧句柄失效 + 发新句柄」逻辑在 {@code AuthService}。
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    public JwtService(JwtEncoder encoder, AuthProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** 签发用户 access token（{@code sub=userId}、{@code role}）。 */
    public String issueAccessToken(User user) {
        return issueAccessToken(user.getId(), user.getRole());
    }

    /**
     * 签发 access token，返回紧凑串。{@code sub=subjectId}、{@code role}。
     *
     * <p>Story 5.1：兽医登录复用同一签发体系（{@code role=VET}，{@code sub=vetId}），
     * 靠 {@code role} claim 与用户/运营严格区分——严禁为兽医另起一套 token 体系。
     */
    public String issueAccessToken(long subjectId, Role role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.getJwt().getIssuer())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(props.getJwt().getAccessTtlSeconds()))
                .subject(String.valueOf(subjectId))
                .claim("role", role.name())
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** 生成新的 refresh 明文（仅本次返回给客户端，不入库）。 */
    public String generateRefreshRaw() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** refresh 明文 → 入库用的不可逆 hash。 */
    public String hashRefresh(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public Instant refreshExpiry(Instant from) {
        return from.plus(Duration.ofDays(props.getRefresh().getTtlDays()));
    }
}
