package com.tailtopia.admin.places.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.dto.PlaceEditForm;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所处置——编辑 / 下架 / 恢复 / 删照片 / 删评论（V1.3.0 Story 5.3 AC1～AC3；合并在 {@link PlaceMergeService}）。
 * 写操作三件套：{@code @PreAuthorize}（Controller）+ 同事务 {@code AdminAuditService.record} + 三语 key。
 * 审计 detail 不记评论正文、坐标值、地址全文（架构模式规则）。幂等：已 DELISTED 再下架 / 已 ACTIVE 再恢复 → no-op、不写审计。
 */
@Service
public class AdminPlaceService {

    /** 五个计数列一条 SQL 全量重算（合并 / 举报处置也用）。 */
    static final String RECOUNT_SQL = """
            UPDATE places p SET
                photo_count         = (SELECT COUNT(*) FROM place_photos   x WHERE x.place_id = p.id AND x.deleted_at IS NULL),
                comment_count       = (SELECT COUNT(*) FROM place_comments x WHERE x.place_id = p.id AND x.deleted_at IS NULL),
                checkin_count       = (SELECT COUNT(*) FROM place_checkins x WHERE x.place_id = p.id),
                recommend_count     = (SELECT COUNT(*) FROM place_comments x WHERE x.place_id = p.id AND x.deleted_at IS NULL AND x.attitude = 'RECOMMEND'),
                not_recommend_count = (SELECT COUNT(*) FROM place_comments x WHERE x.place_id = p.id AND x.deleted_at IS NULL AND x.attitude = 'NOT_RECOMMEND'),
                updated_at          = now()
            WHERE p.id = ?
            """;

    private final PlaceRepository places;
    private final PlacePhotoRepository photos;
    private final PlaceCommentRepository comments;
    private final PlaceCoordinateValidator coordinates;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;

    public AdminPlaceService(PlaceRepository places, PlacePhotoRepository photos, PlaceCommentRepository comments,
            PlaceCoordinateValidator coordinates, AdminAuditService audit, JdbcTemplate jdbc) {
        this.places = places;
        this.photos = photos;
        this.comments = comments;
        this.coordinates = coordinates;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** 编辑结果：{@code outsideJakarta} = 坐标落在雅加达都会区包围盒外（200 + 黄条，不拦）。 */
    public record EditResult(boolean changed, boolean outsideJakarta, List<String> changedFields) {
    }

    /** 编辑（AC1）：标记人 / token 不可改（表单不渲染、服务端不接收）；审计 summary 只记改动字段名列表。 */
    @Transactional
    public EditResult edit(long id, PlaceEditForm form, long actorAdminAccountId) {
        Place p = requirePlace(id);
        if (p.getStatus() == PlaceStatus.MERGED) {
            throw AppException.validation("已合并的场所不可编辑").code("admin.err.places.alreadyMerged");
        }
        coordinates.validate(form.lat(), form.lng());
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(p.getName(), form.name())) {
            changed.add("name");
        }
        if (!Objects.equals(p.getPlaceType(), form.placeType())) {
            changed.add("placeType");
        }
        if (!Objects.equals(p.getTags(), form.tags())) {
            changed.add("tags");
        }
        if (!Objects.equals(blankToNull(p.getDescription()), form.description())) { // App 端可能存空串（复审 #9）
            changed.add("description");
        }
        if (!Objects.equals(p.getCity(), form.city())) {
            changed.add("city");
        }
        if (!Objects.equals(p.getAddressText(), form.addressText())) {
            changed.add("addressText");
        }
        if (p.getLat().compareTo(form.lat()) != 0) {
            changed.add("lat");
        }
        if (p.getLng().compareTo(form.lng()) != 0) {
            changed.add("lng");
        }
        boolean outside = coordinates.isOutsideJakarta(form.lat(), form.lng());
        if (changed.isEmpty()) {
            return new EditResult(false, outside, List.of());
        }
        p.edit(form.name(), form.placeType(), form.tags(), form.description(), form.city(), form.addressText(), form.lat(), form.lng());
        audit.record(actorAdminAccountId, AuditActions.PLACE_EDITED, "PLACE", String.valueOf(p.getId()), "fields=" + String.join(",", changed));
        return new EditResult(true, outside, List.copyOf(changed));
    }

