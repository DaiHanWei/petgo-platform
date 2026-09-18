package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.AdminPlaceService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：合并事务性（V1.3.0 Story 5.3 AC6）——让保留方 {@code recount} 抛异常 → 子表未迁移、B 仍 ACTIVE、无审计。
 * 合并是本版本唯一不可逆操作，半成功比失败更糟。{@code @MockitoBean} 替换 {@code AdminPlaceService}（合并里只用它的 recount）。
 */
class PlaceMergeRollbackIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PlaceCommentRepository comments;
    @Autowired
    private PlaceMergeService mergeService;
    @Autowired
    private PlaceTokenGenerator tokens;
    @Autowired
    private AdminAuditLogRepository audits;
    @MockitoBean
    private AdminPlaceService placeService;

    @Test
    void recountFailureRollsBackEverything() {
        User marker = newUser();
        Place keep = places.save(Place.create(tokens.generate(), "Keep " + UUID.randomUUID(), "CAFE", List.of(), null, "Jakarta", "a",
                new BigDecimal("-6.2"), new BigDecimal("106.8"), marker.getId()));
        Place dup = places.save(Place.create(tokens.generate(), "Dup " + UUID.randomUUID(), "CAFE", List.of(), null, "Jakarta", "b",
                new BigDecimal("-6.2"), new BigDecimal("106.8"), marker.getId()));
        PlaceComment c = comments.save(PlaceComment.create(dup.getId(), marker.getId(), "评论", PlaceAttitude.RECOMMEND));
        doThrow(new IllegalStateException("boom")).when(placeService).recount(anyLong());
        int auditsBefore = audits.findAllByOrderByIdAsc().size();

        assertThatThrownBy(() -> mergeService.merge(dup.getId(), keep.getId(), 1L)).isInstanceOf(IllegalStateException.class);

        assertThat(audits.findAllByOrderByIdAsc().size()).isEqualTo(auditsBefore); // 审计随事务回滚（REQUIRED，非 REQUIRES_NEW）
        assertThat(places.findById(dup.getId()).orElseThrow().getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(places.findById(dup.getId()).orElseThrow().getMergedIntoId()).isNull();
        assertThat(comments.findById(c.getId()).orElseThrow().getPlaceId()).isEqualTo(dup.getId());
    }
}
