package com.tailtopia.content.event;

/** {@link CommentRemovedEvent} 的原因：作者自删 / 运营下架（V1.3.0 Story 4.3）。 */
public enum CommentRemovedReason {
    AUTHOR_DELETE,
    ADMIN_TAKEDOWN
}
