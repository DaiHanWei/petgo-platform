package com.tailtopia.shared.im.web;

import com.tailtopia.consult.service.ConsultSessionService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.im.UserSig;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM UserSig 签发（Story 5.5）。{@code GET /api/v1/im/usersig}：按 JWT role 为自身 IM 账号签短时 UserSig，
 * 客户端 SDK 用其登录 IM。SecretKey 仅服务端持有，绝不下发；UserSig 短时。
 *
 * <p><b>MAU 硬门控（5.5 live 增量）</b>：腾讯 IM 按「当月成功 SDK login 的去重用户」计 MAU。
 * 控制点即「谁/何时签发 UserSig」——
 * <ul>
 *   <li>{@code role=VET}：<b>恒签</b>（兽医上线即 login，已决策）。</li>
 *   <li>非 VET（USER）：须有「进行中/待关闭」会话才签发，否则 <b>403</b>，锁死无关用户吃 MAU。</li>
 * </ul>
 * 经 {@link ConsultSessionService} <b>service 接口</b>查询（不跨模块直访 consult repository）。
 */
@RestController
@RequestMapping("/api/v1/im")
public class ImUserSigController {

    private final TencentImClient imClient;
    private final ConsultSessionService consultSessions;

    public ImUserSigController(TencentImClient imClient, ConsultSessionService consultSessions) {
        this.imClient = imClient;
        this.consultSessions = consultSessions;
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
        if ("VET".equals(role)) {
            return imClient.signUserSig(ImAccountMapper.vetImId(subjectId));
        }
        // 用户态硬门控：无「进行中/待关闭」会话不签发（控 MAU）。
        if (!consultSessions.hasImLoginEligibleSession(subjectId)) {
            throw AppException.forbidden("无进行中的咨询，暂不可建立实时会话");
        }
        return imClient.signUserSig(ImAccountMapper.userImId(subjectId));
    }
}
