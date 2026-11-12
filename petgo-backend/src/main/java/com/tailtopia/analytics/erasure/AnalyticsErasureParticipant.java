package com.tailtopia.analytics.erasure;

import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销状态机的埋点擦除参与者（analytics-pipeline Story 1.8，架构 A10，FR-15）。
 *
 * <p>由 {@code AccountDeletionService#execute} 调用：往<b>业务库</b> {@code analytics_erasure_outbox} 登记一行
 * （{@code ON CONFLICT (user_id) DO NOTHING}，状态机重跑幂等）。<b>不访问埋点库</b>（Dai 2026-11-12 拍板）：埋点库故障或
 * 管道停用都不会让注销失败；真正的擦除由 {@link ErasureJob} 每晚在埋点库执行。
 *
 * <p>写失败（业务库连不上、超时、SQL 异常）<b>直接抛</b>，由注销状态机置 {@code FAILED}、启动重扫续跑。
 * 本类不打日志（日志里绝不出现 {@code user_id} / {@code user_key}）。
 */
@Component
public class AnalyticsErasureParticipant {

    static final String INSERT_SQL = "INSERT INTO analytics_erasure_outbox (user_id, user_key) VALUES (?, ?) "
            + "ON CONFLICT (user_id) DO NOTHING";

    private final JdbcTemplate businessJdbc;

    /** 业务库 {@code @Primary} {@link JdbcTemplate}（不是埋点库那个）。 */
    public AnalyticsErasureParticipant(JdbcTemplate businessJdbcTemplate) {
        this.businessJdbc = businessJdbcTemplate;
    }

    /**
     * 登记该用户的埋点擦除请求（业务库事务；调用方已有业务事务时加入同一事务）。
     *
     * @throws RuntimeException 写失败，不吞
     */
    @Transactional
    public void register(long userId) {
        businessJdbc.update(INSERT_SQL, userId, AnalyticsDistinctId.of(userId));
    }
}
