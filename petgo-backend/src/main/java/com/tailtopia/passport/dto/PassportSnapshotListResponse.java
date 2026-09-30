package com.tailtopia.passport.dto;

import java.time.Instant;
import java.util.List;

/** 已购版本列表（V1.3.2 Story 3.4 · AC7.1）：只含已付快照，按 paidAt 倒序。未付快照不出现。 */
public record PassportSnapshotListResponse(List<Item> items) {

    public record Item(String snapshotToken, Instant paidAt, int stampCount) {
    }
}
