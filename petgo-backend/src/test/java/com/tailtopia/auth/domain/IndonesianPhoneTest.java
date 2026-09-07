package com.tailtopia.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * L0：印尼手机号的基础格式校验与归一（V1.1.6 Story 7.1 · FR-70）。
 *
 * <p>🔴 这组测试的重点**不是"挡住多少非法输入"，而是"别挡住真实用户"**。
 * AC 原话：「常见合法写法均应通过 —— 校验过严会挡住真实用户」，
 * 并专门配了保存失败率埋点来发现"是不是我们卡太严"。
 */
class IndonesianPhoneTest {

    /** 印尼人实际会怎么写自己的号码 —— 这些**必须全过**。 */
    @ParameterizedTest
    @ValueSource(strings = {
        "081234567890",          // 最常见：0 开头
        "0812-3456-7890",        // 带连字符
        "0812 3456 7890",        // 带空格
        "+6281234567890",        // 国际写法
        "+62 812 3456 7890",     // 国际写法带空格
        "62812345678900",        // 无 + 号
        "(0812) 3456-7890",      // 带括号
        "0812.3456.7890",        // 带点
        "08123456789",           // 短一些（11 位）
        "08123456789012",        // 最长的合法形态（国内部分 13 位，E.164 上限）
    })
    void commonIndonesianWritingsAllPass(String input) {
        assertThat(IndonesianPhone.normalizeOrNull(input))
                .as("这是印尼人真实会用的写法，挡掉它就是在挡真实用户：%s", input)
                .isNotNull();
    }

    /** 🛡 归一：不同写法进去，出来是**同一个**形态 —— 否则同一个人会以三种样子出现在导出表里。 */
    @Test
    void differentWritingsOfTheSameNumberNormalizeToOne() {
        String canonical = "+6281234567890";
        for (String input : new String[] {
            "081234567890", "0812-3456-7890", "+62 812 3456 7890", "6281234567890",
        }) {
            assertThat(IndonesianPhone.normalizeOrNull(input))
                    .as("写法 %s 应归一到同一形态", input)
                    .isEqualTo(canonical);
        }
    }

    /** 明显不是手机号的才该被挡。 */
    @ParameterizedTest
    @ValueSource(strings = {
        "081234567890123", // 超出 E.164 上限（62 + 14 位 = 16 位）
        "021 5555 1234",   // 座机（雅加达区号，不以 8 开头）
        "12345",           // 太短
        "abcdefghij",      // 非数字
        "08",              // 只有前缀
        "+1 415 555 0100", // 别国号码
    })
    void obviouslyWrongInputsAreRejected(String input) {
        assertThat(IndonesianPhone.normalizeOrNull(input)).isNull();
    }

    /**
     * ⚠️ 空输入返回 null，但那是**清空**语义 ——
     * 调用方必须在进来之前就分流「清空」与「格式错」，不能靠这里的返回值区分。
     * 这条把该约定写下来。
     */
    @Test
    void blankInputReturnsNullAndMustBeRoutedAsClearByCaller() {
        assertThat(IndonesianPhone.normalizeOrNull("")).isNull();
        assertThat(IndonesianPhone.normalizeOrNull("   ")).isNull();
        assertThat(IndonesianPhone.normalizeOrNull(null)).isNull();
    }
}
