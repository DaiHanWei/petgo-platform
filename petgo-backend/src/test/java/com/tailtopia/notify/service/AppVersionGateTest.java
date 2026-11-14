package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 地基 Story 0.1 · 推送版本门槛（≥ 1.5.0；空 / 解析不了按旧版）。 */
class AppVersionGateTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AppVersionGate gate = new AppVersionGate(users);

    @Test
    void batchKeepsOnlyV150OrLater() {
        when(users.findLastAppVersions(anyCollection())).thenReturn(List.of(
                new Object[] {1L, "1.5.0"},
                new Object[] {2L, "1.5.1-stag"},
                new Object[] {3L, "1.4.2"},
                new Object[] {4L, null},
                new Object[] {5L, "garbage"},
                new Object[] {6L, "2.0.0"}));
        assertThat(gate.usersOnV150OrLater(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)))
                .containsExactlyInAnyOrder(1L, 2L, 6L);
    }

    @Test
    void singleAndEmpty() {
        when(users.findLastAppVersions(anyCollection())).thenReturn(List.<Object[]>of(new Object[] {9L, "1.4.9"}));
        assertThat(gate.reportedV150OrLater(9L)).isFalse();
        assertThat(gate.usersOnV150OrLater(List.of())).isEmpty();
    }

    @Test
    void emptyInputSkipsQuery() {
        gate.usersOnV150OrLater(List.of());
        verify(users, never()).findLastAppVersions(anyCollection());
    }
}
