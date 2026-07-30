package com.tailtopia.consult.service;

import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兽医月结生成调度（Story 3.7）。每月 1 日 01:00 WIB 生成<b>上个 WIB 月</b>月结——Spring 原生 {@code @Scheduled}
 * + DB 状态机幂等（唯一 {@code (vet_id, period)}），<b>禁 Quartz / Kafka / 任何调度或消息中间件</b>（架构护栏，
 * 照 {@code ScheduledPushJob}/{@code SipdhExpiryScanner}）。异常不 crash 调度（try/catch + 日志）。
 */
@Component
public class VetSettlementScanner {

    private static final Logger log = LoggerFactory.getLogger(VetSettlementScanner.class);
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final VetSettlementService service;

    public VetSettlementScanner(VetSettlementService service) {
        this.service = service;
    }

    /** 每月 1 日 01:00 WIB（cron: 秒 分 时 日 月 周）。生成上个 WIB 月月结。 */
    @Scheduled(cron = "${petgo.consult.settlement-cron:0 0 1 1 * *}", zone = "Asia/Jakarta")
    public void generateMonthly() {
        YearMonth lastMonth = YearMonth.now(WIB).minusMonths(1);
        try {
            int generated = service.generateSettlements(lastMonth);
            log.info("兽医月结生成完成 period={} generated={}", lastMonth, generated);
        } catch (RuntimeException e) {
            // 生成失败不 crash 调度；下次可重跑（幂等），或管理端手工补生成（9-5）。
            log.warn("兽医月结生成失败 period={} cause={}", lastMonth, e.getClass().getSimpleName());
        }
    }
}
