package com.tailtopia.admin.alert;

import com.tailtopia.admin.dailyreport.LarkWebhookClient;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 电商运营提醒（V1.4.2 shop-admin Story 1.3 · AD-5 / 决策 O-10）：按 {@link OpsChannel} 发到客服 / 运营 / 技术三个飞书群。
 *
 * <ul>
 *   <li>🔴 <b>事务提交后才发</b>：在活动事务内调用 → 挂 {@code afterCommit}；事务回滚则不发（那件事本就没发生）。
 *       不在事务里出网，免得一次飞书往返拖着行锁。</li>
 *   <li>🔴 <b>发送失败只 WARN、绝不抛</b>：提醒是「有人知道」，不能反过来打断业务。</li>
 *   <li><b>去重</b>：{@code dedupeKey} 非空时同键只发一次（{@code ops_alert_log}，独立短事务插入；发送失败删掉该行，
 *       下次同一事件还能再发）。{@code dedupeKey} 为空 = 不去重（技术类沿用调用方现有节流）。</li>
 *   <li>群未配置（URL 为空）→ 只写 WARN，不记去重行。</li>
 *   <li>🔒 文案不得含手机号、地址、收款账户：发送前兜底把手机号形态与 ≥ 10 位连续数字（账号形态）打码为末 4 位。
 *       日志不记文案、不记 webhook URL。</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(OpsAlertProperties.class)
public class OpsAlertService {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertService.class);

    /**
     * 印尼手机号形态：{@code +62 / 62 / 0} 开头接 {@code 8}，后续可带单个空格或短横线分组（如 {@code +62 812-3456-7890}）。
     * 前后不贴字母数字，订单展示号 {@code TOKO-20260819-000673}、日期、HTTP 状态码不会命中。
     */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])(?:\\+?62|0)[ -]?8\\d{1,3}(?:[ -]?\\d{3,4}){2,3}(?![\\w])");
    /** 银行 / 电子钱包账号形态：连续 ≥ 10 位数字（不含分隔符），前后不贴字母数字。 */
    private static final Pattern ACCOUNT = Pattern.compile("(?<![\\w-])\\d{10,}(?![\\w-])");

    private final OpsAlertProperties props;
    private final LarkWebhookClient lark;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    @Autowired
    public OpsAlertService(OpsAlertProperties props, LarkWebhookClient lark, JdbcTemplate jdbc,
            PlatformTransactionManager txm) {
        this.props = props;
        this.lark = lark;
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 发一条提醒。
     *
     * @param channel   哪个群
     * @param dedupeKey 去重键（如 {@code late-paid:{orderToken}}）；null / 空 = 不去重
     * @param text      文案（🔒 不含手机号、地址、收款账户）
     */
    public void send(OpsChannel channel, String dedupeKey, String text) {
        if (channel == null || text == null || text.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(channel, dedupeKey, text);
                }
            });
            return;
        }
        deliver(channel, dedupeKey, text);
    }

    void deliver(OpsChannel channel, String dedupeKey, String text) {
        try {
            OpsAlertProperties.Target target = props.of(channel);
            if (!target.isConfigured()) {
                log.warn("电商运营提醒未配置飞书群，未发送 channel={}", channel);
                return;
            }
            boolean dedupe = dedupeKey != null && !dedupeKey.isBlank();
            if (dedupe && !claim(channel, dedupeKey)) {
                return;
            }
            try {
                lark.sendTo(target.getWebhookUrl(), target.getSecret(),
                        Map.of("msg_type", "text", "content", Map.of("text", redact(text))));
            } catch (RuntimeException e) {
                log.warn("电商运营提醒发送失败 channel={} cause={}", channel, e.getClass().getSimpleName());
                if (dedupe) {
                    release(dedupeKey);
                }
            }
        } catch (RuntimeException e) {
            log.warn("电商运营提醒处理失败 channel={} cause={}", channel, e.getClass().getSimpleName());
        }
    }

    /** 抢去重行：插入成功 = 第一次，发；冲突 = 已发过，不发。 */
    private boolean claim(OpsChannel channel, String dedupeKey) {
        Integer n = requiresNew.execute(st -> jdbc.update(
                "INSERT INTO ops_alert_log (dedupe_key, channel) VALUES (?, ?) ON CONFLICT (dedupe_key) DO NOTHING",
                dedupeKey, channel.name()));
        return n != null && n > 0;
    }

    private void release(String dedupeKey) {
        try {
            requiresNew.executeWithoutResult(st -> jdbc.update("DELETE FROM ops_alert_log WHERE dedupe_key = ?", dedupeKey));
        } catch (RuntimeException e) {
            log.warn("电商运营提醒去重行回退失败 cause={}", e.getClass().getSimpleName());
        }
    }

    /** 🔒 兜底打码：长串数字（手机号 / 账号形态）只留末 4 位。 */
    static String redact(String text) {
        String out = mask(PHONE, text);
        return mask(ACCOUNT, out);
    }

    private static String mask(Pattern p, String text) {
        return p.matcher(text).replaceAll(m -> {
            String digits = m.group().replaceAll("[^0-9]", "");
            return "****" + digits.substring(digits.length() - 4);
        });
    }
}
