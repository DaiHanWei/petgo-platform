package com.tailtopia.profile.domain;

/**
 * 疫苗预设（V1.5.0 健康记录提醒 Story 1.1，架构增量 §3.1，PRD FR-159）。落库 {@code health_records.vaccine_preset}
 * varchar(24) + CHECK，UPPER_SNAKE。
 *
 * <p>{@link #label()} = 1.5.0 客户端选预设时同时写进 {@code vaccine_name} 的标签（逐字照架构 §3.1）；
 * {@link #OTHER} 的名字由用户自由填写（≤30），没有固定标签。系列长度、到期规则归 {@code profile.health} 规则类（Story 1.2）。
 *
 * <p>物种只认 CAT / DOG（{@link #forCat()} / {@link #forDog()}），其他物种一律不存预设（只有自由文本）。
 */
public enum VaccinePreset {
    F3("F3 (Tricat)", true, false, false),
    F4("F4 (Tetracat)", true, false, false),
    RABIES("Rabies", true, true, true),
    FELV("FeLV", true, false, false),
    PUPPY_DP("Puppy DP", false, true, false),
    COMBO_DHPPI_L("Combo DHPPi+L", false, true, false),
    LEPTO("Leptospirosis", false, true, false),
    KENNEL_COUGH("Kennel Cough", false, true, true),
    CANINE_INFLUENZA("Canine influenza", false, true, true),
    OTHER(null, true, true, false);

    private final String label;
    private final boolean cat;
    private final boolean dog;
    private final boolean singleDose;

    VaccinePreset(String label, boolean cat, boolean dog, boolean singleDose) {
        this.label = label;
        this.cat = cat;
        this.dog = dog;
        this.singleDose = singleDose;
    }

    /** 写进 {@code vaccine_name} 的标签；{@link #OTHER} 为 null（用户自由文本）。 */
    public String label() {
        return label;
    }

    public boolean forCat() {
        return cat;
    }

    public boolean forDog() {
        return dog;
    }

    /** 单剂疫苗（Rabies / Kennel Cough / Canine influenza）：没有剂次。 */
    public boolean singleDose() {
        return singleDose;
    }

    /** 系列疫苗（F3 / F4 / FeLV / Puppy DP / Combo / Lepto）：只有这些预设的 {@code dose} 落库。 */
    public boolean series() {
        return this != OTHER && !singleDose;
    }

    /** 该预设是否适用于此物种；非 CAT / DOG 物种一律 false（OTHER 物种不存预设）。 */
    public boolean appliesTo(PetType petType) {
        if (petType == PetType.CAT) {
            return cat;
        }
        if (petType == PetType.DOG) {
            return dog;
        }
        return false;
    }
}
