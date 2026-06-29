package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建里程碑分享请求（P-35 分享链接）。沿用本模块「显示文案归客户端按 locale 出」约定：
 * 客户端只传**已本地化渲染好**的庆祝文案（{@code title}/{@code body}）与其 {@code locale}；
 * code / petName / level / completedAt 一律后端按 JWT 从自有数据补，不信任客户端。
 *
 * @param title            庆祝标题（已含 emoji、已替换宠物名）
 * @param body             庆祝正文（可空串）
 * @param locale           文案语言，仅 {@code id} / {@code en}
 * @param collectionLevels 「已解锁合集」快照：已完成里程碑级别串（按合集顺序，每字符 S/M/L），可空串
 */
public record MilestoneShareRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 500) String body,
        @NotBlank @Pattern(regexp = "id|en") String locale,
        @Pattern(regexp = "[SML]*") @Size(max = 64) String collectionLevels) {
}
