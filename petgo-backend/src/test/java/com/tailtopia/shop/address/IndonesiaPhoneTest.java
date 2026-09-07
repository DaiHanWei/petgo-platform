package com.tailtopia.shop.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.IndonesiaPhone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：印尼手机号归一化（Story 2.1 / 决策 C-15）。 */
class IndonesiaPhoneTest {

    @Test
    @DisplayName("三种输入形式归一到同一存储值（C-15 原文点名的三种）")
    void threeInputFormsNormalizeToSameValue() {
        assertThat(IndonesiaPhone.normalize("08123456789")).isEqualTo("+628123456789");
        assertThat(IndonesiaPhone.normalize("8123456789")).isEqualTo("+628123456789");
        assertThat(IndonesiaPhone.normalize("+62 812-3456-789")).isEqualTo("+628123456789");
    }

    @Test
    @DisplayName("🔴 前导 0 必须被剥掉——不剥会存成 +6208...，快递系统拨不通")
    void stripsLeadingZero() {
        assertThat(IndonesiaPhone.normalize("08123456789")).doesNotContain("+620");
        assertThat(IndonesiaPhone.normalize("008123456789")).isEqualTo("+628123456789");
    }

    @Test
    @DisplayName("🔴 下限是 9 位不是 8 位——8 位放进的是无效号，无效号 = 快递员联系不上 = 履约失败")
    void lowerBoundIsNineNotEight() {
        // 8 位有效位（8 + 7 位）→ 拒
        assertThatThrownBy(() -> IndonesiaPhone.normalize("81234567"))
                .isInstanceOf(AppException.class);
        // 9 位有效位 → 通过
        assertThat(IndonesiaPhone.normalize("812345678")).isEqualTo("+62812345678");
    }

    @Test
    @DisplayName("上限 12 位：12 通过、13 拒")
    void upperBoundIsTwelve() {
        assertThat(IndonesiaPhone.normalize("812345678901")).isEqualTo("+62812345678901");
        assertThatThrownBy(() -> IndonesiaPhone.normalize("8123456789012"))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("首位必须是 8（印尼手机号段）")
    void firstDigitMustBeEight() {
        assertThatThrownBy(() -> IndonesiaPhone.normalize("7123456789"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> IndonesiaPhone.normalize("+62 21 1234567"))  // 固话
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("🔒 报错 detail 不回显用户输入的号码（NFR-5）")
    void errorDetailNeverEchoesInput() {
        assertThatThrownBy(() -> IndonesiaPhone.normalize("79999999999"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("79999999999"));
    }

    @Test
    @DisplayName("空/空白 → 明确提示")
    void blankRejected() {
        assertThatThrownBy(() -> IndonesiaPhone.normalize(null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> IndonesiaPhone.normalize("  ")).isInstanceOf(AppException.class);
        assertThat(IndonesiaPhone.isValid("abc")).isFalse();
    }
}
