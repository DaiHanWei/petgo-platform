package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 1.1 · AC1 —— 27 值的大类 / 宫格项 / 中文名 / 解析 / 白名单。 */
class PetSpeciesTest {

    private static final List<String> ORDER = List.of(
            "CAT", "DOG", "RABBIT", "HAMSTER", "BIRD", "CHICKEN", "FISH", "LIZARD", "TURTLE", "SUGAR_GLIDER",
            "SNAKE", "DUCK", "FROG", "GUINEA_PIG", "CIVET", "HEDGEHOG", "HERMIT_CRAB", "OTTER", "SHEEP", "COW",
            "SPIDER", "SQUIRREL", "GOAT", "CRAB", "INSECT", "MOUSE", "OTHER");

    private static final List<String> ZH = List.of(
            "猫", "狗", "兔", "仓鼠", "鸟", "鸡", "鱼", "蜥蜴", "龟", "蜜袋鼯", "蛇", "鸭", "青蛙", "豚鼠",
            "麝香猫", "迷你刺猬", "寄居蟹", "水獭", "绵羊", "牛", "蜘蛛", "松鼠", "山羊", "螃蟹", "昆虫", "鼠类", "其他");

    @Test
    void orderIsFixed() {
        assertThat(Arrays.stream(PetSpecies.values()).map(Enum::name).toList()).containsExactlyElementsOf(ORDER);
    }

    @Test
    void groupPickerTypeAndZhNameForEveryValue() {
        for (PetSpecies s : PetSpecies.values()) {
            int i = s.ordinal();
            PetType expectedGroup = s == PetSpecies.CAT ? PetType.CAT : s == PetSpecies.DOG ? PetType.DOG : PetType.OTHER;
            assertThat(s.group()).as(s.name()).isEqualTo(expectedGroup);
            PetSpecies expectedPicker = i <= PetSpecies.GUINEA_PIG.ordinal() ? s : PetSpecies.OTHER;
            assertThat(s.pickerType()).as(s.name()).isEqualTo(expectedPicker);
            assertThat(s.zhName()).as(s.name()).isEqualTo(ZH.get(i));
            assertThat(s.isOtherGroupMember()).as(s.name())
                    .isEqualTo(i >= PetSpecies.CIVET.ordinal() && s != PetSpecies.OTHER);
        }
    }

    @Test
    void fromGroupRoundTrips() {
        for (PetType t : PetType.values()) {
            assertThat(PetSpecies.fromGroup(t).name()).isEqualTo(t.name());
            assertThat(PetSpecies.fromGroup(t).group()).isEqualTo(t);
        }
    }

    @Test
    void parse() {
        assertThat(PetSpecies.parse("rabbit")).contains(PetSpecies.RABBIT);
        assertThat(PetSpecies.parse("  Sugar_Glider ")).contains(PetSpecies.SUGAR_GLIDER);
        assertThat(PetSpecies.parse("CATS")).isEmpty();
        assertThat(PetSpecies.parse(null)).isEmpty();
        assertThat(PetSpecies.parse("  ")).isEmpty();
    }

    @Test
    void wirePatternMatchesValues() {
        assertThat(PetSpecies.WIRE_PATTERN).isEqualTo(PetSpecies.computedWirePattern());
        assertThat("SUGAR_GLIDER".matches(PetSpecies.WIRE_PATTERN)).isTrue();
        assertThat("DRAGON".matches(PetSpecies.WIRE_PATTERN)).isFalse();
        assertThat(Arrays.stream(PetSpecies.values()).mapToInt(s -> s.name().length()).max().orElseThrow())
                .isLessThanOrEqualTo(32);
    }
}
