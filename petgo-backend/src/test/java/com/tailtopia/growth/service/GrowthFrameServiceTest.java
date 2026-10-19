package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.repository.GrowthAccountRepository;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.web.dto.GrowthPageResponse.Frames;
import com.tailtopia.growth.web.dto.GrowthPageResponse.FrameOption;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 ① Story 3.3 · AC1（L0）：选头像框——锁行、按当前段位校验可用档、幂等写 {@code frame_choice}。 */
class GrowthFrameServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthAccountRepository accounts = mock(GrowthAccountRepository.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final GrowthFrameService service = new GrowthFrameService(jdbc, accounts, levels, clock);

    @BeforeEach
    void table() {
        List<GrowthLevelDef> t = new ArrayList<>();
        for (int lv = 1; lv <= 20; lv++) {
            GrowthTier tier = lv < 5 ? GrowthTier.NEWBIE : lv < 10 ? GrowthTier.BUDDY
                    : lv < 15 ? GrowthTier.GUARDIAN : GrowthTier.STAR;
            t.add(GrowthLevelDef.of(lv, lv * 100L, tier));
        }
        when(levels.findAllByOrderByLevelAsc()).thenReturn(t);
    }

    private void participating(boolean p) {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("participating", p)));
    }

    private GrowthAccount account(int level, FrameChoice choice) {
        GrowthAccount a = GrowthAccount.of(7L, 0, level);
        if (choice != null) {
            a.chooseFrame(choice, Instant.EPOCH);
        }
        when(accounts.lockById(7L)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void buddyChoosesBasic() {
        participating(true);
        GrowthAccount a = account(7, null);

        Frames f = service.choose(7L, FrameChoice.BASIC);

        assertThat(a.getFrameChoice()).isEqualTo(FrameChoice.BASIC);
        assertThat(f.effective()).isEqualTo(FrameChoice.BASIC);
        assertThat(f.manual()).isTrue();
        assertThat(f.options()).extracting(FrameOption::frame, FrameOption::unlocked).containsExactly(
                org.assertj.core.groups.Tuple.tuple(FrameChoice.NONE, true),
                org.assertj.core.groups.Tuple.tuple(FrameChoice.BASIC, true),
                org.assertj.core.groups.Tuple.tuple(FrameChoice.UPGRADED, false),
                org.assertj.core.groups.Tuple.tuple(FrameChoice.ANIMATED, false));
        InOrder order = inOrder(accounts);
        order.verify(accounts).insertIfAbsent(7L);
        order.verify(accounts).lockById(7L);
    }

    @Test
    void buddyChoosingUpgradedIsLockedAndColumnUnchanged() {
        participating(true);
        GrowthAccount a = account(7, FrameChoice.BASIC);

        assertThatThrownBy(() -> service.choose(7L, FrameChoice.UPGRADED))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getStatus().value()).isEqualTo(409);
                    assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.FRAME_LOCKED);
                });
        assertThat(a.getFrameChoice()).isEqualTo(FrameChoice.BASIC);
    }

    @Test
    void newbieCanChooseNone() {
        participating(true);
        GrowthAccount a = account(3, null);

        Frames f = service.choose(7L, FrameChoice.NONE);

        assertThat(a.getFrameChoice()).isEqualTo(FrameChoice.NONE);
        assertThat(f.effective()).isEqualTo(FrameChoice.NONE);
        assertThat(f.manual()).isTrue();
    }

    @Test
    void choosingTheAutoEffectiveFrameMakesItManual() {
        participating(true);
        GrowthAccount a = account(12, null); // 自动 → 当前生效 UPGRADED

        Frames f = service.choose(7L, FrameChoice.UPGRADED);

        assertThat(a.getFrameChoice()).isEqualTo(FrameChoice.UPGRADED);
        assertThat(f.manual()).isTrue();
    }

    @Test
    void sameAsCurrentDoesNotWrite() {
        participating(true);
        GrowthAccount a = account(12, FrameChoice.BASIC);
        Instant before = a.getUpdatedAt();

        service.choose(7L, FrameChoice.BASIC);

        assertThat(a.getUpdatedAt()).isEqualTo(before);
    }

    @Test
    void noAccountRowIsLazyCreatedAndJudgedAsLevelOne() {
        participating(true);
        account(1, null); // insertIfAbsent 建出的默认行：Lv1

        assertThatThrownBy(() -> service.choose(7L, FrameChoice.BASIC))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.FRAME_LOCKED));
        verify(accounts).insertIfAbsent(7L);
    }

    @Test
    void nonParticipantIsLockedWithoutCreatingARow() {
        participating(false);

        assertThatThrownBy(() -> service.choose(7L, FrameChoice.NONE))
                .satisfies(e -> assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.FRAME_LOCKED));
        verify(accounts, never()).insertIfAbsent(anyLong());
    }
}
