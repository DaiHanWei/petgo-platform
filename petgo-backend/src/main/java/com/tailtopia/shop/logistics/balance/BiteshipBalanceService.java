package com.tailtopia.shop.logistics.balance;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Biteship 预估余额（V1.4.2 shop-admin Story 4.1 · AD-13）：录入实际余额、预警阈值、低于阈值提醒运营。
 *
 * <p>检查点四处：运单创建、运单取消（{@code BiteshipShipmentService} 的 settle / cancelLocally 事务内调
 * {@link #checkQuietly()}，实际在提交后才查）、录入余额、改阈值。低于阈值发 OPS 群，去重键 {@code biteship-balance:{snapshotId}}
 * —— 同一次录入只提醒一次；{@link OpsAlertService} 在事务提交后才发，失败只记日志、不回滚业务。
 *
 * <p>🔴 只读本地表、不调 Biteship；🔒 提醒与日志不含 key、地址、手机号。
 */
@Service
public class BiteshipBalanceService {

    private static final Logger log = LoggerFactory.getLogger(BiteshipBalanceService.class);

    static final String DEDUPE_PREFIX = "biteship-balance:";

    private final BiteshipBalanceSnapshotRepository snapshots;
    private final ShippingSettingsRepository settings;
    private final JdbcTemplate jdbc;
    private final BiteshipProperties props;
    private final AdminAuditService audit;
    private final OpsAlertService ops;
    private final Clock clock;

    @Autowired
    public BiteshipBalanceService(BiteshipBalanceSnapshotRepository snapshots, ShippingSettingsRepository settings,
            JdbcTemplate jdbc, BiteshipProperties props, AdminAuditService audit, OpsAlertService ops) {
        this(snapshots, settings, jdbc, props, audit, ops, Clock.systemUTC());
    }

    BiteshipBalanceService(BiteshipBalanceSnapshotRepository snapshots, ShippingSettingsRepository settings,
            JdbcTemplate jdbc, BiteshipProperties props, AdminAuditService audit, OpsAlertService ops, Clock clock) {
        this.snapshots = snapshots;
        this.settings = settings;
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.ops = ops;
        this.clock = clock;
    }

    /**
     * 页面用：预估结果 + 上次录入时刻 / 录入人 + 阈值。
     *
     * @param recordedByName 录入人后台显示名（账号已删 → null）
     */
    public record Overview(BiteshipBalanceEstimate.Result result, Instant recordedAt, String recordedByName,
            long threshold) {

        public boolean below() {
            return result.below(threshold);
        }
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        BiteshipBalanceSnapshot snap = snapshots.findFirstByOrderByRecordedAtDescIdDesc().orElse(null);
        BiteshipBalanceEstimate.Result r = estimateFrom(snap);
        String by = null;
        if (snap != null && snap.getRecordedBy() != null) {
            by = jdbc.query("SELECT display_name FROM admin_accounts WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, snap.getRecordedBy());
        }
        return new Overview(r, snap == null ? null : snap.getRecordedAt(), by, threshold());
    }

    /** 录入实际余额：记入值、录入时预估值、录入人、时间；此后从新数重扣。写审计，随后检查一次。 */
    @Transactional
    public BiteshipBalanceSnapshot record(long amount, long actorAccountId) {
        if (amount < 0) {
            throw AppException.validation("余额不能为负").code("admin.err.v142.biteship.balanceNegative");
        }
        BiteshipBalanceSnapshot last = snapshots.findFirstByOrderByRecordedAtDescIdDesc().orElse(null);
        BiteshipBalanceEstimate.Result before = estimateFrom(last);
        Long estimateBefore = before.recorded() ? before.estimate() : null;
        BiteshipBalanceSnapshot saved = snapshots.save(
                BiteshipBalanceSnapshot.record(amount, estimateBefore, actorAccountId, clock.instant()));
        audit.record(actorAccountId, AuditActions.SHOP_BITESHIP_BALANCE_RECORDED, "BITESHIP_BALANCE",
                String.valueOf(saved.getId()), "录入 Biteship 实际余额：%d（录入时预估 %s）".formatted(amount,
                        estimateBefore == null ? "未录入" : String.valueOf(estimateBefore)));
        check(saved);
        return saved;
    }

    /** 设置余额预警阈值（0 = 不预警）。有变化才写审计；随后检查一次。 */
    @Transactional
    public ShippingSettings setThreshold(long value, long actorAccountId) {
        if (value < 0) {
            throw AppException.validation("预警阈值不能为负").code("admin.err.v142.biteship.thresholdNegative");
        }
        ShippingSettings s = settings.findById(ShippingSettings.SINGLETON_ID)
                .orElseThrow(() -> AppException.notFound("配送设置未初始化"));
        long old = s.getBalanceAlertThreshold();
        if (old != value) {
            s.applyBalanceAlertThreshold(value);
            settings.save(s);
            audit.record(actorAccountId, AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED, "SHIPPING_SETTINGS", "1",
                    "Biteship 余额预警阈值：%d → %d".formatted(old, value));
        }
        check(snapshots.findFirstByOrderByRecordedAtDescIdDesc().orElse(null));
        return s;
    }

    /**
     * 运单创建 / 取消后的检查点。在活动事务内调用时<b>挂到提交后</b>再查（复审修）：不在持有订单行锁的短事务里扫运单，
     * 查询失败也不会把调用方事务标成回滚 / aborted（否则会把已建成的 Biteship 运单当失败去取消）。
     * 🔴 任何异常只记日志，绝不影响下运单 / 取消运单本身。
     */
    public void checkQuietly() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runCheckQuietly();
                }
            });
            return;
        }
        runCheckQuietly();
    }

    private void runCheckQuietly() {
        try {
            check(snapshots.findFirstByOrderByRecordedAtDescIdDesc().orElse(null));
        } catch (RuntimeException e) {
            log.warn("Biteship 预估余额检查失败（不影响运单） cause={}", e.getClass().getSimpleName());
        }
    }

    private void check(BiteshipBalanceSnapshot snap) {
        if (snap == null) {
            return;   // 从未录入：不预警
        }
        BiteshipBalanceEstimate.Result r = estimateFrom(snap);
        long threshold = threshold();
        if (r.below(threshold)) {
            ops.send(OpsChannel.OPS, DEDUPE_PREFIX + snap.getId(),
                    "Biteship 预估余额 %s，低于预警阈值 %s，请尽快充值，充值后到后台「服务范围与运费」录入实际余额。余额不足会导致下运单失败。"
                            .formatted(rp(r.estimate()), rp(threshold)));
        }
    }

    private BiteshipBalanceEstimate.Result estimateFrom(BiteshipBalanceSnapshot snap) {
        if (snap == null) {
            return BiteshipBalanceEstimate.estimate(null, List.of(), props.isPriceIncludesInsurance());
        }
        List<BiteshipBalanceEstimate.Waybill> since = jdbc.query("""
                SELECT carrier_cost, insurance_cost, status = 'CANCELLED' AS cancelled
                  FROM shipments
                 WHERE provider = 'BITESHIP' AND created_at > ?""",
                (rs, i) -> new BiteshipBalanceEstimate.Waybill(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)),
                Timestamp.from(snap.getRecordedAt()));
        return BiteshipBalanceEstimate.estimate(snap.getAmount(), since, props.isPriceIncludesInsurance());
    }

    private long threshold() {
        return settings.findById(ShippingSettings.SINGLETON_ID).map(ShippingSettings::getBalanceAlertThreshold)
                .orElse(0L);
    }

    /** {@code Rp 1.284.000}（点分千位；负数带负号）。 */
    public static String rp(long v) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("id")));
        return "Rp " + f.format(v);
    }
}
