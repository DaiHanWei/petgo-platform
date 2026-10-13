package com.tailtopia.ops.popup.dto;

import java.util.List;

/** {@code GET /api/v1/ops-popups/candidates} 响应。无候选 → {@code {"items": []}}（不是 204）。 */
public record OpsPopupCandidatesResponse(List<OpsPopupCandidateView> items) {
}
