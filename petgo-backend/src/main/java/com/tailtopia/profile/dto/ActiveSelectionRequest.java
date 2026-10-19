package com.tailtopia.profile.dto;

import java.util.List;

/**
 * 改选「保持正常」的宠物（V1.4.0 multi-pet Story 2.1 · D13 · {@code PUT /pet-profiles/me/active-selection}）。
 *
 * @param petIds 选为保持正常的宠物 id；去重后必须恰好等于名额数（否则 422），逐只校验归属（非本人 404）
 */
public record ActiveSelectionRequest(List<Long> petIds) {
}
