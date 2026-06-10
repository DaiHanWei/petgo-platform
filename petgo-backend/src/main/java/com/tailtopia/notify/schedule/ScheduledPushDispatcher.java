package com.petgo.notify.schedule;

import com.petgo.notify.domain.ScheduledPushMark;
import com.petgo.notify.repository.ScheduledPushMarkRepository;
import com.petgo.notify.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 定时推送逐条投递器（Story 6.7 · 决策 F5）。{@code @Async} 逐条经 6.1 {@link NotificationService} 下发，
 * 沿用 6.2「批量循环走 @Async」范式——单条失败仅记日志、不阻塞后续、不重试风暴、<b>不引入 MQ</b>。
 *
 * <p>去重：先插 {@code scheduled_push_marks}（唯一约束为单一事实源），撞约束即已推 → 跳过投递
 * （并发/重扫安全；at-most-once，宁可漏推一条提醒也不重复打扰）。
 * 文案为中文常量（与 6.3 既有推送一致；推送内容 i18n 是既有 V1 缺口，非本 Story 范围）。
 */
@Component
public class ScheduledPushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPushDispatcher.class);

    private final ScheduledPushMarkRepository marks;
    private final NotificationService notificationService;

    public ScheduledPushDispatcher(ScheduledPushMarkRepository marks,
            NotificationService notificationService) {
        this.marks = marks;
        this.notificationService = notificationService;
    }

    @Async
    public void dispatch(PlannedPush push) {
        try {
            if (marks.existsByPetProfileIdAndPushKindAndNodeKey(
                    push.petProfileId(), push.type().name(), push.nodeKey())) {
                return; // 已推过，跳过。
            }
            // 先落去重标记（唯一约束兜底并发）；再投递。
            marks.save(ScheduledPushMark.of(push.petProfileId(), push.type().name(), push.nodeKey()));
            String[] text = buildText(push);
            // recipient = 档案主人；deepLinkType = type 名（客户端 pushPayloadToLocation 据此映射固定目标）。
            // targetRef 内部存 petProfileId（不外泄）；deepLinkToken 由 send 自动生成（不可枚举）。
            notificationService.send(push.ownerId(), push.type(), text[0], text[1],
                    push.type().name(), String.valueOf(push.petProfileId()));
        } catch (DataIntegrityViolationException dup) {
            // 并发/重扫撞唯一约束 → 已推，安全跳过。
        } catch (RuntimeException e) {
            // 单条失败不阻塞其余（不记 PII/健康数据/令牌）。
            log.warn("scheduled push dispatch failed: kind={} profileId={}",
                    push.type(), push.petProfileId());
        }
    }

    /** 印尼语推送文案（[title, body]，市场主语言）。不含健康数据明文。 */
    private String[] buildText(PlannedPush push) {
        String name = push.petName() == null ? "hewanmu" : push.petName();
        return switch (push.type()) {
            case PET_BIRTHDAY -> new String[] {
                    "🎂 Pengingat ulang tahun",
                    name + " besok berusia " + push.number() + " tahun! Catat momen spesial yuk"};
            case COMPANION_ANNIVERSARY -> new String[] {
                    "🎉 Hari jadi kebersamaan",
                    "Kamu dan " + name + " sudah bersama " + push.number()
                            + " hari — lihat kisah tumbuh kembangnya"};
            case MILESTONE_NODE -> new String[] {
                    "🎖️ Tonggak tumbuh kembang",
                    name + " akan segera merayakan ulang tahun pertamanya — tonggak yang patut dikenang"};
            default -> new String[] {"Pengingat", ""};
        };
    }
}