    /** 下架（AC2）：ACTIVE → DELISTED；MERGED 422；已下架 no-op 不写审计。返回是否发生变化。 */
    @Transactional
    public boolean delist(long id, long actorAdminAccountId) {
        Place p = requirePlace(id);
        if (p.getStatus() == PlaceStatus.MERGED) {
            throw AppException.validation("已合并的场所不可下架").code("admin.err.places.alreadyMerged");
        }
        if (!p.delist()) {
            return false;
        }
        audit.record(actorAdminAccountId, AuditActions.PLACE_DELISTED, "PLACE", String.valueOf(p.getId()), "name=" + p.getName());
        return true;
    }

    /** 恢复（AC2）：DELISTED → ACTIVE；MERGED 422；已上架 no-op。无需任何数据修复（打卡 / 护照章本就未删）。 */
    @Transactional
    public boolean restore(long id, long actorAdminAccountId) {
        Place p = requirePlace(id);
        if (p.getStatus() == PlaceStatus.MERGED) {
            throw AppException.validation("已合并的场所不可恢复").code("admin.err.places.alreadyMerged");
        }
        if (!p.restore()) {
            return false;
        }
        audit.record(actorAdminAccountId, AuditActions.PLACE_RESTORED, "PLACE", String.valueOf(p.getId()), "name=" + p.getName());
        return true;
    }

    /** 删单张照片（AC3）：软删 + `photo_count −1`（允许删至 0）；不属于该场所 → 404；已删 no-op。 */
    @Transactional
    public boolean removePhoto(long placeId, long photoId, long actorAdminAccountId) {
        Place p = requirePlace(placeId);
        PlacePhoto photo = photos.findById(photoId).filter(x -> x.getPlaceId().equals(p.getId()))
                .orElseThrow(() -> AppException.notFound("照片不存在").code("admin.err.places.photoNotFound"));
        if (!photo.softDelete()) {
            return false;
        }
        p.recount(p.getPhotoCount() - 1, p.getCommentCount(), p.getCheckinCount(), p.getRecommendCount(), p.getNotRecommendCount());
        audit.record(actorAdminAccountId, AuditActions.PLACE_PHOTO_REMOVED, "PLACE_PHOTO", String.valueOf(photoId), "placeId=" + p.getId());
        return true;
    }

    /** 删单条评论（AC3）：软删 + `comment_count −1` + 按态度扣 `recommend_count` / `not_recommend_count`；审计不记正文。 */
    @Transactional
    public boolean removeComment(long placeId, long commentId, long actorAdminAccountId) {
        Place p = requirePlace(placeId);
        PlaceComment c = comments.findById(commentId).filter(x -> x.getPlaceId().equals(p.getId()))
                .orElseThrow(() -> AppException.notFound("评论不存在").code("admin.err.places.commentNotFound"));
        if (!c.softDelete()) {
            return false;
        }
        boolean rec = c.getAttitude() == PlaceAttitude.RECOMMEND;
        p.recount(p.getPhotoCount(), p.getCommentCount() - 1, p.getCheckinCount(),
                p.getRecommendCount() - (rec ? 1 : 0), p.getNotRecommendCount() - (rec ? 0 : 1));
        audit.record(actorAdminAccountId, AuditActions.PLACE_COMMENT_REMOVED, "PLACE_COMMENT", String.valueOf(commentId),
                "placeId=" + p.getId() + ", attitude=" + c.getAttitude());
        return true;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 五个计数列全量重算（合并保留方 / 5.4 举报处置用）。须在调用方事务内。 */
    public void recount(long placeId) {
        jdbc.update(RECOUNT_SQL, placeId);
    }

    /** 写操作一律行锁读取（复审 #4：与合并并发时不能把陈旧列写回覆盖 MERGED）。须在事务内。 */
    Place requirePlace(long id) {
        return places.findForUpdateById(id).filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("场所不存在或已删除").code("admin.err.places.notFound"));
    }
}
