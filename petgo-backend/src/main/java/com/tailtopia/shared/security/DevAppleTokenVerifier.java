package com.tailtopia.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEV-ONLY Apple 校验桩：dev profile 下生效，<b>忽略 identityToken，恒解析成固定测试身份</b>。
 *
 * <p>与 {@link DevGoogleTokenVerifier} 平行：无真实 Apple 凭证时验证「登录后」鉴权链路（L2 联调）。
 *
 * <p>🔒 安全：仅 {@code @Profile("dev")} 生效，{@code @Primary} 在 dev 下盖过真实
 * {@link NimbusAppleTokenVerifier}；prod profile 下本 bean <b>不注册</b>，恒用真实 JWKS 校验。
 * <b>绝不削弱生产鉴权</b>——生产部署须以 {@code SPRING_PROFILES_ACTIVE=prod} 运行。
 */
@Component
@Profile("dev")
@Primary
public class DevAppleTokenVerifier implements AppleTokenVerifier {

    /** 固定测试用户的 Apple sub（与 Google dev 桩区分，避免撞同一账号）。 */
    public static final String DEV_SUB = "dev-stub-apple-user";

    private static final Logger log = LoggerFactory.getLogger(DevAppleTokenVerifier.class);

    public DevAppleTokenVerifier() {
        log.warn("⚠️ DEV Apple 校验桩已启用（dev profile）：所有 Apple 登录将落到固定测试账号 "
                + "sub={}。生产须以 prod profile 运行（真实 Nimbus 校验）。", DEV_SUB);
    }

    @Override
    public AppleIdentity verify(String identityToken) {
        return new AppleIdentity(DEV_SUB, "apple-test@petgo.dev");
    }
}
