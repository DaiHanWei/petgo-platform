package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.UserMilestoneFlag;
import com.tailtopia.profile.dto.MilestoneGroupResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 onboarding-recall Story 2.3 · AC3 / AC5 —— 新口径（账号级 3 项单独成组、不计入宠物进度）。 */
class MilestoneCountingTest {

    private static final Instant T = Instant.parse("2026-11-15T03:00:00Z");

    private static MilestoneItemResponse item(String code, boolean done, Instant celebrated) {
        return new MilestoneItemResponse(code, "t", "S", "SYSTEM_AUTO", done, done ? T : null, celebrated);
    }

    private static UserMilestoneFlag flag(String key, Instant celebratedAt) {
        UserMilestoneFlag f = org.springframework.beans.BeanUtils.instantiateClass(UserMilestoneFlag.class);
        ReflectionTestUtils.setField(f, "userId", 7L);
        ReflectionTestUtils.setField(f, "flagKey", key);
        ReflectionTestUtils.setField(f, "occurredAt", T.minusSeconds(60));
        ReflectionTestUtils.setField(f, "celebratedAt", celebratedAt);
        return f;
    }

    @Test
    void 宠物总数猫狗32其他17() {
        assertThat(MilestoneCatalog.forType(PetType.CAT)).hasSize(35);
        assertThat(MilestoneCounting.petTotal(PetType.CAT)).isEqualTo(32);
        assertThat(MilestoneCounting.petTotal(PetType.DOG)).isEqualTo(32);
        assertThat(MilestoneCatalog.forType(PetType.OTHER)).hasSize(20);
        assertThat(MilestoneCounting.petTotal(PetType.OTHER)).isEqualTo(17);
        assertThat(MilestoneCounting.accountLevelTotal(PetType.OTHER)).isEqualTo(3);
    }

    @Test
    void 账号级判定只认九个完整编码() {
        for (String c : List.of("C-S16", "D-S16", "G-S9", "C-S19", "D-S19", "G-S12", "C-S20", "D-S20", "G-S13")) {
            assertThat(MilestoneCounting.isAccountLevel(c)).as(c).isTrue();
        }
        assertThat(MilestoneCounting.isAccountLevel("C-S1")).isFalse();
        assertThat(MilestoneCounting.isAccountLevel("G-S16")).isFalse();
    }

    @Test
    void 列表新口径_S组与计数去掉账号级_另出账号组() {
        var l = new MilestoneGroupResponse("L", 1, 1, List.of(item("C-L1", true, T)));
        var s = new MilestoneGroupResponse("S", 3, 5, List.of(item("C-S1", true, T), item("C-S2", false, null),
                item("C-S16", true, null), item("C-S19", true, T), item("C-S20", false, null)));
        var r = new MilestoneListResponse("Mochi", null, 4, 6, List.of(l, s));

        var out = MilestoneCounting.withAccountGroup(r, PetType.CAT,
                List.of(flag("FIRST_FOLLOW_USER", T), flag("LULUS_PEMULA", null)));

        assertThat(out.totalCount()).isEqualTo(3);
        assertThat(out.completedCount()).isEqualTo(2);
        MilestoneGroupResponse sOut = out.groups().get(1);
        assertThat(sOut.items()).extracting(MilestoneItemResponse::code).containsExactly("C-S1", "C-S2");
        assertThat(sOut.totalCount()).isEqualTo(2);
        assertThat(sOut.completedCount()).isEqualTo(1);
        assertThat(out.groups().get(0)).isEqualTo(l);

        MilestoneGroupResponse acc = out.accountGroup();
        assertThat(acc.level()).isEqualTo("ACCOUNT");
        assertThat(acc.items()).extracting(MilestoneItemResponse::code).containsExactly("C-S19", "C-S20", "C-S16");
        assertThat(acc.items()).extracting(MilestoneItemResponse::completed).containsExactly(true, false, true);
        assertThat(acc.completedCount()).isEqualTo(2);
        assertThat(acc.totalCount()).isEqualTo(3);
        // Lulus 未庆祝（完成行 celebratedAt null）→ App 据此补弹。
        assertThat(acc.items().get(2).celebratedAt()).isNull();
    }

    @Test
    void 账号组完成态以flag为准_宠物缺行也一致() {
        // 这只宠物 roster 缺账号级行（极老宠物）——flag 存在照样显示完成。
        var s = new MilestoneGroupResponse("S", 1, 1, List.of(item("D-S1", true, T)));
        var r = new MilestoneListResponse("Kopi", null, 1, 1, new ArrayList<>(List.of(s)));

        var out = MilestoneCounting.withAccountGroup(r, PetType.DOG,
                List.of(flag("FIRST_COLLECT_CONTENT", T), flag("LULUS_PEMULA", null)));

        assertThat(out.totalCount()).isEqualTo(1);
        var acc = out.accountGroup().items();
        assertThat(acc).extracting(MilestoneItemResponse::code).containsExactly("D-S19", "D-S20", "D-S16");
        assertThat(acc.get(1).completed()).isTrue();
        assertThat(acc.get(1).celebratedAt()).as("另两个 flag 视为已庆祝").isNotNull();
        assertThat(acc.get(2).completed()).isTrue();
        assertThat(acc.get(2).celebratedAt()).as("Lulus 按 flag 的 celebrated_at").isNull();
        assertThat(acc.get(0).completed()).isFalse();
    }
}
