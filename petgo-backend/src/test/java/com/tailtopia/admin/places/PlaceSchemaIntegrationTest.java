package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceCheckin;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceReport;
import com.tailtopia.admin.places.domain.PlaceReportReason;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.repository.PlaceCheckinRepository;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceReportRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（真库）：五表迁移 + {@code ddl-auto=validate}（拉起上下文即验证）+ CHECK / 唯一约束（V1.3.0 Story 5.1 AC1 / AC2 / AC5）：
 * 五实体经仓储落库回读（JSONB tags / NUMERIC(9,6) / 枚举）；MERGED 无 merged_into_id 被拒；ACTIVE 带 merged_into_id 被拒；
 * 同人同场所第二条举报被唯一约束拒。
 */
class PlaceSchemaIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private PlacePhotoRepository photos;
    @Autowired
    private PlaceCommentRepository comments;
    @Autowired
    private PlaceCheckinRepository checkins;
    @Autowired
    private PlaceReportRepository reports;
    @Autowired
    private PlaceTokenGenerator tokens;
    @Autowired
    private JdbcTemplate jdbc;

    private Place newPlace(User marker) {
        return places.save(Place.create(tokens.generate(), "Kopi Kucing " + SEQ.incrementAndGet(), "CAFE", List.of("PET_FRIENDLY", "OUTDOOR"),
                "猫咪咖啡馆", "Jakarta", "Jl. Sudirman 1", new BigDecimal("-6.208763"), new BigDecimal("106.845599"), marker.getId()));
    }

    @Test
    void fiveEntitiesRoundTripThroughRepositories() {
        User marker = newUser();
        User other = newUser();
        Place p = newPlace(marker);
        Place loaded = places.findByPublicToken(p.getPublicToken()).orElseThrow();
        assertThat(loaded.getTags()).containsExactly("PET_FRIENDLY", "OUTDOOR");
        assertThat(loaded.getLat()).isEqualByComparingTo("-6.208763");
        assertThat(loaded.getCity()).isEqualTo("Jakarta");
        assertThat(loaded.getStatus()).isEqualTo(PlaceStatus.ACTIVE);
        assertThat(places.findByIdAndDeletedAtIsNull(p.getId())).isPresent();

        photos.save(PlacePhoto.create(p.getId(), "places/" + p.getId() + "/a.jpg", other.getId()));
        comments.save(PlaceComment.create(p.getId(), other.getId(), "很友好", PlaceAttitude.RECOMMEND));
        checkins.save(PlaceCheckin.create(p.getId(), other.getId()));
        PlaceReport r = reports.save(PlaceReport.create(p.getId(), other.getId(), PlaceReportReason.CLOSED));
        assertThat(photos.countByPlaceIdAndDeletedAtIsNull(p.getId())).isEqualTo(1);
        assertThat(comments.countByPlaceIdAndAttitudeAndDeletedAtIsNull(p.getId(), PlaceAttitude.RECOMMEND)).isEqualTo(1);
        assertThat(checkins.countByPlaceId(p.getId())).isEqualTo(1);
        assertThat(reports.countByStatus(PlaceReportStatus.PENDING)).isGreaterThanOrEqualTo(1);
        assertThat(reports.findByPlaceIdAndReporterUserId(p.getId(), other.getId())).isPresent();

        // 同人同场所第二条举报 → 唯一约束拒（服务层幂等）
        assertThatThrownBy(() -> reports.saveAndFlush(PlaceReport.create(p.getId(), other.getId(), PlaceReportReason.OTHER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(r.getStatus()).isEqualTo(PlaceReportStatus.PENDING);

        // 软删后列表查询默认看不到
        Place gone = places.findById(p.getId()).orElseThrow();
        gone.softDelete();
        places.saveAndFlush(gone);
        assertThat(places.findByIdAndDeletedAtIsNull(p.getId())).isEmpty();
    }

    @Test
    void mergedRefCheckConstraintRejectsInconsistentRows() {
        User marker = newUser();
        Place keep = newPlace(marker);
        // MERGED 且 merged_into_id 为空 → 拒
        assertThatThrownBy(() -> jdbc.update("INSERT INTO places (public_token, name, place_type, city, address_text, lat, lng, marked_by_user_id, status)"
                + " VALUES (?, 'x', 'CAFE', 'Jakarta', 'addr', 0, 0, ?, 'MERGED')", tokens.generate(), marker.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // ACTIVE 且 merged_into_id 非空 → 拒
        assertThatThrownBy(() -> jdbc.update("INSERT INTO places (public_token, name, place_type, city, address_text, lat, lng, marked_by_user_id, status, merged_into_id)"
                + " VALUES (?, 'x', 'CAFE', 'Jakarta', 'addr', 0, 0, ?, 'ACTIVE', ?)", tokens.generate(), marker.getId(), keep.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 自合并 → 拒（ck_places_no_self_merge）
        assertThatThrownBy(() -> jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = id WHERE id = ?", keep.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 合法：MERGED + merged_into_id（经实体 markMerged）
        Place dup = newPlace(marker);
        dup.markMerged(keep.getId());
        places.saveAndFlush(dup);
        assertThat(places.findById(dup.getId()).orElseThrow().getMergedIntoId()).isEqualTo(keep.getId());
        assertThat(places.countByStatusAndDeletedAtIsNull(PlaceStatus.MERGED)).isGreaterThanOrEqualTo(1);
    }
}
