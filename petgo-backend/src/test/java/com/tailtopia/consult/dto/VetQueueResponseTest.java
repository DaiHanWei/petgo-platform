package com.tailtopia.consult.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.profile.dto.PetIdentityView;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（无 DB/Redis）。Story 3.6 兽医队列 DTO 契约。
 *
 * <p>{@code VetQueueItem.of}：宠物身份富化（缺失兜底 null）+ 等待时长（now − createdAt，未持久化 createdAt=null → 0）；
 * {@code VetAwaitingPayItem.of}：服务端权威 payDeadline + 暂停锚透传（FR-53A/A-4）。
 */
class VetQueueResponseTest {

    private static ConsultRequest queued(String token) {
        return ConsultRequest.queue(100L, 200L, token, Instant.parse("2026-07-13T10:01:00Z"));
    }

    @Test
    void queueItemMapsIdentityAndFields() {
        ConsultRequest r = queued("req-abc");
        PetIdentityView pet = new PetIdentityView("旺财", "DOG", 18);

        VetQueueResponse.VetQueueItem item = VetQueueResponse.VetQueueItem.of(r, pet, "机主小明");

        assertThat(item.requestToken()).isEqualTo("req-abc");
        assertThat(item.petName()).isEqualTo("旺财");
        assertThat(item.petSpecies()).isEqualTo("DOG");
        assertThat(item.petAgeMonths()).isEqualTo(18);
        assertThat(item.ownerHandle()).isEqualTo("机主小明");
        assertThat(item.queueDeadlineAt()).isEqualTo(Instant.parse("2026-07-13T10:01:00Z"));
        assertThat(item.waitingSeconds()).isZero(); // 未持久化 createdAt=null → 0
    }

    @Test
    void queueItemGracefulWhenIdentityMissing() {
        // 注销/无档案 → 身份 null，昵称 null（不泄漏、前端降级），仍返 token/deadline。
        VetQueueResponse.VetQueueItem item = VetQueueResponse.VetQueueItem.of(queued("req-xyz"), null, null);

        assertThat(item.requestToken()).isEqualTo("req-xyz");
        assertThat(item.petName()).isNull();
        assertThat(item.petSpecies()).isNull();
        assertThat(item.petAgeMonths()).isNull();
        assertThat(item.ownerHandle()).isNull();
    }

    @Test
    void awaitingPayMapsServerAuthoritativeDeadlineAndPause() {
        ConsultRequest r = queued("req-pay");
        Instant payDeadline = Instant.now().plus(Duration.ofSeconds(90));
        Instant pausedAt = Instant.now();
        ReflectionTestUtils.setField(r, "payDeadlineAt", payDeadline);
        ReflectionTestUtils.setField(r, "pausedAt", pausedAt);

        VetQueueResponse.VetAwaitingPayItem item = VetQueueResponse.VetAwaitingPayItem.of(
                r, new PetIdentityView("阿黄", "DOG", 6));

        assertThat(item.requestToken()).isEqualTo("req-pay");
        assertThat(item.petName()).isEqualTo("阿黄");
        assertThat(item.payDeadlineAt()).isEqualTo(payDeadline); // 服务端权威透传
        assertThat(item.pausedAt()).isEqualTo(pausedAt);          // A-4 暂停锚
    }

    @Test
    void awaitingPayGracefulWhenPetNull() {
        VetQueueResponse.VetAwaitingPayItem item =
                VetQueueResponse.VetAwaitingPayItem.of(queued("req-nopet"), null);
        assertThat(item.requestToken()).isEqualTo("req-nopet");
        assertThat(item.petName()).isNull();
    }
}
