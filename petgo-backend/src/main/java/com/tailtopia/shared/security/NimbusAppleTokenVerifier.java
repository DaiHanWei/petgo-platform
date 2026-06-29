package com.tailtopia.shared.security;

import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 真实 Apple identity token 校验（基于 Apple JWKS）。FR-44（1.1.0）。
 *
 * <p>与 {@link NimbusGoogleTokenVerifier} 同构：用 Spring Security {@link NimbusJwtDecoder}
 * 拉取 Apple 公钥验签 + exp；叠加 iss（appleid.apple.com）与 aud（本应用 client id，通常 = iOS
 * bundle id）校验。client id 由 env 注入（{@code APPLE_CLIENT_ID}），绝不入库。
 *
 * <p>L2 节点：真实校验需联网 Apple JWKS + 真实 client id；L0/L1 测试用 stub 替换。
 */
@Component
public class NimbusAppleTokenVerifier implements AppleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(NimbusAppleTokenVerifier.class);
    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER = "https://appleid.apple.com";

    private final String clientId;
    private volatile JwtDecoder decoder;

    public NimbusAppleTokenVerifier(@Value("${petgo.auth.apple.client-id:}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    public AppleIdentity verify(String identityToken) {
        try {
            Jwt jwt = decoder().decode(identityToken);
            return new AppleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"));
        } catch (Exception ex) {
            // 不记录 identityToken/PII；仅记类型，对外统一 401。
            log.warn("Apple identity token 校验失败：{}", ex.getClass().getSimpleName());
            throw AppException.unauthorized("登录失败，请重试");
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(APPLE_JWKS).build();
                    nimbus.setJwtValidator(appleValidator());
                    decoder = nimbus;
                }
                local = decoder;
            }
        }
        return local;
    }

    private OAuth2TokenValidator<Jwt> appleValidator() {
        OAuth2TokenValidator<Jwt> issuer =
                new JwtClaimValidator<String>(JwtClaimNames.ISS, APPLE_ISSUER::equals);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> clientId.isBlank() || (aud != null && aud.contains(clientId)));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new JwtTimestampValidator(), issuer, audience);
    }
}
