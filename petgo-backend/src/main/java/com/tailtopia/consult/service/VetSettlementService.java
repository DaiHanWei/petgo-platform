package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.dto.VetIncomeResponse;
import com.tailtopia.consult.dto.VetIncomeResponse.VetIncomePeriodItem;
import com.tailtopia.consult.dto.VetPayoutAggregate;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.VetSettlementRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医月度结算 + 收入（Story 3.7）。
 *
 * <ul>
 *   <li>{@link #generateSettlements(YearMonth)}：聚合某 WIB 月 COMPLETED 订单的 {@code vet_payout} 快照 →
 *       逐兽医生成 {@code vet_settlements}(PENDING)。幂等（唯一 {@code (vet_id, period)}，存在跳过）。
 *       每月 1 日 WIB 由 {@link VetSettlementScanner} 调（生成上月）。</li>
 *   <li>{@link #income(long)}：收入页数据 = 当月待结算实时聚合 + 历史月结倒序。</li>
 * </ul>
 *
 * <p><b>period 用 WIB（Asia/Jakarta）</b>——刻意偏离全局 UTC（印尼「每月 1 号」本地语义，架构 §调度④，照 2-1
 * {@code FreeQuotaService}）。月边界转 UTC {@code Instant} 做区间查询。到手恒读快照，勿实时按 config 重算（D-4）。
 */
@Service
public class VetSettlementService {

    /** WIB（印尼西部时间）。月结 period 按此算，非全局 UTC。刻意偏离，勿订正。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final ConsultOrderRepository orders;
    private final VetSettlementRepository settlements;

    public VetSettlementService(ConsultOrderRepository orders, VetSettlementRepository settlements) {
        this.orders = orders;
        this.settlements = settlements;
    }

    /**
     * 生成某 WIB 月月结（可直测；scanner 只调它）。聚合该月 COMPLETED 订单 by vet_id → 逐兽医 upsert-if-absent
     * {@code vet_settlements}(PENDING)。返回新生成行数（已存在的跳过、不重复）。
     */
    @Transactional
    public int generateSettlements(YearMonth period) {
        Instant start = period.atDay(1).atStartOfDay(WIB).toInstant();
        Instant end = period.plusMonths(1).atDay(1).atStartOfDay(WIB).toInstant();
        String periodKey = period.toString(); // YYYY-MM
        Instant now = Instant.now();
        int generated = 0;
        for (VetPayoutAggregate agg : orders.aggregateCompletedByVet(start, end)) {
            if (settlements.existsByVetIdAndPeriod(agg.vetId(), periodKey)) {
                continue; // 幂等：已生成不重复
            }
            settlements.save(VetSettlement.of(agg.vetId(), periodKey, (int) agg.orderCount(),
                    agg.grossAmount(), agg.payoutAmount(), now));
            generated++;
        }
        return generated;
    }

    /** 收入页数据（只读）：当月待结算实时聚合 + 历史月结倒序。 */
    @Transactional(readOnly = true)
    public VetIncomeResponse income(long vetId) {
        YearMonth thisMonth = YearMonth.now(WIB);
        Instant start = thisMonth.atDay(1).atStartOfDay(WIB).toInstant();
        Instant end = thisMonth.plusMonths(1).atDay(1).atStartOfDay(WIB).toInstant();
        VetPayoutAggregate agg = orders.aggregateCompletedForVet(vetId, start, end).orElse(null);
        VetIncomePeriodItem current = VetIncomePeriodItem.currentMonth(thisMonth.toString(), agg);
        List<VetIncomePeriodItem> history = settlements.findByVetIdOrderByPeriodDesc(vetId).stream()
                .map(VetIncomePeriodItem::ofSettlement)
                .toList();
        return new VetIncomeResponse(current, history);
    }
}
