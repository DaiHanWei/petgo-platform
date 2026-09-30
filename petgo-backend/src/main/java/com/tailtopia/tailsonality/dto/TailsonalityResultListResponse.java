package com.tailtopia.tailsonality.dto;

import java.util.List;

/** 结果列表（新 → 旧）。 */
public record TailsonalityResultListResponse(List<TailsonalityResultResponse> items) {
}
