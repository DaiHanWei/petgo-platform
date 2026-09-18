package com.tailtopia.place.domain;

import com.tailtopia.content.domain.CommentModerationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 场所评论（V1.3.0 batch-b1 Story 1.7 · AD-8）。软删 {@code deletedAt}；时间戳 UTC。
 *
 * <h2>🔴 没有 parentId —— "只有一级"是结构性的，不是约定</h2>
 * 内容评论（{@code content.domain.Comment}）有 {@code parentId} 两级结构；场所评论
 * <b>只有一级</b>（PRD ③：攻略提示性质，无对话需求）。所以本实体**连那个字段都没有** ——
 * 不要"为以后留一个"：留了它，某天加一个端点就能盖楼。
 *
 * <h2>审核态复用同一个枚举，但**存在自己的列里**</h2>
 * {@link CommentModerationStatus} 是与内容评论共享的**语义**（AC5：同一套三方审核），
 * 而状态本身落在 {@code place_comments.moderation_status} —— 两张表各存各的（AD-8 §3）。
 * 其中 {@code AUTHOR_DEACTIVATED} 由注销级联写入（见 {@link #deactivateAuthor()}）——
 * 读路径的「非 VISIBLE 即对他人不可见」对它天然成立。
 * ⚠️ {@code REJECTED} 目前**没有任何代码往本表写**：它要等运营队列接上场所评论
 * （见 {@code PlaceCommentModerationListener} 的待办）。刻意不先写一个没人调的迁移方法 ——
 * 没被执行过的状态迁移只会给人"已经能处置了"的错觉。
 */
@Entity
@Table(name = "place_comments")
public class PlaceComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    /** 列名随后台 schema（2026-09-18 场所表对齐）；Java 字段名不变。 */
    @Column(name = "author_user_id", nullable = false, updatable = false)
    private Long authorId;

    /** 列宽 500（后台 schema）；App 接口仍校验 ≤200。 */
    @Column(name = "body", nullable = false, length = 500)
    private String body;

    /** 二元态度，null = 未表态（AC3 可以不选）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "attitude", length = 16)
    private PlaceCommentAttitude attitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 24)
    private CommentModerationStatus moderationStatus = CommentModerationStatus.VISIBLE;

    /** 内容版本键（D-CM3）：供陈旧审核结果作废。无编辑端点，故恒为 1。 */
    @Column(name = "content_version", nullable = false)
    private int contentVersion = 1;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaceComment() {
    }

    /**
     * 新建**挂起**评论（先发后审，与内容评论同一范式）。
     *
     * <p>🔴 **只有这一个工厂方法** —— 没有"直接 VISIBLE"的入口：
     * 落 {@code UNDER_REVIEW} 后由异步审核转 VISIBLE 是 fail-closed 的那一半，
     * 给一个能直接落 VISIBLE 的构造等于在旁边开了条绕过审核的路。
     * 作者本人立刻看得见自己那条（读路径的自视豁免），所以体感仍是"秒发"。
     */
    public static PlaceComment createUnderReview(long placeId, long authorId, String body,
            PlaceCommentAttitude attitude) {
        PlaceComment c = new PlaceComment();
        c.placeId = placeId;
        c.authorId = authorId;
        c.body = body;
        c.attitude = attitude;
        c.moderationStatus = CommentModerationStatus.UNDER_REVIEW;
        return c;
    }

    /** 审核通过：UNDER_REVIEW → VISIBLE。仅挂起态可转（幂等）。 */
    public boolean approveModeration() {
        if (moderationStatus == CommentModerationStatus.UNDER_REVIEW) {
            moderationStatus = CommentModerationStatus.VISIBLE;
            return true;
        }
        return false;
    }

    /**
     * 运营下架（Story 1.8 · AC5 触发点 ③）：仅 VISIBLE 可下架 → TAKEN_DOWN（仅作者可见）。
     * 返回是否发生迁移（幂等）。
     *
     * <p>⚠️ 调用它的 {@code PlaceCommentService.takedown} 目前**还没有 admin 端点**（后台处置
     * 走 AB-17A，属 admin 主题）。它存在的理由是**计数的三个触发点必须在同一层收口** ——
     * 没有它，admin 那侧接上时最省事的做法就是直接改仓储，把 👍/👎 计数绕过去。
     */
    public boolean takedown() {
        if (moderationStatus == CommentModerationStatus.VISIBLE) {
            moderationStatus = CommentModerationStatus.TAKEN_DOWN;
            return true;
        }
        return false;
    }

    /**
     * 注销联动（NFR-8 / D1/D2）：作者注销 → 其场所评论对**他人**不可见。
     *
     * <p>⚠️ 与「删除」不是一回事：行还在（评论内容是场所攻略的一部分，不随人消失），
     * 只是不再挂着一个已注销的人的身份对外展示。与内容侧
     * {@code ContentService.deactivateAuthorContent} 同一口径、同一个枚举值。
     *
     * <p>幂等：已是该态 / 已软删 → 返回 false。
     */
    public boolean deactivateAuthor() {
        if (deletedAt == null && moderationStatus != CommentModerationStatus.AUTHOR_DEACTIVATED) {
            moderationStatus = CommentModerationStatus.AUTHOR_DEACTIVATED;
            return true;
        }
        return false;
    }

    /** 软删（用户自删 AC7 / 运营删）。幂等：已删不再动时间戳。 */
    public boolean softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            return true;
        }
        return false;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
    }

    public PlaceCommentAttitude getAttitude() {
        return attitude;
    }

    public CommentModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
