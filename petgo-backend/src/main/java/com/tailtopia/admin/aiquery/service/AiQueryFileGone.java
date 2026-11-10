package com.tailtopia.admin.aiquery.service;

/** 文件已超过保留期 / 已从 agent 清除（UX C4）。 */
public class AiQueryFileGone extends RuntimeException {

    public AiQueryFileGone() {
        super("ai query export file gone", null, false, false);
    }
}
