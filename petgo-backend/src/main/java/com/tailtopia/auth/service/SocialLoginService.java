package com.tailtopia.auth.service;

import com.tailtopia.auth.dto.LoginResponse;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Google / Apple 登录入口：吸收「同一账号首登并发建号」的唯一约束冲突。
 *
 * <p>{@link AuthService} 的登录是「先查后建」，两个并发请求可能都查不到账号、都去插入，
 * 后到者撞唯一约束。此时胜者已提交，败者只需重走一遍登录就能取到已建好的账号。
 *
 * <p>⚠️ 本类<b>不能</b>加 {@code @Transactional}：重试必须在败者事务回滚之后、用全新事务执行，
 * 同一事务内捕获异常再查会因事务已标记回滚而失败。
 */
@Service
public class SocialLoginService {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);

    static final String GOOGLE_SUB_CONSTRAINT = "uq_users_google_sub";
    static final String APPLE_SUB_CONSTRAINT = "uq_users_apple_sub";

    private final AuthService authService;

    public SocialLoginService(AuthService authService) {
        this.authService = authService;
    }

    public LoginResponse loginWithGoogle(String idToken) {
        return retryOnSignupRace(GOOGLE_SUB_CONSTRAINT, () -> authService.loginWithGoogle(idToken));
    }

    public LoginResponse loginWithApple(String identityToken) {
        return retryOnSignupRace(APPLE_SUB_CONSTRAINT, () -> authService.loginWithApple(identityToken));
    }

    /** 只重试一次，且只认对应身份列的唯一约束；其它完整性错误照常抛出，不被掩盖。 */
    private LoginResponse retryOnSignupRace(String constraint, Supplier<LoginResponse> login) {
        try {
            return login.get();
        } catch (DataIntegrityViolationException e) {
            String cause = e.getMostSpecificCause().getMessage();
            if (cause == null || !cause.contains(constraint)) {
                throw e;
            }
            log.warn("Concurrent first login hit {}, retrying once", constraint);
            return login.get();
        }
    }
}
