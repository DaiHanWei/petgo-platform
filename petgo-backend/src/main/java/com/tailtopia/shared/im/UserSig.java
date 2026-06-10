package com.tailtopia.shared.im;

/**
 * IM UserSig 签发结果（Story 5.5）。客户端 SDK 用 {@code userSig} + {@code sdkAppId} 登录 IM。
 * SecretKey 绝不出现在本结构（仅服务端持有）。
 */
public record UserSig(String imUserId, String userSig, String sdkAppId, long expireSeconds) {
}
