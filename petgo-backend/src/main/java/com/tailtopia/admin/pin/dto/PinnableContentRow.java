package com.tailtopia.admin.pin.dto;

import java.time.Instant;

/** 顶置内容选择器的一行候选（Story 11.1）。只含选择所需的最少字段。 */
public record PinnableContentRow(long id, String type, String summary, Instant createdAt) {
}
