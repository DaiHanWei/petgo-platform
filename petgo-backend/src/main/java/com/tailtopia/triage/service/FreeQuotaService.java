package com.tailtopia.triage.service;

import com.tailtopia.shared.triage.TriageProperties;
import com.tailtopia.triage.domain.UserMonthlyFreeQuota;
import com.tailtopia.triage.dto.FreeQuotaView;
import com.tailtopia.triage.repository.UserMonthlyFreeQuotaRepository;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每月免费额度判定/扣减（Story 2.1，FR-43B）。提供「有没有免费额度 / 原子消耗一次 / 本月状态」三种能力。
 *
 * <p><b>period 用 WIB（{@code Asia/Jakarta}），刻意偏离项目全局 UTC 惯例</b>：免费额度「每月 1 号刷新」是面向
 * 印尼用户的产品语义，UTC 与 WIB 月初差 7 小时会让月末用户体验错乱（架构 §调度④ 指定 WIB）。<b>这是有意为之</b>，
 * 勿按 UTC 惯例「订正」。换月自然产生新 {@code period} 行 = 惰性重置，不需 {@code @Scheduled}。
 *
 * <p><b>并发不超发</b>：{@link #tryConsume} 走 {@code insertIfAbsent}（幂等建当月行）+ 原子条件 UPDATE
 * （{@code used_count < limit}），照 {@code PawCoinWalletService} 范式。{@code @Transactional} 默认 REQUIRED：
 * 独立调用自开事务；<b>2-3 解锁流</b>「生成结果 + 额度扣减 + 解锁来源写入」同一事务内调用时合并进外层事务。
 *
 * <p><b>本 service 不感知评级颜色</b>（AC6）：红色永不锁 / 红色计入消耗属 2-2/2-3 的<b>调用侧策略</b>——
 * 本 service 只提供无颜色语义的「有没有额度 / 扣一次 / 查状态」原子能力，<b>此处不埋红色特判</b>（安全规则层
 * 单点、只升不降）。本 story triage 生成/解锁链路不改，{@code tryConsume} 只被测试与（未来）2-3 调用。
 */
@Service
public class FreeQuotaService {

    /** WIB（印尼西部时间）。免费额度 period 按此算，非全局 UTC。刻意偏离，勿订正。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /** 后台可调上界（架构 §9「0-35」）。9-2 换 DB 后由 pricing_config 约束，本 story clamp 兜底。 */
    private static final int MAX_QUOTA = 35;

    private final UserMonthlyFreeQuotaRepository quotas;
    private final TriageProperties props;

    public FreeQuotaService(UserMonthlyFreeQuotaRepository quotas, TriageProperties props) {
        this.quotas = quotas;
        this.props = props;
    }

    /** 当前月度 period = {@code YYYY-MM}（WIB）。 */
    String currentPeriod() {
        return YearMonth.now(WIB).toString();
    }

    /** 本月免费额度上限：配置值 clamp 到 {@code [0,35]}（越界夹取，不抛异常）。 */
    int limit() {
        return Math.max(0, Math.min(props.getDefaultFreeQuota(), MAX_QUOTA));
    }

    /** 本月是否还有免费额度（只读，不建行）。 */
    @Transactional(readOnly = true)
    public boolean hasFreeQuota(long userId) {
        int used = usedCount(userId, currentPeriod());
        return used < limit();
    }

    /**
     * 原子消耗一次免费额度。成功=true（{@code used_count} 已 +1）；已达上限/无额度=false。
     * 并发不超发（行锁 + 条件 UPDATE）。供 2-3 在其事务内调用。
     */
    @Transactional
    public boolean tryConsume(long userId) {
        int limit = limit();
        if (limit <= 0) {
            return false; // 0=全付费，无免费额度
        }
        String period = currentPeriod();
        quotas.insertIfAbsent(userId, period);           // 幂等建当月行（ON CONFLICT DO NOTHING）
        return quotas.tryConsume(userId, period, limit) == 1; // 原子条件递增，0=已满
    }

    /** 本月额度状态（只读，不建行）。供 {@code GET /me/free-quota} 与 2-4 paywall。 */
    @Transactional(readOnly = true)
    public FreeQuotaView status(long userId) {
        String period = currentPeriod();
        int limit = limit();
        int used = usedCount(userId, period);
        return new FreeQuotaView(period, limit, used, Math.max(0, limit - used));
    }

    private int usedCount(long userId, String period) {
        return quotas.findByUserIdAndPeriod(userId, period)
                .map(UserMonthlyFreeQuota::getUsedCount)
                .orElse(0);
    }
}
