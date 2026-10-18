package com.tailtopia.growth.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 每日额度原子领额（V1.4.0 ① 架构 AD-3，{@code growth_daily_counters}）。照 {@code ShareRewardQuotaRepository} 先例：
 * {@link #insertIfAbsent} 幂等建行 + {@link #tryGrant} 单行条件 UPDATE（自带行锁，{@code used + grant <= cap} 保证不超）。
 *
 * <p>🔴 禁应用层读改写。额度只增不减（扣回不返还，PRD）。
 */
@Repository
public class GrowthDailyCounterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GrowthDailyCounterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等建当天行。1 = 新建 / 0 = 已存在。 */
    public int insertIfAbsent(long userId, LocalDate wibDate, String counterKey) {
        return jdbc.update("INSERT INTO growth_daily_counters (user_id, wib_date, counter_key, used) "
                + "VALUES (:u, :d, :k, 0) ON CONFLICT (user_id, wib_date, counter_key) DO NOTHING",
                Map.of("u", userId, "d", wibDate, "k", counterKey));
    }

    /** 当天已用额度（行不存在 = 0）。 */
    public int used(long userId, LocalDate wibDate, String counterKey) {
        List<Integer> rows = jdbc.queryForList("SELECT used FROM growth_daily_counters "
                + "WHERE user_id = :u AND wib_date = :d AND counter_key = :k",
                Map.of("u", userId, "d", wibDate, "k", counterKey), Integer.class);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 原子占额：{@code used += grant WHERE used + grant <= cap}。1 = 占到 / 0 = 会超上限。 */
    public int tryGrant(long userId, LocalDate wibDate, String counterKey, int grant, int cap) {
        return jdbc.update("UPDATE growth_daily_counters SET used = used + :g "
                + "WHERE user_id = :u AND wib_date = :d AND counter_key = :k AND used + :g <= :cap",
                Map.of("u", userId, "d", wibDate, "k", counterKey, "g", grant, "cap", cap));
    }
}
