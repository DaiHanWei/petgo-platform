package com.tailtopia.shared.im;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 腾讯 IM 打桩客户端（Story 5.5，{@code petgo.im.mode=stub} 默认装配）。
 *
 * <p>使接单 CAS / WAITING→IN_PROGRESS / BUSY / 会话查询在<b>无真实 IM 凭证</b>下可 L0/L1 验证：
 * 本地生成 {@code im_conversation_id}、占位 UserSig；系统消息仅记非敏感日志。
 * 真实 IM 收发/建会话属 L2（需真机 + SDKAppID/SecretKey），不在此桩覆盖。
 *
 * <p>护栏：占位 UserSig <b>不是</b>真实凭证（前缀 {@code stub-}），不可用于真实 IM 登录；
 * 绝不在日志打印 UserSig / SecretKey / 消息正文。
 */
public class StubTencentImClient implements TencentImClient {

    private static final Logger log = LoggerFactory.getLogger(StubTencentImClient.class);

    private final ImProperties props;

    public StubTencentImClient(ImProperties props) {
        this.props = props;
    }

    @Override
    public void ensureAccount(String imUserId, String displayName) {
        // 桩：不真导入；仅记非敏感日志（不打印 displayName 以外的 PII，displayName 是兽医对外昵称，非敏感）。
        log.debug("[IM-stub] ensure account {}", imUserId);
    }

    @Override
    public String createConversation(String userImId, String vetImId) {
        // 本地确定性会话 id（真实 IM 由腾讯返回）。
        return "stub-conv-" + UUID.randomUUID();
    }

    @Override
    public UserSig signUserSig(String imUserId) {
        // 占位 UserSig（非真实凭证）；TTL 取配置。绝不落日志。
        String sig = "stub-usersig-" + imUserId;
        return new UserSig(imUserId, sig, props.getSdkAppId(), props.getUserSigTtlSeconds());
    }

    @Override
    public void sendSystemMessage(String conversationId, String text) {
        // 桩：仅记会话 id（非敏感），不打印正文。
        log.debug("[IM-stub] system message queued for conversation {}", conversationId);
    }

    @Override
    public void pushOffline(String imUserId, String title, String body, String deepLinkType, String deepLinkToken) {
        // 桩：仅记非敏感字段（不打印 body 正文/token）。真实 APNs/FCM 经 IM 离线通道属 L2。
        log.debug("[IM-stub] offline push to {} type={}", imUserId, deepLinkType);
    }

    @Override
    public void deleteUserConversationMedia(String imUserId) {
        // 桩：仅记非敏感日志。真实 IM 删媒体属 L2；亦可依赖 IM 侧媒体 TTL 自动清理（见 Completion Notes）。
        log.debug("[IM-stub] delete conversation media for {}", imUserId);
    }

    @Override
    public boolean verifyCallback(String token) {
        // 桩：配置了 callbackToken 则比对，否则放行（仅 stub/本地）。
        String expected = props.getCallbackToken();
        return expected == null || expected.isBlank() || expected.equals(token);
    }
}
