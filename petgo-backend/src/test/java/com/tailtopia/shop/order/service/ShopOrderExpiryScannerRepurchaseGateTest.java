package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.shop.repurchase.service.RepurchaseScanService;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shop.review.service.ShopReviewService;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.2 AC5.1，D-20 / FR-136）：复购日扫开关。 */
class ShopOrderExpiryScannerRepurchaseGateTest {

    private final RepurchaseScanService repurchase = mock(RepurchaseScanService.class);

    private ShopOrderExpiryScanner scanner() {
        return new ShopOrderExpiryScanner(mock(ShopOrderPaymentService.class), mock(ShopOrderFulfillmentService.class),
                mock(ReturnRequestService.class), repurchase, mock(ShopReviewService.class));
    }

    @Test
    @DisplayName("开关缺省（false）→ 不调 RepurchaseScanService.scan")
    void disabledByDefault() {
        scanner().scanRepurchase();
        verify(repurchase, never()).scan(any());
    }

    @Test
    @DisplayName("开关 true → 照常调 scan")
    void enabledRunsScan() {
        ShopOrderExpiryScanner s = scanner();
        ReflectionTestUtils.setField(s, "repurchaseScanEnabled", true);
        s.scanRepurchase();
        verify(repurchase, times(1)).scan(any());
    }

    @Test
    @DisplayName("@Value 键与默认值：petgo.shop.repurchase.scan-enabled:false；application.yml 默认 false")
    void propertyKeyAndDefault() throws Exception {
        Field f = ShopOrderExpiryScanner.class.getDeclaredField("repurchaseScanEnabled");
        assertThat(f.getAnnotation(Value.class).value()).isEqualTo("${petgo.shop.repurchase.scan-enabled:false}");
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("scan-enabled: ${SHOP_REPURCHASE_SCAN_ENABLED:false}");
    }
}
