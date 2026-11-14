package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 独立建卡器入参（Story 6-7，决策④）：卡信息与档案解耦，前端默认预填档案值但可改。
 * name 必填；birthday 必填且不可为未来（spec-ktp-pet-idcode-numbering：身份码日期段依赖生日，
 * 快照冻结不可改，未来日期会永久固化荒谬编号）；其余选填。
 * petType 传枚举名（DOG/CAT/...）字符串。birthday `yyyy-MM-dd`。
 * gender 传 MALE/FEMALE/UNKNOWN，可空（null 视同 UNKNOWN），非法值与其余字段校验统一 422。
 * birthCity/address/occupation/maritalStatus（bug 20260729-409：Edit Info 与卡面字段对齐）
 * 均选填；null → 前端渲染趣味默认。
 * cardType（bug 20260730-430：一卡一面）：KTP/PASSPORT/STUDENT；null 容错默认 KTP（兼容旧客户端）。
 * school/faculty（bug 20260730-429：学生卡专属）选填；null → 前端趣味默认。
 *
 * <p>V1.3.4 ktp（AD-4）：cardType 白名单扩为 {@link com.tailtopia.profile.domain.IdCardType} 5 值；新增
 * cardVariant（学生卡配色 {@link com.tailtopia.profile.domain.StudentVariant}，跨字段规则在
 * {@code IdCardService}：学生卡缺省 KLASIK（D-12），非学生卡带值 422）。intro 字段保留以兼容旧版 App，
 * 但一律不写入。
 *
 * <p>V1.3.4 ktp D-20：<b>必填口径按接口区分，不放在 Bean 校验里</b>——旧接口 {@code POST /me/id-cards}
 * （1.3.3 及更早 App）保持宽松：petType / gender / breed 可空，gender 空视同 UNKNOWN；新接口
 * {@code POST /me/id-cards/v2}（1.3.4 起 App）在服务层强制 petType / gender 必填、KTP / KITAS 的 breed 必填（AD-4）。
 * 这里只校验取值白名单（非空时）。两条白名单正则与枚举同源（{@code IdCardTypeWhitelistTest}）。
 */
public record CreateIdCardRequest(
        @NotBlank @Size(max = 60) String name,
        @Pattern(regexp = PET_TYPES) String petType,
        @Size(max = 80) String breed,
        @NotNull @PastOrPresent LocalDate birthday,
        @Pattern(regexp = "MALE|FEMALE|UNKNOWN") String gender,
        @Size(max = 1024) String avatarUrl,
        @Size(max = 30) String intro,
        @Size(max = 40) String birthCity,
        @Size(max = 80) String address,
        @Size(max = 40) String occupation,
        @Size(max = 24) String maritalStatus,
        @Pattern(regexp = CARD_TYPES) String cardType,
        @Size(max = 40) String school,
        @Size(max = 40) String faculty,
        @Pattern(regexp = CARD_VARIANTS) String cardVariant,
        /**
         * 细分类型（V1.5.0 pet-species · D-13），可空：旧版 App 不传 → 快照 = {@code petType}。
         * 传了则以它的大类作卡快照 {@code pet_type}（{@code petType} 若也传了须一致，否则 422）。
         */
        @Pattern(regexp = com.tailtopia.profile.domain.PetSpecies.WIRE_PATTERN) String species) {

    /** 便捷构造：V1.5.0 前的形态（不带细分类型）。 */
    public CreateIdCardRequest(String name, String petType, String breed, LocalDate birthday, String gender,
            String avatarUrl, String intro, String birthCity, String address, String occupation,
            String maritalStatus, String cardType, String school, String faculty, String cardVariant) {
        this(name, petType, breed, birthday, gender, avatarUrl, intro, birthCity, address, occupation,
                maritalStatus, cardType, school, faculty, cardVariant, null);
    }

    /** 卡种白名单（= {@code IdCardType} 枚举名，库内 {@code ck_id_cards_card_type} 同源）。 */
    public static final String CARD_TYPES = "KTP|PASSPORT|KITAS|SIM|STUDENT";

    /** 学生卡配色白名单（= {@code StudentVariant} 枚举名，库内 {@code ck_id_cards_card_variant} 同源）。 */
    public static final String CARD_VARIANTS = "BINTANG|MALAM|CERIA|KLASIK";

    /** 物种白名单（= {@code PetType} 枚举名）。 */
    public static final String PET_TYPES = "CAT|DOG|OTHER";
}
