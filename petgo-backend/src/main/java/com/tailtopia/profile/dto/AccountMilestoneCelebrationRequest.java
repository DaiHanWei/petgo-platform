package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/v1/me/account-milestones/celebrations}（V1.5.0 onboarding-recall Story 2.3 · AC2）：
 * 客户端展示过的账号级编码（任一物种写法都认；非账号级编码静默忽略）。
 */
public record AccountMilestoneCelebrationRequest(@NotNull @Size(max = 50) List<String> codes) {
}
