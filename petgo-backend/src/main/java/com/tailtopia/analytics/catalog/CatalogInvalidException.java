package com.tailtopia.analytics.catalog;

/**
 * 事件清单 / 口径 yaml 解析或校验失败（analytics-pipeline Story 1.2）。
 *
 * <p>消息里必须带出错位置：语法错误给「第 N 行第 M 列」，结构错误给路径（如 {@code events[37].properties[2].name 重复}）。
 * 消息只含 yaml 文件本身的内容片段，不含任何事件数据。
 */
public class CatalogInvalidException extends Exception {

    public CatalogInvalidException(String message) {
        super(message);
    }

    public CatalogInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
