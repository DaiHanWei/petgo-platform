package com.tailtopia.admin.virtual.dto;

/**
 * 纳入身份池时的候选账号（V1.1.6 Story 12.1 · AC3）。
 *
 * @param alreadyInPool 已在池内 ⇒ 界面上不该再给"纳入"按钮（否则点了才报错）
 */
public record RealAccountCandidate(long userId, String nickname, String avatarUrl,
        boolean alreadyInPool, boolean deactivated) {
}
