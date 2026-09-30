package com.tailtopia.passport.dto;

import java.util.List;

/**
 * 护照页响应（V1.3.2 Story 1.2 · AC3，{@code GET /api/v1/pet-profiles/me/passport}）。
 *
 * <p>🔴 <b>不下发任何总数分母</b>（AC3.3）：没有 total / max / capacity / remaining ——
 * 场所由运营持续增加，写死分母 = 一个永远填不满的假进度。{@code PetPassportResponseContractTest} 钉住。
 *
 * @param petName    宠物名（页眉）
 * @param passportNo 护照号 12 位
 * @param stampCount 已集章数（= stamps 长度）
 * @param stamps     章列表，按首次到访升序（新章恒在最后）
 * @param currentVersionUnlocked 当前章集合的版本已买（V1.3.2 Story 3.4 · AC5）；无章恒 false。App 据此决定内页叠不叠水印
 * @param purchasedVersionCount  已买版本数（已付快照数；AppBar「已购版本」入口 &gt; 0 才出）
 */
public record PetPassportResponse(String petName, String passportNo, int stampCount,
        List<PassportStampView> stamps, boolean currentVersionUnlocked, int purchasedVersionCount) {
}
