package com.tailtopia.admin.usertag.dto;

import java.time.Instant;

/** 用户标签列表的一行（Story 11.3 · AB-12A）。 */
public record UserTagRow(long id, String code, String name, String icon, String description,
        Instant retiredAt, long activeAssignments) {

    public boolean retired() {
        return retiredAt != null;
    }
}
