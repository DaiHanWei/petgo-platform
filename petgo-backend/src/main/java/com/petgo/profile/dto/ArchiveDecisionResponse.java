package com.petgo.profile.dto;

import com.petgo.profile.domain.ArchiveDecision;

/**
 * 存档决策结果（Story 2.5）。{@code alreadyDecided=true} 表示此 sourceRef 已决策过（幂等无操作）。
 */
public record ArchiveDecisionResponse(
        String sourceRef,
        ArchiveDecision decision,
        boolean alreadyDecided) {
}
