package com.tailtopia.vet.dto;

import jakarta.validation.constraints.NotNull;

/** 兽医在线/离线切换请求（Story 5.2）。 */
public record OnlineStatusRequest(@NotNull Boolean online) {
}
