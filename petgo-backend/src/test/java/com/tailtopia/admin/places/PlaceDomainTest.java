package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportReason;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.domain.PlaceType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：场所状态机 / 合并指向 / 计数缓存 / 举报处置（V1.3.0 Story 5.1 AC2 / AC3）。 */
class PlaceDomainTest {

    private static Place place() {
        return Place.create("tok", "Kopi Kucing", "CAFE", List.of("PET_FRIENDLY"), null, "Jakarta", "Jl. Sudirman 1",
                new BigDecimal("-6.208763"), new BigDecimal("106.845599"), 7L);
    }

    @Test
    void delistRestoreAndMergedIsTerminal() {
        Place p = place();
        assertThat(p.getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(p.isVisibleToUsers()).isTrue();
        assertThat(p.delist()).isTrue();
        assertThat(p.delist()).isFalse();
        assertThat(p.isVisibleToUsers()).isFalse();
        assertThat(p.restore()).isTrue();
        assertThat(p.restore()).isFalse();
        p.markMerged(99L);
        assertThat(p.getStatus()).isEqualTo(PlaceStatus.MERGED);
        assertThat(p.getMergedIntoId()).isEqualTo(99L);
        assertThatThrownBy(() -> p.markMerged(100L)).isInstanceOf(IllegalStateException.class); // 已 MERGED 不静默改指向
        assertThat(p.isVisibleToUsers()).isFalse();
        assertThatThrownBy(p::delist).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::restore).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recountClampsAndSoftDeleteIsIdempotent() {
        Place p = place();
        p.recount(3, 2, 5, 1, -4);
        assertThat(p.getPhotoCount()).isEqualTo(3);
        assertThat(p.getNotRecommendCount()).isZero();
        assertThat(p.isDeleted()).isFalse();
        p.softDelete();
        var first = p.getDeletedAt();
        assertThat(p.isDeleted()).isTrue();
        p.softDelete();
        assertThat(p.getDeletedAt()).isSameAs(first); // 幂等：同一实例，不重新取时间
        assertThat(p.isVisibleToUsers()).isFalse();
        assertThatThrownBy(() -> p.markMerged(99L)).isInstanceOf(IllegalStateException.class); // 已软删不可合并
    }

    @Test
    void placeTypeIsSoftValidated() {
        assertThat(PlaceType.isKnown("CAFE")).isTrue();
        assertThat(PlaceType.isKnown("SPA")).isFalse(); // 未知值只警告不拒（Dev Notes），列本身是字符串
        assertThat(PlaceType.isKnown(null)).isFalse();
    }

    @Test
    void reportHandleByOnlyOnceAndNeverBackToPending() {
        PlaceReport r = PlaceReport.create(1L, 2L, PlaceReportReason.DUPLICATE);
        assertThat(r.getStatus()).isEqualTo(PlaceReportStatus.PENDING);
        assertThatThrownBy(() -> r.handleBy(5L, PlaceReportStatus.PENDING)).isInstanceOf(IllegalArgumentException.class);
        r.handleBy(5L, PlaceReportStatus.ACTIONED);
        assertThat(r.getStatus()).isEqualTo(PlaceReportStatus.ACTIONED);
        assertThat(r.getHandledBy()).isEqualTo(5L);
        assertThat(r.getHandledAt()).isNotNull();
        assertThatThrownBy(() -> r.handleBy(5L, PlaceReportStatus.DISMISSED)).isInstanceOf(IllegalStateException.class);
    }
}
