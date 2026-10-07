package com.tailtopia.purchase.service;

import java.sql.Savepoint;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 发放口的 JDBC 保存点包裹（V1.3.2 Story 3.2 复审定下的范式，3.4 / 3.5 复用）。
 *
 * <p>PostgreSQL 上事务内任一语句失败会中止整个事务；发放口在调用方事务（到账 = {@code applyCallback}）里跑，
 * 失败时回滚到保存点，外层才能继续把购买行记成 ORPHAN_PAID。{@code PROPAGATION_NESTED} 在 JPA 事务管理器下不可用，故手动。
 * 连接为 autocommit（无外层事务）时不建保存点。异常原样抛给调用方（发放口自己 catch 成 REF_MISSING）。
 */
public final class GrantSavepoint {

    private GrantSavepoint() {
    }

    public static <T> T run(JdbcTemplate jdbc, Supplier<T> work) {
        return jdbc.execute((ConnectionCallback<T>) con -> {
            Savepoint sp = con.getAutoCommit() ? null : con.setSavepoint();
            try {
                T out = work.get();
                if (sp != null) {
                    con.releaseSavepoint(sp);
                }
                return out;
            } catch (RuntimeException e) {
                if (sp != null) {
                    con.rollback(sp);
                }
                throw e;
            }
        });
    }
}
