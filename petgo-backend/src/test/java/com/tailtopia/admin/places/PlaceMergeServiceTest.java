package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.event.PlaceMergedEvent;
import com.tailtopia.admin.places.repository.PlaceCheckinRepository;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.AdminPlaceService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** L0：合并校验顺序 / 步骤顺序 / 事件字段（V1.3.0 Story 5.3 AC4）；仓储全 mock，真事务性在 L1。 */
class PlaceMergeServiceTest {

    private final PlaceRepository places = mock(PlaceRepository.class);
    private final PlacePhotoRepository photos = mock(PlacePhotoRepository.class);
    private final PlaceCommentRepository comments = mock(PlaceCommentRepository.class);
    private final PlaceCheckinRepository checkins = mock(PlaceCheckinRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final com.tailtopia.passport.service.BoardingPassMergeService boardingPasses =
            mock(com.tailtopia.passport.service.BoardingPassMergeService.class);
    private final PlaceMergeService service =
            new PlaceMergeService(places, photos, comments, checkins, audit, events, boardingPasses);

    private static Place place(long id, String name) {
        Place p = Place.create("tok" + id, name, "CAFE", List.of(), null, "Jakarta", "addr", new BigDecimal("-6.2"), new BigDecimal("106.8"), 1L);
        try {
            var f = Place.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    @Test
    void mergeReassignsChildrenMarksMergedRecountsAuditsAndPublishes() {
        Place keep = place(1L, "A");
        Place merged = place(2L, "B");
        when(places.findForUpdateById(1L)).thenReturn(Optional.of(keep));
        when(places.findForUpdateById(2L)).thenReturn(Optional.of(merged));
        when(places.findById(1L)).thenReturn(Optional.of(keep));
        when(photos.reassignPlace(2L, 1L)).thenReturn(3);
        when(comments.reassignPlace(2L, 1L)).thenReturn(2);
        when(checkins.reassignPlace(2L, 1L)).thenReturn(5);
        when(boardingPasses.reassignForMerge(2L, 1L)).thenReturn(4);

        Place result = service.merge(2L, 1L, 42L);

        assertThat(result).isSameAs(keep);
        assertThat(merged.getStatus()).isEqualTo(PlaceStatus.MERGED);
        assertThat(merged.getMergedIntoId()).isEqualTo(1L);
        var order = inOrder(photos, comments, checkins, boardingPasses, places, audit, events);
        order.verify(photos).reassignPlace(2L, 1L);
        order.verify(comments).reassignPlace(2L, 1L);
        order.verify(checkins).reassignPlace(2L, 1L);
        // V1.3.2 Story 3.5：登机牌改挂在打卡之后、markMerged 之前，同事务同步。
        order.verify(boardingPasses).reassignForMerge(2L, 1L);
        // 合并链压平：曾并入 B 的场所改指 A，merged_into_id 恒单跳（契约 X-1）
        order.verify(places).repointMergedInto(eq(2L), eq(1L), any());
        order.verify(places).saveAndFlush(merged);
        // 计数实时统计（2026-09-18 场所表对齐 D3）：合并不再重算缓存列。
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        order.verify(audit).record(eq(42L), eq(AuditActions.PLACE_MERGED), eq("PLACE"), eq("2"), summary.capture());
        assertThat(summary.getValue()).startsWith("B → A").contains("boardingPasses=4");
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(ev.capture());
        PlaceMergedEvent e = (PlaceMergedEvent) ev.getValue();
        assertThat(e.keepPlaceId()).isEqualTo(1L);
        assertThat(e.mergedPlaceId()).isEqualTo(2L);
        assertThat(e.actorAdminAccountId()).isEqualTo(42L);
        assertThat(e.mergedAt()).isNotNull();
    }

    @Test
    void validationOrderSelfKeepNotActiveMergedNotMergeable() {
        assertThatThrownBy(() -> service.merge(1L, 1L, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.places.mergeSelf"));

        Place keep = place(1L, "A");
        keep.delist();
        Place merged = place(2L, "B");
        when(places.findForUpdateById(1L)).thenReturn(Optional.of(keep));
        when(places.findForUpdateById(2L)).thenReturn(Optional.of(merged));
        assertThatThrownBy(() -> service.merge(2L, 1L, 42L)).satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.places.keepNotActive"));

        keep.restore();
        merged.markMerged(9L);
        assertThatThrownBy(() -> service.merge(2L, 1L, 42L)).satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.places.mergedNotMergeable"));

        when(places.findForUpdateById(3L)).thenReturn(Optional.empty());
        // 锁序按 id 升序：merge(3, 1) 先锁 1 再锁 3 → 3 不存在 → 404
        assertThatThrownBy(() -> service.merge(3L, 1L, 42L)).satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.places.notFound"));

        verify(photos, never()).reassignPlace(anyLong(), anyLong());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    /** V1.3.2 Story 3.5 · AC7：登机牌改挂抛出 → 合并不继续（不 markMerged、不审计、不发事件），事务整体回滚。 */
    @Test
    void boardingPassReassignFailureAbortsMerge() {
        Place keep = place(1L, "A");
        Place merged = place(2L, "B");
        when(places.findForUpdateById(1L)).thenReturn(Optional.of(keep));
        when(places.findForUpdateById(2L)).thenReturn(Optional.of(merged));
        when(boardingPasses.reassignForMerge(2L, 1L)).thenThrow(new IllegalStateException("boom"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.merge(2L, 1L, 42L))
                .isInstanceOf(IllegalStateException.class);
        verify(places, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }
}
