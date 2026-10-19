package com.tailtopia.admin.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 4.1 · T7.2（L0）：新增的 {@code facts} 重载不影响两个既有构造器。 */
class AdminConfirmModalFactsTest {

    @Test
    void legacyConstructorsHaveNoFacts() {
        AdminConfirmModal eleven = new AdminConfirmModal("t", null, List.of(), null, null, null, null, "/x", Map.of(),
                "ok", false);
        AdminConfirmModal twelve = new AdminConfirmModal("t", null, List.of(), null, null, null, null, "/x", Map.of(),
                "ok", false, true);
        assertThat(eleven.facts()).isEmpty();
        assertThat(eleven.warnBanner()).isFalse();
        assertThat(twelve.facts()).isEmpty();
        assertThat(twelve.warnBanner()).isTrue();
    }

    @Test
    void factsAreCopiedAndNullBecomesEmpty() {
        List<AdminConfirmModal.Fact> facts = new java.util.ArrayList<>(List.of(new AdminConfirmModal.Fact("升级", "3 人")));
        AdminConfirmModal m = new AdminConfirmModal("t", null, List.of(), null, null, null, null, "/x", Map.of(), "ok",
                false, false, facts);
        facts.clear();
        assertThat(m.facts()).containsExactly(new AdminConfirmModal.Fact("升级", "3 人"));
        assertThat(new AdminConfirmModal("t", null, null, null, null, null, null, "/x", null, "ok", false, false, null)
                .facts()).isEmpty();
    }
}
