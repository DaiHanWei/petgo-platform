package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.dto.MilestoneGroupResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：1.4.2 上线前旧版兼容——旧包隐藏账号级新里程碑。 */
class LegacyMilestoneCompatTest {

    @Test
    @DisplayName("隐藏名单正好是 1.4.2 新增的「第一次关注 / 第一次收藏」两条（猫 / 狗 / 通用）")
    void codesAreTheTwoNewAccountMilestones() {
        for (String code : LegacyMilestoneCompat.V142_CODES) {
            var def = MilestoneCatalog.byCode(code);
            assertThat(def).as(code).isNotNull();
            assertThat(def.titleZh()).as(code).isIn("第一次关注一个人", "第一次收藏内容");
        }
        assertThat(LegacyMilestoneCompat.V142_CODES).hasSize(6);
    }

    private static MilestoneItemResponse item(String code, boolean done) {
        return new MilestoneItemResponse(code, null, "S", "SYSTEM_AUTO", done, done ? Instant.EPOCH : null, null);
    }

    @Test
    @DisplayName("旧版：去掉新里程碑并扣减计数；新版原样")
    void filtersAndRecounts() {
        var s = new MilestoneGroupResponse("S", 2, 3,
                List.of(item("C-S1", true), item("C-S19", true), item("C-S20", false)));
        var r = new MilestoneListResponse("Mochi", null, 2, 3, List.of(s));

        var legacy = LegacyMilestoneCompat.forClient(r, "1.4.1");
        assertThat(legacy.totalCount()).isEqualTo(1);
        assertThat(legacy.completedCount()).isEqualTo(1);
        assertThat(legacy.groups().get(0).items()).extracting(MilestoneItemResponse::code).containsExactly("C-S1");
        assertThat(legacy.groups().get(0).totalCount()).isEqualTo(1);
        assertThat(legacy.groups().get(0).completedCount()).isEqualTo(1);

        assertThat(LegacyMilestoneCompat.forClient(r, "1.4.2")).isSameAs(r);
    }

    /**
     * V1.5.0 onboarding-recall Story 2.3 · AC4：1.4.2 ≤ v &lt; 1.5.0 原样（账号级 3 项仍在 S 组、计入计数、无 accountGroup）；
     * 序列化形状不带 accountGroup 字段（NON_NULL）。
     */
    @Test
    @DisplayName("1.4.2 ≤ v < 1.5.0 原样；旧形状不序列化 accountGroup")
    void v142To149KeepsOldShape() throws Exception {
        var s = new MilestoneGroupResponse("S", 2, 4,
                List.of(item("C-S1", true), item("C-S16", false), item("C-S19", true), item("C-S20", false)));
        var r = new MilestoneListResponse("Mochi", null, 2, 4, List.of(s));
        assertThat(LegacyMilestoneCompat.forClient(r, "1.4.9")).isSameAs(r);
        assertThat(r.accountGroup()).isNull();
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(r);
        assertThat(json).doesNotContain("accountGroup");
    }
}
