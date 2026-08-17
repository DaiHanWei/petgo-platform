package com.tailtopia.shop.order.dto;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.ProblemExtensions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 下单被不可用行阻断（Story 3.4；Story 3.7 接上 HTTP 映射）。
 *
 * <p>🔴 携带<b>逐行明细</b>而不是一句笼统的错误 —— 见 {@link UnavailableLine} 的说明。
 * 明细经 {@link ProblemExtensions} 进入 RFC 9457 的 {@code unavailableLines} 扩展成员，
 * 信封（type/title/status/detail/instance/traceId）仍由 {@code GlobalExceptionHandler} 统一产出。
 *
 * <p>⚠️ 本类<b>继承 {@code AppException}</b>：不继承的话它会落进 handler 的 catch-all 变成 500，
 * 而这是一个完全预期内的业务结果（409）。
 */
public class CheckoutUnavailableException extends AppException implements ProblemExtensions {

    private final transient List<UnavailableLine> lines;

    public CheckoutUnavailableException(List<UnavailableLine> lines) {
        super(HttpStatus.CONFLICT, ErrorTypes.CONFLICT, "部分商品已不可购买，请移除后重试");
        this.lines = List.copyOf(lines);
    }

    public List<UnavailableLine> getLines() {
        return lines;
    }

    @Override
    public Map<String, Object> problemExtensions() {
        return Map.of("unavailableLines", lines);
    }
}
