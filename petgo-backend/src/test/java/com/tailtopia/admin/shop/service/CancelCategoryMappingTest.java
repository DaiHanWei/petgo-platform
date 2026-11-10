package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.shop.service.CancelCategoryMapping.Action;
import com.tailtopia.admin.shop.service.CancelCategoryMapping.Category;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.logistics.ReturnCause;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

/** L0（V1.4.2 shop-admin Story 5.1 · AC4）：类别映射与绑定由服务端校验，不符 409；退回待判定不能取消。 */
class CancelCategoryMappingTest {

    @ParameterizedTest(name = "{0} / {1} / {2} / {3} / inbound={4} → {5} {6}")
    @CsvSource(nullValues = "-", value = {
            "OUT_OF_STOCK,   PLATFORM,     PENDING_SHIPMENT, -,             false, OUT_OF_STOCK,        CANCEL_WHOLE",
            "WAYBILL_FAILED, PLATFORM,     PENDING_SHIPMENT, -,             false, WAYBILL_FAILED,      CANCEL_WHOLE",
            "PICKUP_FAILED,  PLATFORM,     SHIPPED,          -,             false, PICKUP_FAILED,       PICKUP_CANCEL",
            "RETURNED,       PLATFORM,     SHIPPED,          COURIER_FAULT, false, COURIER_FAULT,       CANCEL_WHOLE",
            "RETURNED,       USER,         SHIPPED,          USER_FAULT,    true,  USER_FAULT_RETURNED, CANCEL_WHOLE",
            "LOST_DAMAGED,   LOST_DAMAGED, SHIPPED,          -,             false, LOST_DAMAGED,        CANCEL_WHOLE",
            "LOST_DAMAGED,   LOST_DAMAGED, DELIVERED,        -,             false, LOST_DAMAGED,        COMPENSATE",
            "LOST_DAMAGED,   LOST_DAMAGED, COMPLETED,        -,             false, LOST_DAMAGED,        COMPENSATE",
    })
    void allowed(ExceptionSource source, Category category, ShopOrderStatus status, ReturnCause cause, boolean inbound,
            CancelReason reason, Action action) {
        var plan = CancelCategoryMapping.resolve(source, category, status, cause, inbound);
        assertThat(plan.reason()).isEqualTo(reason);
        assertThat(plan.action()).isEqualTo(action);
    }

    @ParameterizedTest(name = "{0} / {1} / {2} / {3} / inbound={4} → 409 {5}")
    @CsvSource(nullValues = "-", value = {
            // 用户原因只接受 return_cause = USER_FAULT 的退回
            "OUT_OF_STOCK,   USER,         PENDING_SHIPMENT, -,             true,  admin.err.v142.exception.categoryMismatch",
            "RETURNED,       USER,         SHIPPED,          COURIER_FAULT, true,  admin.err.v142.exception.categoryMismatch",
            // 用户原因须已入库（D4）
            "RETURNED,       USER,         SHIPPED,          USER_FAULT,    false, admin.err.v142.exception.inboundRequired",
            // 平台原因不接受用户原因退回 / 丢件破损
            "RETURNED,       PLATFORM,     SHIPPED,          USER_FAULT,    true,  admin.err.v142.exception.categoryMismatch",
            "LOST_DAMAGED,   PLATFORM,     SHIPPED,          -,             false, admin.err.v142.exception.categoryMismatch",
            // 丢件破损只接受丢件破损来源
            "OUT_OF_STOCK,   LOST_DAMAGED, PENDING_SHIPMENT, -,             false, admin.err.v142.exception.categoryMismatch",
            "RETURNED,       LOST_DAMAGED, SHIPPED,          COURIER_FAULT, false, admin.err.v142.exception.categoryMismatch",
            // 退回待判定不能取消；退回只在已发货时可取消（O-29）
            "RETURNED,       PLATFORM,     SHIPPED,          -,             false, admin.err.v142.exception.returnUndecided",
            "RETURNED,       USER,         SHIPPED,          -,             true,  admin.err.v142.exception.returnUndecided",
            "RETURNED,       PLATFORM,     DELIVERED,        COURIER_FAULT, false, admin.err.v142.exception.returnNotCancellable",
            "RETURNED,       PLATFORM,     PENDING_SHIPMENT, COURIER_FAULT, false, admin.err.v142.exception.returnNotCancellable",
    })
    void rejected(ExceptionSource source, Category category, ShopOrderStatus status, ReturnCause cause, boolean inbound,
            String code) {
        assertThatThrownBy(() -> CancelCategoryMapping.resolve(source, category, status, cause, inbound))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(code);
                });
    }

    @Test
    void notInQueueIs409() {
        assertThatThrownBy(() -> CancelCategoryMapping.resolve(null, Category.PLATFORM, ShopOrderStatus.SHIPPED, null,
                false)).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.exception.notInQueue"));
    }

    @Test
    void lineReasonOnlyPlatformSources() {
        assertThat(CancelCategoryMapping.lineReason(ExceptionSource.OUT_OF_STOCK)).isEqualTo(CancelReason.OUT_OF_STOCK);
        assertThat(CancelCategoryMapping.lineReason(ExceptionSource.WAYBILL_FAILED)).isEqualTo(CancelReason.WAYBILL_FAILED);
        assertThat(CancelCategoryMapping.lineReason(ExceptionSource.PICKUP_FAILED)).isEqualTo(CancelReason.PICKUP_FAILED);
        assertThatThrownBy(() -> CancelCategoryMapping.lineReason(ExceptionSource.RETURNED)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> CancelCategoryMapping.lineReason(ExceptionSource.LOST_DAMAGED)).isInstanceOf(AppException.class);
    }

    @Test
    void categoryParse() {
        assertThat(Category.parse(" user ")).isEqualTo(Category.USER);
        assertThatThrownBy(() -> Category.parse("FOO")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> Category.parse(null)).isInstanceOf(AppException.class);
    }

    @Test
    void allowedHelperMatchesResolve() {
        assertThat(CancelCategoryMapping.allowed(ExceptionSource.OUT_OF_STOCK, Category.PLATFORM,
                ShopOrderStatus.PENDING_SHIPMENT, null)).isTrue();
        assertThat(CancelCategoryMapping.allowed(ExceptionSource.OUT_OF_STOCK, Category.USER,
                ShopOrderStatus.PENDING_SHIPMENT, null)).isFalse();
        // 用户原因：可选态不看入库（D4 照显明细，按钮置灰）
        assertThat(CancelCategoryMapping.allowed(ExceptionSource.RETURNED, Category.USER, ShopOrderStatus.SHIPPED,
                ReturnCause.USER_FAULT)).isTrue();
    }
}
