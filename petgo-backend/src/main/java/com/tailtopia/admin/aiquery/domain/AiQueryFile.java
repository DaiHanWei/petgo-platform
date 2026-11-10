package com.tailtopia.admin.aiquery.domain;

/** 导出文件清单项（{@code ai_query_messages.files} jsonb 元素）。文件本体在 agent 上，按对话隔离。 */
public record AiQueryFile(String name, long size) {
}
