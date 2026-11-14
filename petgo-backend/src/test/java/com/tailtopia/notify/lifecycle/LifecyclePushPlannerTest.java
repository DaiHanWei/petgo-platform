package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.port.LifecycleContextPort.DefaultPet;
import com.tailtopia.notify.port.LifecycleContextPort.LifecycleContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * L0 金标：生命周期推送计划器（V1.5.0 onboarding-recall Story 6.1 · OR-22 / OR-23 重写）—— 5 层 × 3 节点表格驱动、
 * 当天已来过作废（D1 / D3）、D3 E 不推、毕业两种判据、N 计算、窗口与排序、WIB 边界、召回开关。
 */
class LifecyclePushPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final LifecyclePlanOptions NO_WINBACK = new LifecyclePlanOptions(false, 7);

    private final LifecyclePushPlanner planner = new LifecyclePushPlanner();

    /** 一个用户在某层的构造：快照 + 宠物 + 标记。 */
    private record Fixture(UserLifecycleSnapshot user, DefaultPet pet, Set<String> marks) {
    }

    private static final DefaultPet MOCHI = new DefaultPet("Mochi", "Persia", "CAT");

    private static Fixture layer(LifecycleLayer l, long id, int age) {
        LocalDate reg = TODAY.minusDays(age);
        return switch (l) {
            case A -> new Fixture(new UserLifecycleSnapshot(id, reg, null, 0, "PLANNING"), null, Set.of());
            case B -> new Fixture(new UserLifecycleSnapshot(id, reg, null, 0, "HAS_PET"), null, Set.of());
            case C -> new Fixture(new UserLifecycleSnapshot(id, reg, null, 0, "HAS_PET"), MOCHI, Set.of());
            case D -> new Fixture(new UserLifecycleSnapshot(id, reg, null, 1, "HAS_PET"), MOCHI, Set.of("FIRST_DIARY"));
            case E -> new Fixture(new UserLifecycleSnapshot(id, reg, null, 1, "HAS_PET"), MOCHI,
                    Set.of("FIRST_DIARY", "FIRST_FOLLOW"));
        };
    }

    private List<LifecyclePlannedPush> run(List<Fixture> fs, Set<Long> activeToday, Set<Long> graduated,
            LifecyclePlanOptions o) {
        Map<Long, DefaultPet> pets = new HashMap<>();
        Map<Long, Set<String>> marks = new HashMap<>();
        for (Fixture f : fs) {
            if (f.pet() != null) {
                pets.put(f.user().userId(), f.pet());
            }
            marks.put(f.user().userId(), f.marks());
        }
        return planner.plan(TODAY, fs.stream().map(Fixture::user).toList(),
                new LifecycleContext(pets, marks, activeToday, graduated), o);
    }

    private List<LifecyclePlannedPush> run(Fixture f) {
        return run(List.of(f), Set.of(), Set.of(), NO_WINBACK);
    }

    // ===== 表格（OR-23）=====

    static Stream<Arguments> table() {
        return Stream.of(
                // 节点天数, 层, 期望变体, 期望文案键（null = 不产出）
                Arguments.of(1, LifecycleLayer.A, LifecycleVariant.DISCOVER, "LIFECYCLE_D1.NO_PET"),
                Arguments.of(1, LifecycleLayer.B, LifecycleVariant.CREATE_PROFILE, "LIFECYCLE_D1.CREATE_PROFILE"),
                Arguments.of(1, LifecycleLayer.C, LifecycleVariant.RECORD, "LIFECYCLE_D1.RECORD"),
                Arguments.of(1, LifecycleLayer.D, LifecycleVariant.DISCOVER, "LIFECYCLE_D1.SQUAD"),
                Arguments.of(1, LifecycleLayer.E, LifecycleVariant.RECORD, "LIFECYCLE_D1.ACTIVE"),
                Arguments.of(3, LifecycleLayer.A, LifecycleVariant.DISCOVER, "LIFECYCLE_D3.NO_PET"),
                Arguments.of(3, LifecycleLayer.B, LifecycleVariant.CREATE_PROFILE, "LIFECYCLE_D3.CREATE_PROFILE"),
                Arguments.of(3, LifecycleLayer.C, LifecycleVariant.DISCOVER, "LIFECYCLE_D3.SQUAD"),
                Arguments.of(3, LifecycleLayer.D, LifecycleVariant.DISCOVER, "LIFECYCLE_D3.BESTIE"),
                Arguments.of(3, LifecycleLayer.E, null, null),
                Arguments.of(7, LifecycleLayer.A, LifecycleVariant.NEWBIE_TASKS, "LIFECYCLE_D7.TASKS_LEFT"),
                Arguments.of(7, LifecycleLayer.B, LifecycleVariant.CREATE_PROFILE, "LIFECYCLE_D7.CREATE_PROFILE"),
                Arguments.of(7, LifecycleLayer.C, LifecycleVariant.NEWBIE_TASKS, "LIFECYCLE_D7.TASKS_LEFT"),
                Arguments.of(7, LifecycleLayer.D, LifecycleVariant.NEWBIE_TASKS, "LIFECYCLE_D7.TASKS_LEFT"),
                Arguments.of(7, LifecycleLayer.E, LifecycleVariant.NEWBIE_TASKS, "LIFECYCLE_D7.TASKS_LEFT"));
    }

    @ParameterizedTest(name = "D{0} × {1} → {2} · {3}")
    @MethodSource("table")
    void nodeByLayerTable(int age, LifecycleLayer l, LifecycleVariant variant, String copyKey) {
        for (int offset : new int[] {0, 1}) { // 两天窗口都一样
            List<LifecyclePlannedPush> out = run(layer(l, 1, age + offset));
            if (variant == null) {
                assertThat(out).as("D%d+%d × %s 不产出", age, offset, l).isEmpty();
                continue;
            }
            assertThat(out).singleElement().satisfies(p -> {
                assertThat(p.variant()).isEqualTo(variant);
                assertThat(p.copyKey()).isEqualTo(copyKey);
                assertThat(p.layer()).isEqualTo(l);
                assertThat(p.nodeKey()).isEqualTo(LifecyclePlannedPush.ONCE);
                assertThat(p.markVariant()).isEqualTo(l.name() + "_" + variant.name());
                // A / B 层不带宠物名；C / D / E 带默认宠物名与品种
                if (l == LifecycleLayer.A || l == LifecycleLayer.B) {
                    assertThat(p.petName()).isNull();
                } else {
                    assertThat(p.petName()).isEqualTo("Mochi");
                    assertThat(p.breedWord()).isEqualTo("Persia");
                    assertThat(p.petType()).isEqualTo("CAT");
                }
            });
        }
    }

    @Test
    @DisplayName("A 层且 ③④⑤ 任一完成（没宠物也能关注 / 点赞 / 评论）→ D1 / D3 落 FEED，文案仍是 NO_PET")
    void noPetButInteractedGoesFeed() {
        Fixture f = new Fixture(new UserLifecycleSnapshot(1, TODAY.minusDays(1), null, 0, null), null,
                Set.of("FIRST_LIKE_GIVEN"));
        assertThat(run(f)).singleElement().satisfies(p -> {
            assertThat(p.layer()).isEqualTo(LifecycleLayer.A);
            assertThat(p.variant()).isEqualTo(LifecycleVariant.FEED);
            assertThat(p.copyKey()).isEqualTo("LIFECYCLE_D1.NO_PET");
        });
    }

    @Test
    @DisplayName("分层：petStatus 为空 / ENTHUSIAST / PLANNING 没宠物都是 A；有宠物行一律不是 A / B（不看 petStatus）")
    void layerRules() {
        assertThat(LifecyclePushPlanner.layerOf(new UserLifecycleSnapshot(1, TODAY, null, 0, null), null, Set.of()))
                .isEqualTo(LifecycleLayer.A);
        assertThat(LifecyclePushPlanner.layerOf(new UserLifecycleSnapshot(1, TODAY, null, 0, "ENTHUSIAST"), null,
                Set.of())).isEqualTo(LifecycleLayer.A);
        assertThat(LifecyclePushPlanner.layerOf(new UserLifecycleSnapshot(1, TODAY, null, 0, "PLANNING"), MOCHI,
                Set.of())).isEqualTo(LifecycleLayer.C);
        assertThat(LifecyclePushPlanner.layerOf(new UserLifecycleSnapshot(1, TODAY, null, 2, null), MOCHI,
                Set.of("FIRST_COMMENT"))).isEqualTo(LifecycleLayer.E);
        assertThat(LifecyclePushPlanner.layerOf(new UserLifecycleSnapshot(1, TODAY, null, 2, null), MOCHI,
                Set.of("FIRST_REPLY", "FIRST_LIKE_RECEIVED"))).as("②⑥ 不算互动").isEqualTo(LifecycleLayer.D);
    }

    // ===== AC5：当天已来过 =====

    @Test
    @DisplayName("AC5：D1 / D3 当天已来过 → SKIPPED_ACTIVE（只写 mark）；D7 不受影响照发")
    void activeTodaySkipsEarlyNodesOnly() {
        Fixture d1 = layer(LifecycleLayer.D, 1, 1);
        Fixture d3 = layer(LifecycleLayer.C, 2, 3);
        Fixture d7 = layer(LifecycleLayer.D, 3, 7);
        List<LifecyclePlannedPush> out = run(List.of(d1, d3, d7), Set.of(1L, 2L, 3L), Set.of(), NO_WINBACK);
        assertThat(out).extracting(LifecyclePlannedPush::userId, LifecyclePlannedPush::variant,
                LifecyclePlannedPush::markVariant).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, LifecycleVariant.SKIPPED_ACTIVE, "D_SKIPPED_ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(2L, LifecycleVariant.SKIPPED_ACTIVE, "C_SKIPPED_ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(3L, LifecycleVariant.NEWBIE_TASKS, "D_NEWBIE_TASKS"));
    }

    @Test
    @DisplayName("AC6：D3 E 层即使当天来过也不产出（不写 mark）")
    void d3LayerENeverProduces() {
        assertThat(run(List.of(layer(LifecycleLayer.E, 1, 3)), Set.of(1L), Set.of(), NO_WINBACK)).isEmpty();
    }

    // ===== D7 毕业 / N =====

    @Test
    @DisplayName("D7 毕业判据 ①：已有 LULUS_PEMULA flag（标记不全也算）→ GRADUATED")
    void graduatedByFlag() {
        Fixture f = layer(LifecycleLayer.C, 1, 7);
        assertThat(run(List.of(f), Set.of(), Set.of(1L), NO_WINBACK)).singleElement().satisfies(p -> {
            assertThat(p.variant()).isEqualTo(LifecycleVariant.GRADUATED);
            assertThat(p.copyKey()).isEqualTo("LIFECYCLE_D7.GRADUATED");
            assertThat(p.remaining()).isZero();
        });
    }

    @Test
    @DisplayName("D7 毕业判据 ②：①–⑥ 标记齐全（不依赖 NewbieWeekJob 是否已跑）→ GRADUATED")
    void graduatedByMarks() {
        Fixture f = new Fixture(new UserLifecycleSnapshot(1, TODAY.minusDays(8), null, 3, "HAS_PET"), MOCHI,
                new HashSet<>(LifecyclePushPlanner.NEWBIE_FIRST_SIX));
        assertThat(run(f)).singleElement()
                .satisfies(p -> assertThat(p.variant()).isEqualTo(LifecycleVariant.GRADUATED));
    }

    @Test
    @DisplayName("D7 还差 N 项：4/7（3 个标记 + ⑦）→ N=3；0 个标记 → N=6")
    void remainingCount() {
        Fixture four = new Fixture(new UserLifecycleSnapshot(1, TODAY.minusDays(7), null, 1, "HAS_PET"), MOCHI,
                Set.of("FIRST_DIARY", "FIRST_FOLLOW", "FIRST_REPLY", "ONE_WEEK"));
        assertThat(run(four)).singleElement().satisfies(p -> {
            assertThat(p.variant()).isEqualTo(LifecycleVariant.NEWBIE_TASKS);
            assertThat(p.remaining()).isEqualTo(3);
        });
        assertThat(run(layer(LifecycleLayer.A, 2, 7))).singleElement()
                .satisfies(p -> assertThat(p.remaining()).isEqualTo(6));
    }

    // ===== 窗口 / 排序 / WIB =====

    @Test
    @DisplayName("窗口外（0 / 5 / 6 / 9 / 10 天）不产出；未来注册日 / 注册日空跳过")
    void outsideWindows() {
        for (int age : new int[] {0, 5, 6, 9, 10, 30}) {
            assertThat(run(layer(LifecycleLayer.C, 1, age))).as("age=" + age).isEmpty();
        }
        assertThat(run(layer(LifecycleLayer.C, 1, -1))).isEmpty();
        assertThat(planner.plan(TODAY, List.of(new UserLifecycleSnapshot(1, null, null, 0)),
                LifecycleContext.EMPTY, NO_WINBACK)).isEmpty();
    }

    @Test
    @DisplayName("一人一天一条、按 D1 → D3 → D7 排序")
    void orderedByUrgency() {
        List<LifecyclePlannedPush> out = run(List.of(layer(LifecycleLayer.C, 7, 7), layer(LifecycleLayer.C, 3, 3),
                layer(LifecycleLayer.C, 1, 1)), Set.of(), Set.of(), NO_WINBACK);
        assertThat(out).extracting(LifecyclePlannedPush::type).containsExactly(NotificationType.LIFECYCLE_D1,
                NotificationType.LIFECYCLE_D3, NotificationType.LIFECYCLE_D7);
    }

    @Test
    @DisplayName("AC1 WIB 边界：UTC 11-11 20:00 注册 = WIB 11-12 03:00 → D1 在 WIB 11-13")
    void wibRegisteredDate() {
        LocalDate wibReg = Instant.parse("2026-11-11T20:00:00Z").atZone(ZoneId.of("Asia/Jakarta")).toLocalDate();
        assertThat(wibReg).isEqualTo(LocalDate.of(2026, 11, 12));
        UserLifecycleSnapshot u = new UserLifecycleSnapshot(1, wibReg, null, 0, "HAS_PET");
        LifecycleContext ctx = new LifecycleContext(Map.of(1L, MOCHI), Map.of(), Set.of(), Set.of());
        assertThat(planner.plan(LocalDate.of(2026, 11, 13), List.of(u), ctx, NO_WINBACK)).singleElement()
                .satisfies(p -> assertThat(p.type()).isEqualTo(NotificationType.LIFECYCLE_D1));
        assertThat(planner.plan(LocalDate.of(2026, 11, 12), List.of(u), ctx, NO_WINBACK)).isEmpty();
    }

    // ===== 召回（开关）=====

    @Test
    @DisplayName("召回：开关关 → 30 天没来的老用户不产出；开 → 按月 nodeKey，已建档 RECORD / 未建档 CREATE_PROFILE")
    void winbackBehindOption() {
        UserLifecycleSnapshot lapsedWithPet = new UserLifecycleSnapshot(1, TODAY.minusDays(60), TODAY.minusDays(30),
                2, "HAS_PET");
        UserLifecycleSnapshot lapsedNoPet = new UserLifecycleSnapshot(2, TODAY.minusDays(60), null, 0, null);
        UserLifecycleSnapshot recent = new UserLifecycleSnapshot(3, TODAY.minusDays(60), TODAY.minusDays(2), 2,
                "HAS_PET");
        LifecycleContext ctx = new LifecycleContext(Map.of(1L, MOCHI, 3L, MOCHI), Map.of(), Set.of(), Set.of());
        List<UserLifecycleSnapshot> users = List.of(lapsedWithPet, lapsedNoPet, recent);

        assertThat(planner.plan(TODAY, users, ctx, NO_WINBACK)).isEmpty();
        assertThat(planner.plan(TODAY, users, ctx, new LifecyclePlanOptions(true, 7)))
                .extracting(LifecyclePlannedPush::userId, LifecyclePlannedPush::variant, LifecyclePlannedPush::nodeKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, LifecycleVariant.RECORD, "2026-11"),
                        org.assertj.core.groups.Tuple.tuple(2L, LifecycleVariant.CREATE_PROFILE, "2026-11"));
    }

    @Test
    @DisplayName("宠物名 / 品种空白 → null（dispatcher 兜底）；品种 trim")
    void blankNameAndBreed() {
        Fixture f = new Fixture(new UserLifecycleSnapshot(1, TODAY.minusDays(1), null, 1, null),
                new DefaultPet(" ", "  Persia ", "CAT"), Set.of());
        assertThat(run(f)).singleElement().satisfies(p -> {
            assertThat(p.petName()).isNull();
            assertThat(p.breedWord()).isEqualTo("Persia");
        });
        Fixture noBreed = new Fixture(new UserLifecycleSnapshot(2, TODAY.minusDays(1), null, 1, null),
                new DefaultPet("Kopi", " ", "DOG"), Set.of());
        assertThat(run(noBreed)).singleElement().satisfies(p -> {
            assertThat(p.breedWord()).isNull();
            assertThat(p.petType()).isEqualTo("DOG");
        });
    }
}
