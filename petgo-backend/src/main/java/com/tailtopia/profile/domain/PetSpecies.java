package com.tailtopia.profile.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 宠物细分类型（V1.5.0 pet-species FR-205 · 架构 D-1）。落库 {@code pet_profiles.pet_species} varchar，枚举名即存储值 / API 契约值。
 *
 * <p>🔴 <b>顺序固定、只往后加</b>；加值须同步：① 新迁移 DROP + ADD {@code ck_pet_profiles_pet_species} 重列全集
 * （值取当前树最后一条重建它的迁移）；② App {@code lib/features/profile/domain/pet_species.dart}
 * （{@code pet_species_backend_sync_test.dart} 逐个比对常量名与顺序）。
 *
 * <p>前 14 个是 App 类型宫格上的独立类型；{@code CIVET … MOUSE} 是「其他」组下的 12 个物种；{@code OTHER} 为兜底。
 * 大类（{@link PetType}）只由 {@link #group()} 推出，全仓库不许另写推导（架构 G-2）。
 */
public enum PetSpecies {
    CAT("猫"),
    DOG("狗"),
    RABBIT("兔"),
    HAMSTER("仓鼠"),
    BIRD("鸟"),
    CHICKEN("鸡"),
    FISH("鱼"),
    LIZARD("蜥蜴"),
    TURTLE("龟"),
    SUGAR_GLIDER("蜜袋鼯"),
    SNAKE("蛇"),
    DUCK("鸭"),
    FROG("青蛙"),
    GUINEA_PIG("豚鼠"),
    CIVET("麝香猫"),
    HEDGEHOG("迷你刺猬"),
    HERMIT_CRAB("寄居蟹"),
    OTTER("水獭"),
    SHEEP("绵羊"),
    COW("牛"),
    SPIDER("蜘蛛"),
    SQUIRREL("松鼠"),
    GOAT("山羊"),
    CRAB("螃蟹"),
    INSECT("昆虫"),
    MOUSE("鼠类"),
    OTHER("其他");

    /** 27 值 {@code |} 拼接，供 DTO {@code @Pattern} 白名单用（{@code PetSpeciesTest} 断言与 {@link #values()} 一致）。 */
    public static final String WIRE_PATTERN = "CAT|DOG|RABBIT|HAMSTER|BIRD|CHICKEN|FISH|LIZARD|TURTLE|SUGAR_GLIDER"
            + "|SNAKE|DUCK|FROG|GUINEA_PIG|CIVET|HEDGEHOG|HERMIT_CRAB|OTTER|SHEEP|COW|SPIDER|SQUIRREL|GOAT|CRAB"
            + "|INSECT|MOUSE|OTHER";

    private final String zhName;

    PetSpecies(String zhName) {
        this.zhName = zhName;
    }

    /** 大类：CAT → CAT、DOG → DOG、其余 → OTHER。🔴 全仓库唯一的大类推导处。 */
    public PetType group() {
        return switch (this) {
            case CAT -> PetType.CAT;
            case DOG -> PetType.DOG;
            default -> PetType.OTHER;
        };
    }

    /**
     * 宫格上对应的那一项：独立类型返回自己；「其他」组 12 个物种与 {@code OTHER} 返回 {@code OTHER}。
     * 「是否改了类型」（占不占 90 天冷却）只比较它（架构 D-8）。
     */
    public PetSpecies pickerType() {
        return ordinal() >= CIVET.ordinal() ? OTHER : this;
    }

    /** 后台与 AI 用的中文名。 */
    public String zhName() {
        return zhName;
    }

    /** 是否「其他」组下的具体物种（不含 {@code OTHER} 本身）；后台显示「其他·麝香猫」用。 */
    public boolean isOtherGroupMember() {
        return pickerType() == OTHER && this != OTHER;
    }

    /** 旧版只带大类时的细分类型（= 同名值；三个大类在本枚举里都有同名常量）。 */
    public static PetSpecies fromGroup(PetType group) {
        return switch (group) {
            case CAT -> CAT;
            case DOG -> DOG;
            case OTHER -> OTHER;
        };
    }

    /** null / 空 → empty；trim + 大写后按名解析，不认识 → empty（调用方 422）。 */
    public static Optional<PetSpecies> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 测试用：由 {@link #values()} 拼出的白名单，与 {@link #WIRE_PATTERN} 比对。 */
    static String computedWirePattern() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining("|"));
    }
}
