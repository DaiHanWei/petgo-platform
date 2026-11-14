package com.tailtopia.entitlement.domain;

import com.tailtopia.profile.domain.IdCardType;
import com.tailtopia.profile.domain.StudentVariant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 权益类型。wire 值即 {@code entitlement_credits.credit_type}（V1.5.0 gift-pack 架构 §5，<b>签名冻结</b>，⑥ 依赖）。
 *
 * <p>KTP 8 款 = KTP / PASSPORT / KITAS / SIM / 学生卡四配色（架构 §1 #10；PASSPORT 是 KTP 卡种「Paspor」，
 * 不是护照快照）；{@link #KTP_HD_ANY}「任意款」只由运营活动发放（D-31）。
 */
public enum CreditType {
    KTP_HD_KTP("KTP_HD:KTP"), KTP_HD_PASSPORT("KTP_HD:PASSPORT"), KTP_HD_KITAS("KTP_HD:KITAS"), KTP_HD_SIM("KTP_HD:SIM"),
    KTP_HD_STUDENT_BINTANG("KTP_HD:STUDENT_BINTANG"), KTP_HD_STUDENT_MALAM("KTP_HD:STUDENT_MALAM"),
    KTP_HD_STUDENT_CERIA("KTP_HD:STUDENT_CERIA"), KTP_HD_STUDENT_KLASIK("KTP_HD:STUDENT_KLASIK"),
    KTP_HD_ANY("KTP_HD:ANY"), TAILSONALITY("TAILSONALITY"), GIFT_1K("GIFT_1K");

    private static final String KTP_PREFIX = "KTP_HD:";

    private final String wire;

    CreditType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 前 9 个（含 ANY）→ {@link CreditFamily#KTP_HD}。 */
    public CreditFamily family() {
        return switch (this) {
            case TAILSONALITY -> CreditFamily.TAILSONALITY;
            case GIFT_1K -> CreditFamily.GIFT_1K;
            default -> CreditFamily.KTP_HD;
        };
    }

    public static Optional<CreditType> fromWire(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.wire.equals(wire)).findFirst();
    }

    /**
     * {@code id_cards.card_type / card_variant} → 8 款之一。非学生卡忽略 variant；学生卡 variant 为空按 KLASIK
     * （存量学生卡回填 KLASIK）。非法组合 → {@link IllegalArgumentException}。
     */
    public static CreditType ktpHdOf(String cardType, String cardVariant) {
        IdCardType type;
        try {
            type = IdCardType.valueOf(cardType == null ? "" : cardType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知卡种: " + cardType, e);
        }
        String suffix;
        if (type == IdCardType.STUDENT) {
            StudentVariant variant;
            if (cardVariant == null || cardVariant.isBlank()) {
                variant = StudentVariant.KLASIK;
            } else {
                try {
                    variant = StudentVariant.valueOf(cardVariant);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("未知学生卡配色: " + cardVariant, e);
                }
            }
            suffix = type.name() + "_" + variant.name();
        } else {
            suffix = type.name();
        }
        return fromWire(KTP_PREFIX + suffix)
                .orElseThrow(() -> new IllegalArgumentException("无对应 KTP 款: " + cardType + "/" + cardVariant));
    }

    /** 8 款（不含 ANY），顺序 = 枚举声明序；礼包发放用。 */
    public static List<CreditType> ktpTemplates() {
        return Arrays.stream(values()).filter(t -> t.family() == CreditFamily.KTP_HD && t != KTP_HD_ANY).toList();
    }
}
