package com.petgo.shared.im.web;

import com.petgo.shared.error.AppException;
import com.petgo.shared.im.ImAccountMapper;
import com.petgo.shared.im.TencentImClient;
import com.petgo.shared.im.UserSig;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM UserSig 签发（Story 5.5）。{@code GET /api/v1/im/usersig}：按 JWT role 为自身 IM 账号签短时 UserSig，
 * 客户端 SDK 用其登录 IM。SecretKey 仅服务端持有，绝不下发；UserSig 短时。
 */
@RestController
@RequestMapping("/api/v1/im")
public class ImUserSigController {

    private final TencentImClient imClient;

    public ImUserSigController(TencentImClient imClient) {
        this.imClient = imClient;
    }

    @GetMapping("/usersig")
    public UserSig userSig(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        long subjectId;
        try {
            subjectId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
        String role = jwt.getClaimAsString("role");
        String imUserId = "VET".equals(role)
                ? ImAccountMapper.vetImId(subjectId)
                : ImAccountMapper.userImId(subjectId);
        return imClient.signUserSig(imUserId);
    }
}
