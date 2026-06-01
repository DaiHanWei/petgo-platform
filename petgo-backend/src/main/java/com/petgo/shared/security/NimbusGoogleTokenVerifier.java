package com.petgo.shared.security;

import com.petgo.shared.error.AppException;
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
 * 真实 Google ID Token 校验（基于 Google JWKS）。
 *
 * <p>用 Spring Security {@link NimbusJwtDecoder} 拉取 Google 公钥验签 + exp；
 * 叠加 iss（accounts.google.com）与 aud（本应用 client id）校验。
 * client id 由 env 注入（{@code GOOGLE_OAUTH_CLIENT_ID}），绝不入库。
 *
 * <p>L2 节点：真实校验需联网 Google JWKS + 真实 client id；L0/L1 测试用 stub 替换。
 */
@Component
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(NimbusGoogleTokenVerifier.class);
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> GOOGLE_ISSUERS = List.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private volatile JwtDecoder decoder;

    public NimbusGoogleTokenVerifier(@Value("${petgo.auth.google.client-id:}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        try {
            Jwt jwt = decoder().decode(idToken);
            return new GoogleIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("picture"));
        } catch (Exception ex) {
            // 不记录 idToken/PII；仅记类型，对外统一 401。
            log.warn("Google ID Token 校验失败：{}", ex.getClass().getSimpleName());
            throw AppException.unauthorized("登录失败，请重试");
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).build();
                    nimbus.setJwtValidator(googleValidator());
                    decoder = nimbus;
                }
                local = decoder;
            }
        }
        return local;
    }

    private OAuth2TokenValidator<Jwt> googleValidator() {
        OAuth2TokenValidator<Jwt> issuer =
                new JwtClaimValidator<String>(JwtClaimNames.ISS, iss -> iss != null && GOOGLE_ISSUERS.contains(iss));
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> clientId.isBlank() || (aud != null && aud.contains(clientId)));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new JwtTimestampValidator(), issuer, audience);
    }
}
