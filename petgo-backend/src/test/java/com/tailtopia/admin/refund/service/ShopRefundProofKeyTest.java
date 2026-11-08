package com.tailtopia.admin.refund.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 5.2 · O-9）：转账截图 key 只认本退款单、本方法生成的形状。 */
class ShopRefundProofKeyTest {

    @Test
    void onlyOwnGeneratedKeys() {
        String ok = "shop-refund-proof/rf-1/123e4567-e89b-12d3-a456-426614174000.png";
        assertThat(AdminRefundProofService.belongsToShopRefund(ok, "rf-1")).isTrue();
        assertThat(AdminRefundProofService.belongsToShopRefund(ok, "rf-2")).isFalse();
        assertThat(AdminRefundProofService.belongsToShopRefund(
                "shop-refund-proof/rf-1/../rf-2/123e4567-e89b-12d3-a456-426614174000.png", "rf-1")).isFalse();
        assertThat(AdminRefundProofService.belongsToShopRefund(
                "shop-refund-proof/rf-1/123e4567-e89b-12d3-a456-426614174000.pdf", "rf-1")).isFalse();
        assertThat(AdminRefundProofService.belongsToShopRefund(null, "rf-1")).isFalse();
    }
}
