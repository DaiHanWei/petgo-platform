package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.5.0 onboarding-recall Story 2.1 · AC8 / AC9 —— 7 项账号级进度（按设计重写：旧 6 项用例已搬到
 * {@link LegacyNewbieTaskCompatTest}）。无宠物 200、⑦ 按 WIB 注册日、{@code oneWeekDaysLeft}、{@code replyGoPostId}。
 */
class NewbieTaskServiceTest {

    private static final long U = 7L;
    // 2026-11-15 10:00 WIB
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    private PetProfileRepository profiles;
    private UserNewbieMarkDao marks;
    private AccountQueryService accounts;
    private ContentService content;
    private LulusPemulaReader lulus;
    private NewbieTaskService service;

    @BeforeEach
    void setUp() {
        profiles = mock(PetProfileRepository.class);
        marks = mock(UserNewbieMarkDao.class);
        accounts = mock(AccountQueryService.class);
        content = mock(ContentService.class);
        lulus = mock(LulusPemulaReader.class);
        service = new NewbieTaskService(profiles, marks, accounts, content, lulus);
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(U)).thenReturn(List.of());
        when(marks.keysOf(U)).thenReturn(Set.of());
        when(content.latestPostCommentedByOthers(anyLong())).thenReturn(Optional.empty());
    }

    private void registeredAt(String iso) {
        when(accounts.createdAtOf(U)).thenReturn(Optional.of(Instant.parse(iso)));
    }

    private static Map<String, Boolean> byKey(NewbieTaskResponse r) {
        return r.items().stream().collect(Collectors.toMap(NewbieTaskResponse.Item::key, NewbieTaskResponse.Item::done));
    }

    @Test
    void 无宠物也有7项进度() {
        registeredAt("2026-11-14T03:00:00Z");
        when(marks.keysOf(U)).thenReturn(Set.of("FIRST_FOLLOW", "FIRST_LIKE_GIVEN"));

        NewbieTaskResponse r = service.progress(U, NOW);

        assertThat(r.total()).isEqualTo(7);
        assertThat(r.items()).extracting(NewbieTaskResponse.Item::key)
                .containsExactly(NewbieTask.ALL.stream().map(Enum::name).toArray(String[]::new));
        assertThat(r.completedCount()).isEqualTo(2);
        assertThat(r.hasPet()).isFalse();
        assertThat(byKey(r)).containsEntry("FIRST_FOLLOW", true).containsEntry("FIRST_DIARY", false);
    }

    @Test
    void 有只读宠物也算有宠物() {
        registeredAt("2026-11-14T03:00:00Z");
        PetProfile p = PetProfile.create(U, PetType.CAT, "Momo", null, null, null, null, "T");
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(U)).thenReturn(List.of(p));
        assertThat(service.progress(U, NOW).hasPet()).isTrue();
    }

    @Test
    void 第七项按WIB注册日加7天判定且不依赖标记() {
        // WIB 2026-11-08 06:00（UTC 11-07 23:00）注册 → 11-15（WIB）恰满 7 天。
        registeredAt("2026-11-07T23:00:00Z");
        NewbieTaskResponse r = service.progress(U, NOW);
        assertThat(byKey(r)).containsEntry("ONE_WEEK", true);
        assertThat(r.oneWeekDaysLeft()).isZero();
        assertThat(r.completedCount()).isEqualTo(1);
    }

    @Test
    void 未满一周给剩余天数且至少1() {
        registeredAt("2026-11-09T03:00:00Z"); // WIB 11-09 → 6 天
        NewbieTaskResponse r = service.progress(U, NOW);
        assertThat(byKey(r)).containsEntry("ONE_WEEK", false);
        assertThat(r.oneWeekDaysLeft()).isEqualTo(1);

        registeredAt("2026-11-15T02:00:00Z"); // 当天注册
        assertThat(service.progress(U, NOW).oneWeekDaysLeft()).isEqualTo(7);
    }

    @Test
    void 回复未完成时给最近被别人评论的帖子id() {
        registeredAt("2026-11-14T03:00:00Z");
        when(content.latestPostCommentedByOthers(U)).thenReturn(Optional.of(321L));
        assertThat(service.progress(U, NOW).replyGoPostId()).isEqualTo(321L);
    }

    @Test
    void 回复已完成不查也不给帖子id() {
        registeredAt("2026-11-14T03:00:00Z");
        when(marks.keysOf(U)).thenReturn(Set.of("FIRST_REPLY"));
        assertThat(service.progress(U, NOW).replyGoPostId()).isNull();
        verify(content, never()).latestPostCommentedByOthers(anyLong());
    }

    @Test
    void Lulus解锁走统一读取口() {
        registeredAt("2026-11-14T03:00:00Z");
        when(lulus.unlocked(org.mockito.ArgumentMatchers.eq(U), anyList())).thenReturn(true);
        assertThat(service.progress(U, NOW).lulusPemulaUnlocked()).isTrue();
    }
}
