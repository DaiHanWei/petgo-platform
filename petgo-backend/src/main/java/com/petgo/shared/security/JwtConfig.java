package com.petgo.shared.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 自签 JWT 的编/解码器（HMAC HS256，对称密钥 env 注入）。
 *
 * <p>这些 Bean 校验/签发的是**本应用 JWT**（非 Google token）——本应用 JWT 作为
 * 后续所有受保护端点的鉴权凭证（OAuth2 Resource Server 用 {@link JwtDecoder} 校验）。
 */
@Configuration
public class JwtConfig {

    private final SecretKey secretKey;

    public JwtConfig(AuthProperties props) {
        String secret = props.getJwt().getSecret();
        // 兜底：密钥不足 32 字节会导致 HS256 拒绝；启动期给出明确失败而非运行期。
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "petgo.auth.jwt.secret 必须 ≥32 字节（HS256）；请经 env JWT_SECRET 注入足够长的密钥");
        }
        this.secretKey = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
