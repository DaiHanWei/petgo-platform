package com.tailtopia.shared.security;

/**
 * Google ID Token 校验抽象（验签 / aud / iss / exp，解析 sub/email/name/picture）。
 *
 * <p>抽象为接口便于测试注入 stub（L0 用伪 verifier，免真实 Google 凭证）。
 * 校验失败抛 {@link com.tailtopia.shared.error.AppException#unauthorized}。
 */
public interface GoogleTokenVerifier {

    GoogleIdentity verify(String idToken);
}
