package com.tailtopia.shared.im.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.im.UserSig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM UserSig 签发（Story 5.5）。{@code GET /api/v1/im/usersig}：按 JWT role 为自身 IM 账号签短时 UserSig，
 * 客户端 SDK 用其登录 IM。SecretKey 仅服务端持有，绝不下发；UserSig 短时。
 *
 * <p><b>签发条件（2026-08-07 推送接入决策，放宽原 5.5 MAU 硬门控）</b>：登录用户（USER/VET）一律签发。
 * 原门控「USER 须有进行中会话才签」会导致从未问诊的用户永远无法登录 IM → 无法注册 TIMPush 离线推送
 * → 点赞/评论/生日类系统推送（FR-22B/40~42）整体失效。放宽后所有活跃用户计入 IM MAU
 * （国际站免费档 1000 MAU，当前体量在额内；超出需升级套餐——产品已知悉取舍）。
 * 未登录仍 401；游客无 JWT 天然不可达。
 *
 * <p><b>签发前幂等导入账号（2026-09-24，bug 519/521）</b>：IM 账号带环境前缀后（stag {@code stg_u_<id>}），
 * 存量用户/兽医在腾讯侧还没有新账号——建号即注册只覆盖新注册，接单导入只覆盖问诊双方。
 * 故每个账号在本进程内首次签发前先 {@code ensureAccount}（account_import 幂等、不计 MAU；失败只 WARN 不阻断签发）。
 * 进程内去重避免每次取 UserSig 都打一次 REST；重启后会再导一次，幂等无害。
 * 不传昵称：避免用占位名覆盖腾讯侧已有的真实昵称。
 */
@RestController
@RequestMapping("/api/v1/im")
public class ImUserSigController {

    private static final Logger log = LoggerFactory.getLogger(ImUserSigController.class);

    private final TencentImClient imClient;

    /** 本进程已导入过的 IM 账号（有界：≤ 用户+兽医总数）。 */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

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
        if ("VET".equals(role)) {
            return sign(ImAccountMapper.vetImId(subjectId));
        }
        // role 显式白名单（code-review 2026-08-07）：放宽会话闸门后不能让未知/缺失 role 的
        // 主体默认落入 USER 分支拿到 IM 登录凭证——非 USER/VET 一律拒绝。
        if (!"USER".equals(role)) {
            throw AppException.forbidden("该角色不可使用实时会话");
        }
        return sign(ImAccountMapper.userImId(subjectId));
    }

    private UserSig sign(String imUserId) {
        if (ensured.add(imUserId)) {
            try {
                imClient.ensureAccount(imUserId, null);
            } catch (RuntimeException e) {
                // live 实现本身不抛；防御性兜底：导入失败不阻断签发（SDK 登录仍可用），下次再试。
                ensured.remove(imUserId);
                log.warn("[IM] 签发前导入账号失败（不阻断签发）: {}", e.getClass().getSimpleName());
            }
        }
        return imClient.signUserSig(imUserId);
    }
}
