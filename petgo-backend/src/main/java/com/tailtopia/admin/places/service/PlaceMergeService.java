package com.tailtopia.admin.places.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceStatus;
import com.tailtopia.admin.places.event.PlaceMergedEvent;
import com.tailtopia.admin.places.repository.PlaceCheckinRepository;
import com.tailtopia.admin.places.repository.PlaceCommentRepository;
import com.tailtopia.admin.places.repository.PlacePhotoRepository;
import com.tailtopia.admin.places.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所合并（V1.3.0 Story 5.3 AC4，本版本唯一不可逆的场所操作，<b>单事务</b>）：
 * ① 三张子表 {@code place_id} 从 B（merged）改指 A（keep）② B {@code status=MERGED, merged_into_id=A} ③ A 五个计数列全量重算
 * ④ 发布 {@link PlaceMergedEvent}（事务内发布；App 分支 AFTER_COMMIT + REQUIRES_NEW 监听归并护照章）⑤ 审计 {@code PLACE_MERGED}「B(name) → A(name)」。
 * {@code place_reports} <b>不迁移</b>（举报针对 B 这个条目本身）。校验：A ≠ B；A ACTIVE 且未软删；B ACTIVE / DELISTED（已 MERGED 不可再合并）。
 * 任一步失败整体回滚（半成功比失败更糟）。
 */
@Service
public class PlaceMergeService {

    private static final Logger log = LoggerFactory.getLogger(PlaceMergeService.class);

    private final PlaceRepository places;
    private final PlacePhotoRepository photos;
    private final PlaceCommentRepository comments;
    private final PlaceCheckinRepository checkins;
    private final AdminPlaceService placeService;
    private final AdminAuditService audit;
    private final ApplicationEventPublisher events;

    public PlaceMergeService(PlaceRepository places, PlacePhotoRepository photos, PlaceCommentRepository comments,
            PlaceCheckinRepository checkins, AdminPlaceService placeService, AdminAuditService audit, ApplicationEventPublisher events) {
        this.places = places;
        this.photos = photos;
        this.comments = comments;
        this.checkins = checkins;
        this.placeService = placeService;
        this.audit = audit;
        this.events = events;
    }

    private Place lock(long id) {
        return places.findForUpdateById(id).filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("场所不存在或已删除").code("admin.err.places.notFound"));
    }

    /** 把 {@code mergedId} 并入 {@code keepId}。返回保留场所。 */
    @Transactional
    public Place merge(long mergedId, long keepId, long actorAdminAccountId) {
        if (mergedId == keepId) {
            throw AppException.validation("不能把场所并入自己").code("admin.err.places.mergeSelf");
        }
        // 行锁：并发两次合并 / 合并 + 下架只能成功一个；按 id 升序加锁，A→B 与 B→A 同时提交不会死锁（复审 #5）
        Place first = lock(Math.min(keepId, mergedId));
        Place second = lock(Math.max(keepId, mergedId));
        Place keep = first.getId() == keepId ? first : second;
        Place merged = first.getId() == mergedId ? first : second;
        if (keep.getStatus() != PlaceStatus.ACTIVE) {
            throw AppException.validation("保留场所必须是上架状态").code("admin.err.places.keepNotActive");
        }
        if (merged.getStatus() == PlaceStatus.MERGED) {
            throw AppException.validation("该场所已合并过，不能再合并").code("admin.err.places.mergedNotMergeable");
        }
        int movedPhotos = photos.reassignPlace(mergedId, keepId);
        int movedComments = comments.reassignPlace(mergedId, keepId);
        int movedCheckins = checkins.reassignPlace(mergedId, keepId);
        merged.markMerged(keepId);
        merged.recount(0, 0, 0, 0, 0); // MERGED 行不再展示计数
        places.saveAndFlush(merged);
        placeService.recount(keepId);
        audit.record(actorAdminAccountId, AuditActions.PLACE_MERGED, "PLACE", String.valueOf(mergedId),
                merged.getName() + " → " + keep.getName() + " (keepId=" + keepId + ", photos=" + movedPhotos + ", comments=" + movedComments
                        + ", checkins=" + movedCheckins + ")");
        events.publishEvent(new PlaceMergedEvent(keepId, mergedId, Instant.now(), actorAdminAccountId));
        log.info("place merged mergedId={} keepId={} photos={} comments={} checkins={}", mergedId, keepId, movedPhotos, movedComments, movedCheckins);
        return places.findById(keepId).orElseThrow();
    }
}
