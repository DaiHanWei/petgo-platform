package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * L0：身份码/护照号编码拼装纯逻辑（spec-ktp-pet-idcode-numbering，《宠物身份码护照编码规则》）——
 * 性别加码 +50/+10/+0、SP 物种映射、补零、位数。计数器原子取号 / 撞号循环等 DB 行为在集成测试（L1）验。
 */
class CardNumberServiceTest {

    // ---- 身份码 TT+DDMMYY+SP+XXXX ----

    @Test
    void femaleCatSpecExample() {
        // spec I/O 矩阵示例：2024-03-10 母猫、当日该物种第 2 张 → 日 10+50=60。
        String no = CardNumberService.composeCardNo(
                LocalDate.of(2024, 3, 10), "FEMALE", "02", 2);
        assertThat(no).isEqualTo("TT600324020002").hasSize(14);
    }

    @Test
    void maleDogDayPlus10() {
        // 公 +10：生日 03 日 → 日期段日=13；SP 狗=01。
        String no = CardNumberService.composeCardNo(
                LocalDate.of(2023, 7, 3), "MALE", "01", 1);
        assertThat(no).isEqualTo("TT130723010001");
    }

    @Test
    void unknownGenderPlus0AndZeroPadding() {
        // 未知 +0；日/月/序号补零。
        String no = CardNumberService.composeCardNo(
                LocalDate.of(2025, 1, 5), "UNKNOWN", "00", 7);
        assertThat(no).isEqualTo("TT050125000007");
    }

    @Test
    void genderOffsets() {
        assertThat(CardNumberService.genderOffset("FEMALE")).isEqualTo(50);
        assertThat(CardNumberService.genderOffset("MALE")).isEqualTo(10);
        assertThat(CardNumberService.genderOffset("UNKNOWN")).isZero();
        assertThat(CardNumberService.genderOffset(null)).isZero();
    }

    @Test
    void speciesCodeMapping() {
        assertThat(CardNumberService.speciesCode("DOG")).isEqualTo("01");
        assertThat(CardNumberService.speciesCode("CAT")).isEqualTo("02");
        assertThat(CardNumberService.speciesCode("OTHER")).isEqualTo("00");
        assertThat(CardNumberService.speciesCode(null)).isEqualTo("00");
    }

    // ---- 护照号 TT+SP+P+YY+XXXXX ----

    @Test
    void passportNoComposition() {
        assertThat(CardNumberService.composePassportNo("CAT", 2026, 3))
                .isEqualTo("TT02P2600003").hasSize(12);
        assertThat(CardNumberService.composePassportNo(null, 2026, 12345))
                .isEqualTo("TT00P2612345");
    }
}
