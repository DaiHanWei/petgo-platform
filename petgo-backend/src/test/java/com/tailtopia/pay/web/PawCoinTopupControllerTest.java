package com.tailtopia.pay.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.dto.CreateTopupRequest;
import com.tailtopia.pay.service.PawCoinTopupService;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.Test;

/**
 * L0：资金下单端点强制 Idempotency-Key（Review P3）。缺失/空即拒，且不触达 service（不建单、不发 charge）。
 */
class PawCoinTopupControllerTest {

    private final PawCoinTopupService service = mock(PawCoinTopupService.class);
    private final PawCoinTopupController controller = new PawCoinTopupController(service);
    private final CreateTopupRequest req = new CreateTopupRequest("T25K", PayChannel.QRIS.name());

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> controller.topup(null, null, req))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> controller.topup(null, "   ", req))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(service);
    }
}
