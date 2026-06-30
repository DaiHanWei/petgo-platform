package com.tailtopia.admin.dto;

import java.time.Instant;
import java.util.List;

/**
 * 兽医在线态快照（Story 2.6，AB-2F）。{@code queriedAt} = 本次查询渲染时刻（快照、非实时）。
 */
public record VetOnlineSnapshot(List<Row> rows, Instant queriedAt) {

    /** 单行：id / 显示名 / 在线态（ONLINE/BUSY/OFFLINE）。 */
    public record Row(long id, String displayName, String presence) {
    }
}
