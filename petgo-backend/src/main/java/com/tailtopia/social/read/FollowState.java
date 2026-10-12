package com.tailtopia.social.read;

/**
 * 当前查看者与某人之间的关注关系（V1.3.4 follow · Story 1.1 · AD-4 / AD-6）。
 *
 * @param iFollow   我是否关注了对方
 * @param followsMe 对方是否关注了我
 */
public record FollowState(boolean iFollow, boolean followsMe) {

    /** 互不关注（游客、缺失 id 一律取它）。 */
    public static final FollowState NONE = new FollowState(false, false);
}
