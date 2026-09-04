package com.tailtopia.notify.lifecycle;

import com.tailtopia.notify.domain.LifecyclePushMark;
import com.tailtopia.notify.repository.LifecyclePushMarkRepository;
import com.tailtopia.notify.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 生命周期推送逐条投递器（留存手册抓手 1）。{@code @Async} 逐条经 6.1 {@link NotificationService} 下发，
 * 沿用 Story 6.7 的范式 —— 单条失败仅记日志、不阻塞后续、不重试风暴、<b>不引入 MQ</b>。
 *
 * <p>去重：先插 {@code lifecycle_push_marks}（唯一约束为单一事实源），撞约束即已推 → 跳过投递
 * （并发/重扫安全；at-most-once，宁可漏推一条也绝不重复打扰 —— 重复打扰换来的是关推送权限甚至卸载）。
 */
@Component
public class LifecyclePushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LifecyclePushDispatcher.class);

    /** 未建档时文案里的宠物名占位（印尼语「你的毛孩子」；与 Story 6.7 dispatcher 同口径）。 */
    private static final String PET_NAME_FALLBACK = "hewanmu";

    private final LifecyclePushMarkRepository marks;
    private final NotificationService notificationService;

    public LifecyclePushDispatcher(LifecyclePushMarkRepository marks,
            NotificationService notificationService) {
        this.marks = marks;
        this.notificationService = notificationService;
    }

    /**
     * 投递一条。返回值仅用于日志统计；调用方不依赖（{@code @Async} 下拿不到）。
     *
     * <p>{@code targetRef} 下发 variant 名，客户端据此选深链落点
     * （沿用 {@code NAME_RESET}/{@code AVATAR_RESET} 范式：走 targetRef 而非随机 token
     * —— [notify 跳转改用 targetRef] 的教训，随机 token 在目标页会 {@code int.parse} 抛异常）。
     */
    @Async
    public void dispatch(LifecyclePlannedPush push) {
        try {
            if (marks.existsByUserIdAndPushKindAndNodeKey(
                    push.userId(), push.type().name(), push.nodeKey())) {
                return; // 已推过，跳过。
            }
            // 🔴 召回「每月至多一次」按滚动 30 天判定：nodeKey 是日历月，9/30 与 10/1 会各推一条。
            if (push.type() == com.tailtopia.notify.domain.NotificationType.LIFECYCLE_WINBACK
                    && marks.findFirstByUserIdAndPushKindOrderByPushedAtDesc(
                            push.userId(), push.type().name())
                    .map(m -> m.getPushedAt() != null && m.getPushedAt()
                            .isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(30))))
                    .orElse(false)) {
                return;
            }
            // 先落去重标记（唯一约束兜底并发）；再投递。
            marks.save(LifecyclePushMark.of(push.userId(), push.type().name(), push.nodeKey(),
                    push.variant().name()));
            String petName = push.petName() == null || push.petName().isBlank()
                    ? PET_NAME_FALLBACK : push.petName();
            notificationService.sendWithCopy(push.userId(), push.type(), push.copyKey(),
                    new Object[] {petName}, fallbackTitle(push), fallbackBody(push, petName),
                    push.variant().name());
        } catch (DataIntegrityViolationException dup) {
            // 并发/重扫撞唯一约束 → 已推，安全跳过。
        } catch (RuntimeException e) {
            // 单条失败不阻塞其余（不记 PII/健康数据/令牌，也不记宠物名）。
            log.warn("lifecycle push dispatch failed: kind={} variant={}",
                    push.type(), push.variant());
        }
    }

    /**
     * i18n 缺键时的兜底标题（印尼语，市场主语言）。
     *
     * <p>兜底串刻意也写全 —— 缺键回退成一句英文调试文本，用户是会真的收到的。
     */
    private String fallbackTitle(LifecyclePlannedPush push) {
        return switch (push.variant()) {
            case CREATE_PROFILE -> "Tinggal satu langkah lagi";
            case RECORD -> "Catat momen hari ini";
            case FEED -> "Lihat kabar anabul lain";
            case REVIEW -> "Rangkuman minggu ini";
        };
    }

    private String fallbackBody(LifecyclePlannedPush push, String petName) {
        return switch (push.variant()) {
            case CREATE_PROFILE -> "Bikin profil anabulmu, cuma butuh 30 detik";
            case RECORD -> "Catat satu momen " + petName + " hari ini";
            case FEED -> "Lihat apa yang dilakukan anabul lain hari ini";
            case REVIEW -> "Lihat perkembangan " + petName + " minggu ini";
        };
    }
}
