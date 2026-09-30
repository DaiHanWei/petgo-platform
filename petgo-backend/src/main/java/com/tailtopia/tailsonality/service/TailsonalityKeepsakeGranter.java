package com.tailtopia.tailsonality.service;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.sql.Savepoint;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tailsonality 结果的发放口（V1.3.2 Story 3.2 · AC2）：置 {@code tailsonality_results.unlocked_at}。
 *
 * <p>走 JDBC 而不是 JPA：{@link KeepsakeGranter} 契约要求不抛、且不得把调用方事务标成 rollback-only；
 * JPA / Hibernate 的持久化异常会在抛出点就标 rollback-only，catch 也救不回。
 *
 * <p>🔴 再包一层 <b>JDBC 保存点</b>：PostgreSQL 上事务内任一语句失败会中止整个事务（之后所有 SQL 都报
 * {@code current transaction is aborted}），光 catch 不够 —— 调用方随后把购买行记 ORPHAN_PAID 也会失败。
 * 失败时回滚到保存点，外层事务可继续。（Spring 的 {@code PROPAGATION_NESTED} 在 JPA 事务管理器下不支持，故手动。）
 *
 * <p>🔴 扩展点：Story 3.3 的「解锁即自动佩戴」在 {@link #grant} 的 GRANTED 分支内追加，<b>不要</b>另起监听器。
 */
@Component
public class TailsonalityKeepsakeGranter implements KeepsakeGranter {

    private static final Logger log = LoggerFactory.getLogger(TailsonalityKeepsakeGranter.class);

    private final NamedParameterJdbcTemplate jdbc;

    public TailsonalityKeepsakeGranter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public KeepsakeSku sku() {
        return KeepsakeSku.TAILSONALITY;
    }

    @Override
    public GrantOutcome grant(long refId, long purchaseId) {
        try {
            return jdbc.getJdbcTemplate().execute((ConnectionCallback<GrantOutcome>) con -> {
                Savepoint sp = con.getAutoCommit() ? null : con.setSavepoint();
                try {
                    GrantOutcome outcome = unlock(refId);
                    if (sp != null) {
                        con.releaseSavepoint(sp);
                    }
                    return outcome;
                } catch (RuntimeException e) {
                    if (sp != null) {
                        con.rollback(sp);
                    }
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            // 只记 refId（AD-19：不记用户 / 宠物信息）。
            log.error("tailsonality grant failed refId={}", refId, e);
            return GrantOutcome.REF_MISSING;
        }
    }

    /** 同一连接上执行（JdbcTemplate 取事务绑定的连接）。GRANTED 分支是 Story 3.3 自动佩戴的追加点。 */
    private GrantOutcome unlock(long refId) {
        Map<String, Long> p = Map.of("id", refId);
        int updated = jdbc.update(
                "UPDATE tailsonality_results SET unlocked_at = now() WHERE id = :id AND unlocked_at IS NULL", p);
        if (updated == 1) {
            return GrantOutcome.GRANTED;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM tailsonality_results WHERE id = :id", p, Integer.class);
        return exists != null && exists > 0 ? GrantOutcome.ALREADY_UNLOCKED : GrantOutcome.REF_MISSING;
    }
}
