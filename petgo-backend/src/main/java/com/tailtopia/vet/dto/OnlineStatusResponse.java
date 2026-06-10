package com.tailtopia.vet.dto;

import com.tailtopia.vet.domain.VetPresenceStatus;

/** 兽医自身在线态（Story 5.2 工作台「我的」Tab 渲染开关初值）。 */
public record OnlineStatusResponse(boolean online, String status) {

    public static OnlineStatusResponse of(VetPresenceStatus status) {
        return new OnlineStatusResponse(status == VetPresenceStatus.ONLINE, status.name());
    }
}
