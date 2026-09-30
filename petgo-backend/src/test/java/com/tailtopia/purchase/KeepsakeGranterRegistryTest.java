package com.tailtopia.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.1 · AC4.2（L0）：发放口注册表启动校验 + 发放收口吞异常。 */
class KeepsakeGranterRegistryTest {

    static KeepsakeGranter granter(KeepsakeSku sku, GrantOutcome outcome) {
        return new KeepsakeGranter() {
            @Override
            public KeepsakeSku sku() {
                return sku;
            }

            @Override
            public GrantOutcome grant(long refId, long purchaseId) {
                return outcome;
            }
        };
    }

    private static List<KeepsakeGranter> all() {
        return List.of(granter(KeepsakeSku.TAILSONALITY, GrantOutcome.GRANTED),
                granter(KeepsakeSku.PASSPORT_SNAP, GrantOutcome.GRANTED),
                granter(KeepsakeSku.BOARDING_PASS, GrantOutcome.GRANTED));
    }

    @Test
    void exactlyOnePerSkuStartsFine() {
        KeepsakeGranterRegistry r = new KeepsakeGranterRegistry(all());
        for (KeepsakeSku sku : KeepsakeSku.values()) {
            assertThat(r.forSku(sku).sku()).isEqualTo(sku);
        }
    }

    @Test
    void missingSkuFailsStartup() {
        assertThatThrownBy(() -> new KeepsakeGranterRegistry(all().subList(0, 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOARDING_PASS");
    }

    @Test
    void duplicateSkuFailsStartup() {
        List<KeepsakeGranter> dup = new java.util.ArrayList<>(all());
        dup.add(granter(KeepsakeSku.TAILSONALITY, GrantOutcome.GRANTED));
        assertThatThrownBy(() -> new KeepsakeGranterRegistry(dup))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
    }

    @Test
    void grantRunnerSwallowsGranterExceptions() {
        KeepsakeGranter boom = new KeepsakeGranter() {
            @Override
            public KeepsakeSku sku() {
                return KeepsakeSku.TAILSONALITY;
            }

            @Override
            public GrantOutcome grant(long refId, long purchaseId) {
                throw new IllegalStateException("contract violated");
            }
        };
        KeepsakeGranterRegistry r = new KeepsakeGranterRegistry(List.of(boom,
                granter(KeepsakeSku.PASSPORT_SNAP, GrantOutcome.GRANTED),
                granter(KeepsakeSku.BOARDING_PASS, GrantOutcome.ALREADY_UNLOCKED)));
        KeepsakeGrantRunner runner = new KeepsakeGrantRunner(r);

        KeepsakePurchase ts = KeepsakePurchase.paidPawcoin("a", new KeepsakeRef(KeepsakeSku.TAILSONALITY, 1, "r", null, false),
                7L, 5000, Instant.EPOCH);
        ReflectionTestUtils.setField(ts, "id", 1L);
        assertThat(runner.grantOrNull(ts)).isNull();

        KeepsakePurchase bp = KeepsakePurchase.paidPawcoin("b", new KeepsakeRef(KeepsakeSku.BOARDING_PASS, 2, "r", null, false),
                7L, 1000, Instant.EPOCH);
        ReflectionTestUtils.setField(bp, "id", 2L);
        assertThat(runner.grantOrNull(bp)).isEqualTo(GrantOutcome.ALREADY_UNLOCKED);
    }
}
