package com.tailtopia.shared.error;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 礼包下单冲突（V1.5.0 gift-pack Story 4-2 · D-22）：409 + RFC 9457 扩展成员
 * （{@code starter-pack-pending} → {@code purchaseToken}；{@code starter-pack-cooldown} → {@code until}）。
 * 扩展字段只放对用户安全的值（对外 token / 时间），不放内部 id。
 */
public class StarterPackConflictException extends AppException implements ProblemExtensions {

    private final Map<String, Object> extensions;

    StarterPackConflictException(URI type, String detail, Map<String, Object> extensions) {
        super(HttpStatus.CONFLICT, type, detail);
        this.extensions = Map.copyOf(extensions);
    }

    @Override
    public Map<String, Object> problemExtensions() {
        return extensions;
    }
}
