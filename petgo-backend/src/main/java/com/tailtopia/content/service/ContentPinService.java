package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.SchedulePhase;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 顶置排期的写入与判定（V1.1.6 Story 4.1 · FR-68 / AD-8 / AD-9）。
 *
 * <p>⚠️ **本 story 没有对外接口** —— 后台配置界面（AB-10A）不在本轮范围，运营还无处配置。
 * 本类交付的是**机制**，供后台接上来之后直接调用，以及 Story 4.2 的坑位取数使用。
 */
@Service
public class ContentPinService {

    private final ContentPinRepository pins;

    public ContentPinService(ContentPinRepository pins) {
        this.pins = pins;
    }

    /**
     * 新增一条排期。
     *
     * <p>🛡 **同一坑位的时间窗不可重叠**（AD-9 Rule 5）—— 重叠即拦截。
     * 首尾相接不算重叠：10:00–12:00 与 12:00–14:00 是合法排期。
     */
    @Transactional
    public ContentPin schedule(ContentPin pin) {
        validateWindow(pin.getStartsAt(), pin.getEndsAt());
        requireNoOverlap(pin.getSlot(), pin.getStartsAt(), pin.getEndsAt(), null);
        return pins.save(pin);
    }

    private void validateWindow(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw AppException.validation("结束时间必须晚于开始时间");
        }
    }

    private void requireNoOverlap(String slot, Instant startsAt, Instant endsAt, Long excludeId) {
        List<ContentPin> conflicts = pins.findOverlapping(slot, startsAt, endsAt, excludeId);
        if (!conflicts.isEmpty()) {
            throw AppException.validation("该坑位在此时间段已有顶置排期，请调整时间窗");
        }
    }

    /** 某坑位当前生效中的排期；无则空。 */
    @Transactional(readOnly = true)
    public Optional<ContentPin> activePin(String slot, Instant now) {
        return pins.findActiveOne(slot, now);
    }

    /**
     * 后台列表的「待生效 / 生效中 / 已结束」。
     *
     * <p>🛡 走的就是那份**唯一判定**，与取数用的 SQL 条件同口径 —— 各写一遍就会出现
     * 「App 上已失效、后台还显示生效中」。
     */
    public SchedulePhase phaseOf(ContentPin pin, Instant now) {
        return ScheduleWindow.phaseAt(now, pin.getStartsAt(), pin.effectiveEnd());
    }

    /**
     * 下架联动：把引用该内容、尚未结束的排期提前结束（幂等）。
     *
     * @return 本次真正被结束的条数
     */
    @Transactional
    public int terminateForContent(long contentId, Instant at) {
        return pins.terminateByContent(contentId, at);
    }

    /** 注销联动：某作者名下内容整体不再可展示时一并收手。 */
    @Transactional
    public int terminateForAuthor(long authorId, Instant at) {
        return pins.terminateByAuthor(authorId, at);
    }
}
