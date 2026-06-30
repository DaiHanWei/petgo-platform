package com.tailtopia.admin.vetqual.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SIPDH 到期每日扫描（Story 2.8，AB-2H）。{@code @Scheduled} + DB 状态机幂等去重——
 * 到期→EXPIRED（自动停接单，2.1 门控联动）、≤30 天→EXPIRING_SOON（仍可接单）。
 * <b>禁 MQ/调度/缓存中间件（F5）</b>，沿用 {@code ConsultCloseScanner} 范式。系统行为不写审计；日志不含证件 PII。
 */
@Component
public class SipdhExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(SipdhExpiryScanner.class);

    private final VetQualificationService qualificationService;

    public SipdhExpiryScanner(VetQualificationService qualificationService) {
        this.qualificationService = qualificationService;
    }

    /** 每日扫描（默认每天 03:00 UTC，cron 可经 env 覆盖）。 */
    @Scheduled(cron = "${petgo.vet.sipdh-expiry-scan-cron:0 0 3 * * *}", zone = "UTC")
    public void scan() {
        try {
            VetQualificationService.ScanResult r = qualificationService.scanExpiry(LocalDate.now(ZoneOffset.UTC));
            if (r.expired() > 0 || r.warned() > 0) {
                log.info("SIPDH 到期扫描完成 expired={} expiringSoon={}", r.expired(), r.warned());
            }
        } catch (RuntimeException e) {
            log.warn("SIPDH 到期扫描失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
