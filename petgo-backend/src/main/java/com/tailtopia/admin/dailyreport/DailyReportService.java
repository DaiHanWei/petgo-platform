package com.tailtopia.admin.dailyreport;

import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 飞书群日报编排：取数 → 拼卡片 → 推送。
 *
 * <p>两个入口：定时任务 {@link DailyReportJob} 调 {@link #pushScheduled()}（吞掉一切异常只记日志，
 * 不重试、不抛出 —— 推送失败绝不影响主业务）；管理端调 {@link #pushNow()}（失败抛业务异常，接口能回显原因）。
 */
@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    /** 切日时区：运营在雅加达。 */
    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final DailyReportQuery query;
    private final LarkWebhookClient lark;
    private final DailyReportProperties props;
    private final Clock clock;

    @Autowired
    public DailyReportService(DailyReportQuery query, LarkWebhookClient lark, DailyReportProperties props) {
        this(query, lark, props, Clock.systemUTC());
    }

    DailyReportService(DailyReportQuery query, LarkWebhookClient lark, DailyReportProperties props, Clock clock) {
        this.query = query;
        this.lark = lark;
        this.props = props;
        this.clock = clock;
    }

    /** 以 {@code today}（WIB）为发送日：统计「昨天」，环比「前天」。 */
    public DailyReport getDailyReport(LocalDate today) {
        LocalDate day = today.minusDays(1);
        return new DailyReport(day, query.metricsOf(day), query.metricsOf(day.minusDays(1)));
    }

    /** 管理端手动触发：失败抛业务异常（含原因），供接口回显。 */
    public DailyReport pushNow() {
        if (!props.isEnabled()) {
            throw AppException.serviceUnavailable("日报 webhook 未配置（LARK_DAILY_REPORT_WEBHOOK_URL）");
        }
        DailyReport report = getDailyReport(LocalDate.now(clock.withZone(WIB)));
        try {
            lark.send(DailyReportCard.build(report));
        } catch (LarkWebhookClient.LarkWebhookException e) {
            throw AppException.serviceUnavailable("日报推送失败：" + e.getMessage());
        }
        log.info("daily report pushed (manual) date={}", report.date());
        return report;
    }

    /** 定时任务入口：未配置则跳过；任何异常只记日志，不重试、不抛出。 */
    public void pushScheduled() {
        if (!props.isEnabled()) {
            log.info("daily report skipped: webhook not configured");
            return;
        }
        try {
            DailyReport report = getDailyReport(LocalDate.now(clock.withZone(WIB)));
            lark.send(DailyReportCard.build(report));
            log.info("daily report pushed date={}", report.date());
        } catch (Exception e) {
            // 不带 webhook（LarkWebhookException 的消息本身不含 URL）
            log.warn("daily report push failed: {} {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
