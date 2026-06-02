package com.petgo.shared.im;

/**
 * 腾讯 IM 编排客户端（Story 5.5）。后端经 IM REST 建会话 / 发系统消息 / 签 UserSig，
 * <b>后端不持长连接</b>，实时收发由客户端 SDK 直连（架构 §API & Communication）。
 *
 * <p>实现二选一（{@code petgo.im.mode}）：
 * <ul>
 *   <li>{@link StubTencentImClient}（stub，默认）：本地生成会话 id + 占位 UserSig，免凭证验状态机（L0/L1）。</li>
 *   <li>live：接真实腾讯 IM REST/SDK（L2，需真机 + SDKAppID/SecretKey），本批次未实现。</li>
 * </ul>
 */
public interface TencentImClient {

    /** 为一对 user/vet 建会话，返回 {@code im_conversation_id}（写入 consult_sessions）。 */
    String createConversation(String userImId, String vetImId);

    /** 为指定 IM 账号签发短时 UserSig（客户端 SDK 登录用）。SecretKey 绝不下发。 */
    UserSig signUserSig(String imUserId);

    /** 向会话发系统消息（如「兽医已接受你的问诊，点击开始对话」）。 */
    void sendSystemMessage(String conversationId, String text);

    /** 校验腾讯 IM 服务端回调签名/token（非法回调拒绝）。 */
    boolean verifyCallback(String token);
}
