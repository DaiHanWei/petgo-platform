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
    private final AdminPlaceService placeService = mock(AdminPlaceService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlaceMergeService service = new PlaceMergeService(places, photos, comments, checkins, placeService, audit, events);

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

        Place result = service.merge(2L, 1L, 42L);

        assertThat(result).isSameAs(keep);
        assertThat(merged.getStatus()).isEqualTo(PlaceStatus.MERGED);
        assertThat(merged.getMergedIntoId()).isEqualTo(1L);
        assertThat(merged.getPhotoCount()).isZero();
        var order = inOrder(photos, comments, checkins, places, placeService, audit, events);
        order.verify(photos).reassignPlace(2L, 1L);
        order.verify(comments).reassignPlace(2L, 1L);
        order.verify(checkins).reassignPlace(2L, 1L);
        order.verify(places).saveAndFlush(merged);
        order.verify(placeService).recount(1L);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        order.verify(audit).record(eq(42L), eq(AuditActions.PLACE_MERGED), eq("PLACE"), eq("2"), summary.capture());
        assertThat(summary.getValue()).startsWith("B → A");
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
}
