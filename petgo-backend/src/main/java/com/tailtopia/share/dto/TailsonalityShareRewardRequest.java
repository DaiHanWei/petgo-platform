package com.tailtopia.share.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Tailsonality 卡分享上报（V1.3.2 Story 4.5 · AC3.1）。
 *
 * <p>🔴 <b>只有卡类型，一个字段都不多</b>：不收结果 token（去重键是宠物不是结果，收了就会有人按结果计）、
 * 不收水印态（带水印卡同样计）、不收卡面内容。非法值 → 422。
 */
public record TailsonalityShareRewardRequest(
        @NotBlank @Pattern(regexp = "RESULT|MATCH") String cardType) {
}
