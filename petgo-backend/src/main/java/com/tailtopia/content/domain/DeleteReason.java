package com.tailtopia.content.domain;

/**
 * 内容软删来源（Story 3.6 作者删 / Story 3.7 运营下架）。仅用于审计日志，不外泄给前端、不入库列。
 */
public enum DeleteReason {
    AUTHOR_DELETE,
    ADMIN_TAKEDOWN
}
