package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 独立建卡器入参（Story 6-7，决策④）：卡信息与档案解耦，前端默认预填档案值但可改。
 * name 必填；其余选填。petType 传枚举名（DOG/CAT/...）字符串。birthday `yyyy-MM-dd`。
 * cardType（Story 6-8）传 KTP/PASSPORT/STUDENT；null/非法 → 服务端回落 KTP。
 */
public record CreateIdCardRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 16) String petType,
        @Size(max = 80) String breed,
        LocalDate birthday,
        @Size(max = 1024) String avatarUrl,
        @Size(max = 30) String intro,
        @Size(max = 16) String cardType) {
}
