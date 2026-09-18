package com.tailtopia.admin.places.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceComment;
import com.tailtopia.admin.places.domain.PlacePhoto;
import com.tailtopia.admin.places.domain.PlaceReportStatus;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.dto.PlaceEditForm;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceReportRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.virtual.dto.PublishIdentityOption;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 场所处置——编辑 / 下架 / 恢复 / 删照片 / 删评论（V1.3.0 Story 5.3 AC1～AC3；合并在 {@link PlaceMergeService}）+ 新建与举报处置（Story 5.4）。
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
    /** Story 5.4：新建（标记人 ∈ 发布身份池、照片上传）与举报处置。 */
    private final PlaceReportRepository reports;
    private final PlaceTokenGenerator tokens;
    private final AdminPublishIdentityService identities;
    private final AdminSeedImageService images;

    public static final int MAX_PHOTOS = 9;

    public AdminPlaceService(PlaceRepository places, PlacePhotoRepository photos, PlaceCommentRepository comments,
            PlaceCoordinateValidator coordinates, AdminAuditService audit, JdbcTemplate jdbc, PlaceReportRepository reports,
            PlaceTokenGenerator tokens, AdminPublishIdentityService identities, AdminSeedImageService images) {
        this.places = places;
        this.photos = photos;
        this.comments = comments;
        this.coordinates = coordinates;
        this.audit = audit;
        this.jdbc = jdbc;
        this.reports = reports;
        this.tokens = tokens;
        this.identities = identities;
        this.images = images;
    }

    /** 新建结果：{@code placeId} + 雅加达包围盒外警告（200 + 黄条，不拦）。 */
    public record CreateResult(long placeId, boolean outsideJakarta) {
    }

    /**
     * 运营预置录入（Story 5.4 AC2）：字段校验（{@link PlaceEditForm}）→ 坐标（5.3 校验器）→ 标记人须 ∈ 发布身份池
     * （{@code selectableIdentities()}：启用中的虚拟账号 + 授权真实账号，否则 422 {@code admin.err.places.markerNotInPool}）→ token → 保存
     * → 逐张上传照片（12-2 链路 {@code images.upload(file, "places/<id>")}，只存 objectKey，{@code uploader_user_id} = 标记人）→
     * {@code photo_count} = 张数 → 审计 {@code PLACE_CREATED}（summary 记场所名，不记坐标）。不做批量导入。
     */
    @Transactional
    public CreateResult create(PlaceEditForm form, Long markerUserId, List<MultipartFile> photoFiles, long actorAdminAccountId) {
        coordinates.validate(form.lat(), form.lng());
        if (markerUserId == null || identities.selectableIdentities().stream()
                .noneMatch(o -> o.userId() == markerUserId && !o.disabled())) {
            throw AppException.validation("标记人须从运营发布身份池中选择").code("admin.err.places.markerNotInPool");
        }
        List<MultipartFile> files = photoFiles == null ? List.of() : photoFiles.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (files.size() > MAX_PHOTOS) {
            throw AppException.validation("照片最多 9 张").code("admin.err.places.tooManyPhotos");
        }
        files.forEach(images::validate); // 复审 #6：先把全部文件的类型 / 大小 / HEIC 校验跑完，再落库、再逐张上传，避免第 N 张被拒时前几张成 OSS 孤儿
        Place p = places.save(Place.create(tokens.generate(), form.name(), form.placeType(), form.tags(), form.description(), form.city(),
                form.addressText(), form.lat(), form.lng(), markerUserId));
        int uploaded = 0;
        for (MultipartFile f : files) {
            UploadedImage up = images.upload(f, "places/" + p.getId());
            photos.save(PlacePhoto.create(p.getId(), up.objectKey(), markerUserId));
            uploaded++;
        }
        p.recount(uploaded, 0, 0, 0, 0);
        audit.record(actorAdminAccountId, AuditActions.PLACE_CREATED, "PLACE", String.valueOf(p.getId()),
                "name=" + p.getName() + ", photos=" + uploaded + ", markerUserId=" + markerUserId);
        return new CreateResult(p.getId(), coordinates.isOutsideJakarta(form.lat(), form.lng()));
    }

    /** 标记人下拉数据源（Story 5.4）：发布身份池，启用中的在前；默认选中「TailTopia Official」或池内第一项由模板决定。 */
    @Transactional(readOnly = true)
    public List<PublishIdentityOption> markerOptions() {
        return identities.selectableIdentities().stream().filter(o -> !o.disabled()).toList();
    }

    /**
     * 驳回该场所全部 PENDING 举报（Story 5.4 AC4）：→ DISMISSED，记 handled_by / handled_at；审计 {@code PLACE_REPORTS_DISMISSED}。
     * 返回驳回条数（0 = 没有待处理举报，不写审计）。场所已软删也允许驳回（举报对象已不存在，队列要能清）。
     */
    @Transactional
    public int dismissReports(long placeId, long actorAdminAccountId) {
        int n = settleReports(placeId, PlaceReportStatus.DISMISSED, actorAdminAccountId);
        if (n > 0) {
            audit.record(actorAdminAccountId, AuditActions.PLACE_REPORTS_DISMISSED, "PLACE", String.valueOf(placeId), "dismissed=" + n);
        }
        return n;
    }

    /**
     * 该场所全部 PENDING 举报置为 {@code decision}，返回受影响条数。单条 {@code UPDATE … WHERE status='PENDING'}（复审 #7）：
     * 原子且天然幂等——两位管理员同时驳回只有一笔改到行、只记一笔审计；与「下架」（锁场所行）并发也不会把已 ACTIONED 的行改成 DISMISSED。
     */
    private int settleReports(long placeId, PlaceReportStatus decision, long actorAdminAccountId) {
        return reports.settlePending(placeId, decision, actorAdminAccountId, Instant.now());
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
        // Story 5.4 AC4：下架顺带把该场所全部 PENDING 举报置 ACTIONED（同事务）
        int actioned = settleReports(p.getId(), PlaceReportStatus.ACTIONED, actorAdminAccountId);
        audit.record(actorAdminAccountId, AuditActions.PLACE_DELISTED, "PLACE", String.valueOf(p.getId()),
                "name=" + p.getName() + (actioned > 0 ? ", reportsActioned=" + actioned : ""));
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
