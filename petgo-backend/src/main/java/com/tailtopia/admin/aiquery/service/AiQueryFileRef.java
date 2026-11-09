package com.tailtopia.admin.aiquery.service;

/** 一次下载要取的文件：已校验归属、在清单里、未过保留期。 */
public record AiQueryFileRef(String conversationToken, String messageToken, String name, long size) {
}
