package com.tailtopia.triage.dto;

import com.tailtopia.triage.domain.UnlockMethod;
import jakarta.validation.constraints.NotNull;

/**
 * 解锁请求（Story 2.3，{@code POST /triage/{id}/unlock}）。{@code method} 指定解锁方式
 * （FREE_QUOTA/PAWCOIN/QRIS/DANA）。triage 由路径 {@code {id}} 定位，userId 取自 JWT（不入 body）。
 */
public record UnlockRequest(@NotNull UnlockMethod method) {
}
