package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneDefinition;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.MilestoneCelebrationReportResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * V1.3.0 批次 A · Story 1.4（L0）：庆祝状态记账的**回报通道**（FR-111 · AD-A1 / AD-A2.3b / AD-A3）。
 *
 * <p>本类的重点不在「能不能置位」，而在**置位范围**。对抗性评审 H-2 指出的洞是：
 * 若服务端做 {@code WHERE celebrated_at IS NULL} 的 mark-all，客户端从读取列表到回报之间
 * 那几百毫秒里新解锁的条目（被点赞、被评论都可能触发）会被静默盖章为已庆祝、**永不补弹**
 * —— 用 FR-111 造出 FR-111 本来要修的 bug。{@link WindowRace} 那一组就是钉这件事的。
 */
class MilestoneCelebrationServiceTest {

    private PetProfileRepository profiles;
    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private MilestoneCelebrationService service;

    private long nextId = 1;

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        milestones = Mockito.mock(PetMilestoneRepository.class);
        completions = Mockito.mock(MilestoneCompletionRepository.class);
        service = new MilestoneCelebrationService(profiles, milestones, completions);
    }

    /** 给 owner 7 挂一只猫，并铺一份只含指定 code 的 roster；返回 code → rosterId。 */
    private java.util.Map<String, Long> seedRoster(String... codes) {
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Momo", null, null, null, null, "TOK");
        setField(p, "id", 10L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));

        java.util.Map<String, Long> ids = new java.util.LinkedHashMap<>();
        List<PetMilestone> roster = new ArrayList<>();
        for (String code : codes) {
            MilestoneDefinition def = MilestoneCatalog.byCode(code);
            Assertions.assertThat(def).as("测试用的 code %s 不在清单里", code).isNotNull();
            PetMilestone m = PetMilestone.of(10L, def);
            long id = nextId++;
            setField(m, "id", id);
            ids.put(code, id);
            roster.add(m);
        }
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(10L)).thenReturn(roster);
        return ids;
    }

    // ===== AC5：置位范围按客户端回传列表 =====

    @Nested
    @DisplayName("AC5 只置位客户端点名的那些")
    class ByList {

        @Test
        void marksExactlyTheReportedCodes() {
            var ids = seedRoster("C-S1", "C-S2", "C-M3");
            when(completions.markCelebrated(anyCollection(), any())).thenReturn(2);

            service.reportCelebrated(7L, List.of("C-S1", "C-M3"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> cap = ArgumentCaptor.forClass(Collection.class);
            verify(completions).markCelebrated(cap.capture(), any());
            assertThat(cap.getValue())
                    .as("只有回传的两条该被置位，C-S2 不在列表里")
                    .containsExactlyInAnyOrder(ids.get("C-S1"), ids.get("C-M3"));
        }

        @Test
        void unknownAndForeignCodesAreSilentlyIgnored_notAnError() {
            // 客户端回报的是「我展示了什么」，与服务端状态本就可能有几百毫秒偏差。
            // 为一个对不上的 code 让整次回报失败，只会换来一次多余的补弹。
            var ids = seedRoster("C-S1");
            when(completions.markCelebrated(anyCollection(), any())).thenReturn(1);

            service.reportCelebrated(7L, List.of("C-S1", "D-S1", "不存在的-code"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> cap = ArgumentCaptor.forClass(Collection.class);
            verify(completions).markCelebrated(cap.capture(), any());
            assertThat(cap.getValue()).containsExactly(ids.get("C-S1"));
        }

        @Test
        void duplicateCodesCollapse() {
            var ids = seedRoster("C-S1");
            when(completions.markCelebrated(anyCollection(), any())).thenReturn(1);

            service.reportCelebrated(7L, List.of("C-S1", "C-S1", "C-S1"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> cap = ArgumentCaptor.forClass(Collection.class);
            verify(completions).markCelebrated(cap.capture(), any());
            assertThat(cap.getValue()).containsExactly(ids.get("C-S1"));
        }

        @Test
        void noMatchingCode_skipsTheUpdateEntirely() {
            seedRoster("C-S1");
            when(completions.countByPetMilestoneIdInAndCelebratedAtIsNull(anyCollection()))
                    .thenReturn(3L);

            MilestoneCelebrationReportResponse resp = service.reportCelebrated(7L, List.of("D-S1"));

            verify(completions, never()).markCelebrated(anyCollection(), any());
            assertThat(resp.markedCount()).isZero();
            assertThat(resp.uncelebratedCount()).isEqualTo(3L);
        }
    }

    /**
     * 🔴 <b>本类最重要的一组</b>：回报窗口期内新解锁的条目**不得被吞**。
     *
     * <p>时序：客户端读到「C-S1 未庆祝」→ 展示庆祝 →（这几百毫秒里内容被点赞，C-S15 自动解锁）
     * → 客户端回报 {@code ["C-S1"]}。C-S15 必须**原样留着未庆祝**，等下次进列表页补弹。
     */
    @Nested
    @DisplayName("AC5 🔴 窗口期新解锁的不被吞（对抗性评审 H-2）")
    class WindowRace {

        @Test
        void newlyUnlockedDuringTheWindowIsNotMarked() {
            var ids = seedRoster("C-S1", "C-S15");
            when(completions.markCelebrated(anyCollection(), any())).thenReturn(1);
            // 置位后仍剩 1 条未庆祝 —— 正是窗口期里被点赞解锁的 C-S15。
            when(completions.countByPetMilestoneIdInAndCelebratedAtIsNull(anyCollection()))
                    .thenReturn(1L);

            MilestoneCelebrationReportResponse resp = service.reportCelebrated(7L, List.of("C-S1"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> cap = ArgumentCaptor.forClass(Collection.class);
            verify(completions).markCelebrated(cap.capture(), any());
            assertThat(cap.getValue())
                    .as("C-S15 是回报窗口期内新解锁的，绝不能跟着被置位 —— 那会让它永不补弹")
                    .doesNotContain(ids.get("C-S15"))
                    .containsExactly(ids.get("C-S1"));
            assertThat(resp.uncelebratedCount())
                    .as("剩余未庆祝数应如实返回，客户端据此刷角标")
                    .isEqualTo(1L);
        }

        /**
         * 结构性断言：仓库层**没有**任何「按 petProfileId 批量置位」的方法。
         *
         * <p>与上一条不重复 —— 上一条只证明当前实现没这么干，这一条堵住「日后有人加一个
         * markAllCelebrated(petProfileId) 图省事」。
         */
        @Test
        void repositoryExposesNoMarkAllByProfile() {
            List<String> markMethods =
                    java.util.Arrays.stream(MilestoneCompletionRepository.class.getDeclaredMethods())
                            .map(java.lang.reflect.Method::getName)
                            .filter(n -> n.toLowerCase(java.util.Locale.ROOT).contains("celebrat"))
                            .toList();
            assertThat(markMethods)
                    .as("置位入口只允许「按 id 列表」这一个；出现 markAll / byProfile 之类即为 H-2 回归")
                    .containsExactlyInAnyOrder(
                            "markCelebrated", "countByPetMilestoneIdInAndCelebratedAtIsNull");
        }
    }

    // ===== AC4：幂等 =====

    @Test
    @DisplayName("AC4 幂等：已有 celebrated_at 的不覆盖 —— 由 WHERE celebrated_at IS NULL 保证")
    void idempotent_alreadyCelebratedRowsAreNotCounted() {
        seedRoster("C-S1", "C-S2");
        // 两条都回报，但只有一条真的从 NULL 变成非空（另一条上次已庆祝过）。
        when(completions.markCelebrated(anyCollection(), any())).thenReturn(1);
        when(completions.countByPetMilestoneIdInAndCelebratedAtIsNull(anyCollection())).thenReturn(0L);

        MilestoneCelebrationReportResponse resp =
                service.reportCelebrated(7L, List.of("C-S1", "C-S2"));

        assertThat(resp.markedCount())
                .as("markedCount 是实际由 NULL 变非空的行数，不是请求里的 code 数")
                .isEqualTo(1);
        assertThat(resp.uncelebratedCount()).isZero();
    }

    /** 幂等靠的是 JPQL 里那半句 —— 它被删掉就等于每次回报都覆盖首次庆祝时刻。 */
    @Test
    @DisplayName("AC4 markCelebrated 的查询必须带 celebrated_at is null")
    void markQueryKeepsTheNullGuard() throws NoSuchMethodException {
        var q = MilestoneCompletionRepository.class
                .getDeclaredMethod("markCelebrated", Collection.class, Instant.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class);
        assertThat(q).isNotNull();
        assertThat(q.value().replaceAll("\\s+", " "))
                .contains("celebratedAt is null")
                .contains("petMilestoneId in :ids");
    }

    // ===== 角标计数 & 边界 =====

    @Test
    @DisplayName("AC6 未庆祝计数：roster 缺失 → 0，不炸")
    void countUncelebratedReturnsZeroWhenRosterMissing() {
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(10L)).thenReturn(List.of());

        assertThat(service.countUncelebrated(10L)).isZero();
        verify(completions, never()).countByPetMilestoneIdInAndCelebratedAtIsNull(anyCollection());
    }

    @Test
    void noProfile_is404() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> service.reportCelebrated(7L, List.of("C-S1")))
                .isInstanceOf(AppException.class);
    }

    @Test
    void emptyRoster_reportsZeroWithoutTouchingCompletions() {
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Momo", null, null, null, null, "TOK");
        setField(p, "id", 10L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));
        when(milestones.findByPetProfileIdOrderBySortOrderAsc(10L)).thenReturn(List.of());

        MilestoneCelebrationReportResponse resp = service.reportCelebrated(7L, List.of("C-S1"));

        assertThat(resp.markedCount()).isZero();
        assertThat(resp.uncelebratedCount()).isZero();
        verify(completions, never()).markCelebrated(anyCollection(), any());
    }

    private static void setField(Object o, String name, Object value) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
