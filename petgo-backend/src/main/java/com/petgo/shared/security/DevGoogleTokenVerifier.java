package com.petgo.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEV-ONLY Google 校验桩：dev profile 下生效，<b>忽略 idToken，恒解析成固定测试身份</b>。
 *
 * <p>用途：无真实 Google OAuth 凭证时验证「登录后」鉴权链路（L2 视觉/联调）——前端点 Google
 * 登录即落到同一测试账号（{@link #DEV_SUB}，由 {@code DevUserSeeder} 预置），换取真实自签 JWT，
 * 所有 {@code /api/v1/me} 等鉴权接口正常可用。
 *
 * <p>🔒 安全：仅 {@code @Profile("dev")} 生效，{@code @Primary} 在 dev 下盖过真实
 * {@link NimbusGoogleTokenVerifier}；prod profile 下本 bean <b>不注册</b>，恒用真实 JWKS 校验。
 * <b>绝不削弱生产鉴权</b>——生产部署须以 {@code SPRING_PROFILES_ACTIVE=prod} 运行。
 */
@Component
@Profile("dev")
@Primary
public class DevGoogleTokenVerifier implements GoogleTokenVerifier {

    /** 固定测试用户的 Google sub（与 DevUserSeeder 一致）。 */
    public static final String DEV_SUB = "dev-stub-user";

    private static final Logger log = LoggerFactory.getLogger(DevGoogleTokenVerifier.class);

    public DevGoogleTokenVerifier() {
        log.warn("⚠️ DEV Google 校验桩已启用（dev profile）：所有 Google 登录将落到固定测试账号 "
                + "sub={}。生产须以 prod profile 运行（真实 Nimbus 校验）。", DEV_SUB);
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        return new GoogleIdentity(DEV_SUB, "test@petgo.dev", "测试用户", null);
    }
}
