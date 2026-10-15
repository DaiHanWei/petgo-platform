package com.tailtopia.admin.opspopup.service;

import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupStatus;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;

/**
 * 后台列表的「状态」合一列（V1.3.4 后台返工清单 §0-1 · UI 稿 H1）。
 *
 * <p>表里仍只存 {@code ACTIVE} / {@code DISABLED}；展示时合一：{@code DISABLED} → 已停用，
 * 否则按时间窗现算 未开始 / 进行中 / 已结束（与 App 候选过滤同一份判定 {@link ScheduleWindow}）。
 * 顺序即汇总条的格子顺序（进行中 / 未开始 / 已结束 / 已停用）。
 */
public enum OpsPopupState {
    RUNNING,
    PENDING,
    ENDED,
    DISABLED;

    public static OpsPopupState of(OpsPopup p, Instant now) {
        if (p.getStatus() == OpsPopupStatus.DISABLED) {
            return DISABLED;
        }
        return switch (ScheduleWindow.phaseAt(now, p.getStartsAt(), p.getEndsAt())) {
            case PENDING -> PENDING;
            case ACTIVE -> RUNNING;
            case ENDED -> ENDED;
        };
    }

    /** 筛选参数 → 状态；空 / 非法值 = 不筛（null）。 */
    public static OpsPopupState parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
