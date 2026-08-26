package com.tailtopia.admin.contenttag.dto;

import java.time.Instant;

/** 装饰标签列表的一行（Story 11.2 · AB-10C）。 */
public record TagRow(long id, String code, String name, String icon, String description,
        Instant retiredAt, long activeAssignments) {

    /** 已下线：不可再分配，但已分配的照旧生效到各自 ends_at。 */
    public boolean retired() {
        return retiredAt != null;
    }
}
