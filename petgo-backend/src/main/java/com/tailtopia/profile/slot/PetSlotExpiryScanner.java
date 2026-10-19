package com.tailtopia.profile.slot;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月租名额到期每小时扫描（V1.4.0 multi-pet Story 2.3 · AD-7，照 {@code SipdhExpiryScanner}）。只做调度，业务在
 * {@link PetSlotExpiryService#scan}。{@code @Scheduled} + DB 状态机幂等去重（{@code status} / {@code reminded_at}），
 * <b>禁 MQ / 调度中间件 / 缓存层</b>；单次失败不 crash 调度。日志只记计数与异常类名（不记宠物名 / 用户名）。
 *
 * <p>cron 默认每小时第 5 分（避开整点其它任务），经 {@code petgo.pet-slot.expiry-scan-cron} 覆盖；{@code "-"} = 关闭
 * （集成测试基类即如此，扫描由测试直接调 {@link PetSlotExpiryService#scan}）。
 */
@Component
public class PetSlotExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(PetSlotExpiryScanner.class);

    private final PetSlotExpiryService expiryService;
    private final Clock clock;

    @Autowired
    public PetSlotExpiryScanner(PetSlotExpiryService expiryService) {
        this(expiryService, Clock.systemUTC());
    }

    PetSlotExpiryScanner(PetSlotExpiryService expiryService, Clock clock) {
        this.expiryService = expiryService;
        this.clock = clock;
    }

    @Scheduled(cron = "${petgo.pet-slot.expiry-scan-cron:0 5 * * * *}", zone = "UTC")
    public void scan() {
        try {
            PetSlotExpiryService.ScanResult r = expiryService.scan(clock.instant());
            if (r.hasWork()) {
                log.info("宠物名额到期扫描完成 expiredUsers={} expiredRentals={} expiredNotices={} remindedRentals={}"
                                + " remindNotices={} failedUsers={}", r.expiredUsers(), r.expiredRentals(),
                        r.expiredNotices(), r.remindedRentals(), r.remindNotices(), r.failedUsers());
            }
        } catch (RuntimeException e) {
            log.warn("宠物名额到期扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
