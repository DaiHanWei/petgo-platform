package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.PinObjectType;
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
        validateObject(pin);
        requireNoOverlap(pin.getSlot(), pin.getStartsAt(), pin.getEndsAt(), null);
        return pins.save(pin);
    }

    /**
     * 两类对象各自的必填项（Story 4.3 · FR-68）。
     *
     * <p>数据库已有互斥约束兜底，这里再拦一道只为给运营一句**人话**提示 ——
     * 约束违例抛出来的是一串英文约束名。
     */
    private void validateObject(ContentPin pin) {
        if (pin.getObjectType() == PinObjectType.PROMO) {
            if (isBlank(pin.getPromoImageUrl()) || isBlank(pin.getPromoTitle())) {
                throw AppException.validation("推广卡片的图片与标题为必填")
                        .code("admin.err.pins.promoFieldsRequired");
            }
        } else if (pin.getContentId() == null) {
            throw AppException.validation("请选择要顶置的内容")
                    .code("admin.err.pins.contentRequired");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void validateWindow(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw AppException.validation("结束时间必须晚于开始时间")
                    .code("admin.err.pins.endNotAfterStart");
        }
    }

    private void requireNoOverlap(String slot, Instant startsAt, Instant endsAt, Long excludeId) {
        List<ContentPin> conflicts = pins.findOverlapping(slot, startsAt, endsAt, excludeId);
        if (!conflicts.isEmpty()) {
            throw AppException.validation("该坑位在此时间段已有顶置排期，请调整时间窗")
                    .code("admin.err.pins.overlap");
        }
    }

    /**
     * 修改一条既有排期的时间窗 / 对象（Story 11.1 · AB-10A）。
     *
     * <p>🔴 重叠校验**排除自身**（`excludeId`）—— 否则"把自己的时间窗往后挪一小时"
     * 会被自己拦住。`findOverlapping` 早就留了这个参数，之前没有调用方。
     */
    @Transactional
    public ContentPin update(long id, Instant startsAt, Instant endsAt, ContentPin patch) {
        ContentPin pin = pins.findById(id)
                .orElseThrow(() -> AppException.notFound("顶置排期不存在")
                        .code("admin.err.pins.notFound"));
        validateWindow(startsAt, endsAt);
        requireNoOverlap(pin.getSlot(), startsAt, endsAt, id);
        pin.reschedule(startsAt, endsAt);
        if (patch != null) {
            pin.retarget(patch.getObjectType(), patch.getContentId(),
                    patch.getPromoImageUrl(), patch.getPromoTitle(), patch.getPromoLinkUrl());
            validateObject(pin);
        }
        return pin;
    }

    /**
     * 运营手动提前结束一条排期（Story 11.1 · AB-10A）。
     *
     * <p>🛡 写 `terminatedAt`、**绝不覆盖 `endsAt`** —— 覆盖了运营只会看到
     * 「这条 14:32 结束了」，**无从知道是排期到点还是被人提前收的**，排期意图的记录也没了。
     *
     * <p>已结束的（自然到点或已被结束）视为幂等 no-op，返回 false。
     */
    @Transactional
    public boolean terminateNow(long id, Instant at) {
        ContentPin pin = pins.findById(id)
                .orElseThrow(() -> AppException.notFound("顶置排期不存在")
                        .code("admin.err.pins.notFound"));
        // 领域对象自带幂等守卫，并保证 terminatedAt <= endsAt（满足 DB 约束）。
        return pin.terminateAt(at);
    }

    /** 后台列表：某坑位的全部排期（含已结束的历史），开始时间倒序。 */
    @Transactional(readOnly = true)
    public List<ContentPin> listBySlot(String slot) {
        return pins.findBySlotOrderByStartsAtDesc(slot);
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
