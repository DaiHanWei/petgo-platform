package com.petgo.account.web;

import com.petgo.account.service.AccountDeletionService;
import com.petgo.shared.error.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号注销端点（Story 7.3，PDP NFR-8）。{@code DELETE /api/v1/me}：双重确认 → 受理级联删除作业（202）。
 *
 * <p>仅作用于 JWT {@code sub} 本人（不接受任意 userId，防越权）。确认短语缺失 → 422。
 * 与退出登录（{@code POST /api/v1/auth/logout}，不删数据）严格区分。
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AccountDeletionService deletionService;

    public AccountController(AccountDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) DeleteAccountRequest req) {
        if (req == null || !req.confirmed()) {
            throw AppException.validation("请二次确认注销（删除后数据不可恢复）");
        }
        deletionService.requestDeletion(currentUserId(jwt));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build(); // 202 受理，异步级联删除
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
