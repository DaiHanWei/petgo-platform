package com.tailtopia.growth.service;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销级联：成长值数据物理删除（V1.4.0 ① Story 1.2 · 架构 AD-18、PRD AC10）。幂等可重跑。
 *
 * <p>删 {@code growth_ledger}、{@code growth_daily_counters}、{@code growth_accounts} 中该用户的行。
 * <b>先删账户行</b>：它会等进行中的入账（持有该行 {@code FOR UPDATE}）提交后才执行，之后再删流水 / 额度就能看到那笔
 * 入账写下的行，不留孤儿。注销开始后不再有新入账（{@link GrowthParticipation} 排除注销作业已开始的用户）。
 * {@code growth_ledger.reverses_id} 自引用 FK：保险起见先删反向流水、再删其余（同一用户的原流水与反向流水同属一人）。
 * 停用账号（{@code DEACTIVATED}）<b>不走</b>这里，数据保留。
 */
@Service
public class GrowthAccountDeletionService {

    private final NamedParameterJdbcTemplate jdbc;

    public GrowthAccountDeletionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        Map<String, Long> p = Map.of("u", userId);
        jdbc.update("DELETE FROM growth_accounts WHERE user_id = :u", p);
        jdbc.update("DELETE FROM growth_ledger WHERE user_id = :u AND reverses_id IS NOT NULL", p);
        jdbc.update("DELETE FROM growth_ledger WHERE user_id = :u", p);
        jdbc.update("DELETE FROM growth_daily_counters WHERE user_id = :u", p);
    }
}
