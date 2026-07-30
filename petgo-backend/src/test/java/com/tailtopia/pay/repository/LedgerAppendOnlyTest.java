package com.tailtopia.pay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * L0：总账仓储 append-only 静态守卫（纯反射，无 Spring context）。{@link LedgerEntryRepository} 故意
 * {@code extends Repository}（非 {@code JpaRepository}）——不得暴露任何 {@code delete}/{@code remove} 方法，
 * 保证总账不可篡改，更正只能走反向补偿分录。
 */
class LedgerAppendOnlyTest {

    @Test
    void ledgerRepositoryExposesNoDeleteOrRemovePath() {
        for (Method m : LedgerEntryRepository.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertThat(n).as("总账仓储禁暴露删除方法: %s", m.getName()).doesNotContain("delete");
            assertThat(n).as("总账仓储禁暴露移除方法: %s", m.getName()).doesNotContain("remove");
        }
    }
}
