package com.tailtopia.tailsonality.dto;

import com.tailtopia.tailsonality.domain.TailsonalityResult;
import java.time.Instant;

/**
 * 结果 DTO（V1.3.2 Story 2.1 · AC6）。
 *
 * <p>🔴 不含答案、权重、各轴原始分（计分细节不外露）；{@code TailsonalityResultResponseContractTest} 钉字段全集。
 *
 * @param typeCode    完整代号 {@code ENTJ-H}
 * @param letters     四字母 {@code ENTJ}
 * @param resultIndex 同宠物按 {@code created_at} 升序的 1 起序号，现算不落库
 * @param unlockedAt  null 时按全局 {@code non_null} 省略
 * @param equipped    当前宠物佩戴的就是本结果（Story 3.3 · AC2.5；佩戴行 {@code result_id} = 本结果 id）
 */
public record TailsonalityResultResponse(
        String token,
        String typeCode,
        String letters,
        String energy,
        String questionSet,
        int resultIndex,
        boolean unlocked,
        Instant unlockedAt,
        int contentVersion,
        Instant createdAt,
        boolean equipped) {

    public static TailsonalityResultResponse of(TailsonalityResult r, int resultIndex, boolean equipped) {
        return new TailsonalityResultResponse(
                r.getPublicToken(),
                r.code().full(),
                r.getTypeCode(),
                r.getEnergy(),
                r.getQuestionSet().name(),
                resultIndex,
                r.getUnlockedAt() != null,
                r.getUnlockedAt(),
                r.getContentVersion(),
                r.getCreatedAt(),
                equipped);
    }
}
