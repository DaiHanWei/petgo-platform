package com.tailtopia.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0：注销匿名化（Story 7.3 + 后台展示诉求）。原 email/displayName 置空（业务/公开/vet 侧看到 null，
 * 不泄漏、不影响同邮箱重注册），同时快照到 deletedEmail/deletedDisplayName 供运营后台展示。幂等不覆盖快照。
 */
class UserAnonymizeTest {

    @Test
    void anonymizeWipesLiveColumnsButSnapshotsForAdminDisplay() {
        User u = User.newGoogleUser("g-sub-1", "huahua@example.com", "Huahua", "https://oss/av.jpg");

        u.anonymizeForDeletion(Instant.now());

        // 原列按 7-3 匿名化置空（业务/公开侧读到 null）。
        assertThat(u.getEmail()).isNull();
        assertThat(u.getDisplayName()).isNull();
        assertThat(u.getDeletedAt()).isNotNull();
        // 快照列保留供后台展示「谁注销了」。
        assertThat(u.getDeletedEmail()).isEqualTo("huahua@example.com");
        assertThat(u.getDeletedDisplayName()).isEqualTo("Huahua");
    }

    @Test
    void anonymizeIsIdempotentAndKeepsFirstSnapshot() {
        User u = User.newGoogleUser("g-sub-2", "a@b.com", "Ann", null);
        u.anonymizeForDeletion(Instant.now());
        // 重跑（幂等）：此时原列已 null，快照不得被 null 覆盖。
        u.anonymizeForDeletion(Instant.now());

        assertThat(u.getDeletedEmail()).isEqualTo("a@b.com");
        assertThat(u.getDeletedDisplayName()).isEqualTo("Ann");
    }
}
