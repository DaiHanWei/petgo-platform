package com.tailtopia.admin.aiquery.service;

/** 对话不存在、属于他人或已过期：统一 404，不暴露是否存在（spec §5.3 / UX E1）。 */
public class AiQueryNotFound extends RuntimeException {

    public AiQueryNotFound() {
        super("ai query conversation not accessible", null, false, false);
    }
}
