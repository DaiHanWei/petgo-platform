package com.tailtopia.admin.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 看板一次取数的结果（V1.3.0 Story 3.4 AC2）：范围内<b>每一天</b>的 label（WIB，含无数据日）+ 五张卡。
 *
 * @param rangeDays 7 / 30
 * @param start     范围起点（WIB 自然日）
 * @param end       范围终点 = 昨天（WIB）
 * @param labels    {@code yyyy-MM-dd}，与每条序列的 values 逐日对齐
 */
public record ChartData(int rangeDays, LocalDate start, LocalDate end, List<String> labels, List<ChartCard> cards) {

    public ChartCard card(String id) {
        return cards.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }
}
