package com.tailtopia.merchant.binding.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：填码比较前去掉空格（契约第 1 节 TailTopia 侧验收）。 */
class MerchantBindingNormalizeTest {

    @Test
    @DisplayName("首尾与中间的空白全部去掉；null 视为空串")
    void stripsAllWhitespace() {
        assertThat(MerchantBindingService.normalize(" 000 035\t")).isEqualTo("000035");
        assertThat(MerchantBindingService.normalize("000035")).isEqualTo("000035");
        assertThat(MerchantBindingService.normalize(null)).isEmpty();
    }
}
