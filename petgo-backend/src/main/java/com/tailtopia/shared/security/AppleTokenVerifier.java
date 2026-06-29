package com.tailtopia.shared.security;

/**
 * Apple identity token 校验抽象（验签 / aud / iss / exp，解析 sub/email）。FR-44（1.1.0）。
 *
 * <p>与 {@link GoogleTokenVerifier} 平行：抽象为接口便于测试注入 stub（L0 用伪 verifier，
 * 免真实 Apple 凭证）。校验失败抛 {@link com.tailtopia.shared.error.AppException#unauthorized}。
 */
public interface AppleTokenVerifier {

    AppleIdentity verify(String identityToken);
}
