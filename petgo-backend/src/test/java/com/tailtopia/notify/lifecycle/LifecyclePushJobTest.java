package com.tailtopia.notify.lifecycle;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.repository.LifecyclePushMarkRepository;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.PetProfileQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X24，AD-13）：账号两只宠物 → 推送用的名字恒为默认（最早建）宠物，
 * 与仓储返回顺序无关（旧实现依赖 {@code findAll()} 无序遍历）。
 */
class LifecyclePushJobTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void petNameIsAlwaysTheDefaultPet() {
        PetProfileRepository repo = mock(PetProfileRepository.class);
        // 读口按 created_at, id 排好序返回（真实 SQL ORDER BY）；owner 1：A(10) 先建、B(11) 后建。
        when(repo.findAll(any(Sort.class))).thenReturn(List.of(
                pet(1L, 10L, PetType.CAT, T0), pet(2L, 20L, PetType.DOG, T0.plusSeconds(5)),
                pet(1L, 11L, PetType.DOG, T0.plusSeconds(60))));
        PetProfileQueryService query = new PetProfileQueryService(repo, mock(IdCardRepository.class));
        AccountQueryService accounts = mock(AccountQueryService.class);
        when(accounts.lifecycleSnapshots()).thenReturn(List.of());
        LifecyclePushPlanner planner = mock(LifecyclePushPlanner.class);
        when(planner.plan(any(), anyList(), any(), anyInt())).thenReturn(List.of());
        LifecyclePushProperties props = new LifecyclePushProperties();
        props.setEnabled(true);
        LifecyclePushJob job = new LifecyclePushJob(accounts, query, planner, mock(LifecyclePushDispatcher.class),
                props, mock(LifecyclePushMarkRepository.class));

        job.runDailyScan();

        ArgumentCaptor<Map<Long, String>> names = ArgumentCaptor.forClass(Map.class);
        verify(planner).plan(any(), anyList(), names.capture(), anyInt());
        assertThat(names.getValue()).containsEntry(1L, "Pet10").containsEntry(2L, "Pet20").hasSize(2);
    }
}
