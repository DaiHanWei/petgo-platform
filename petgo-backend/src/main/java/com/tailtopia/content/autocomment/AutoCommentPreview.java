package com.tailtopia.content.autocomment;

import java.time.Instant;

/**
 * 单帖预览结果（测试用，不发评论、不写留档）。
 *
 * @param outcome   OK（生成了评论）/ AI_SKIPPED / NO_IMAGE / FAILED
 * @param aiLive    false = 当前环境是 stub，comment 是固定文案，不代表真实效果
 * @param candidate 这个帖子此刻是否满足定时任务的条件（已发布 + 公开 + 未删 + 零评论；不含发帖时长与起始日期）
 */
public record AutoCommentPreview(
        long postId,
        String postStatus,
        String visibility,
        boolean deleted,
        Instant postCreatedAt,
        boolean hasComment,
        boolean candidate,
        String imageUrl,
        String species,
        String petName,
        Long virtualUserId,
        String virtualNickname,
        String model,
        String promptVersion,
        boolean aiLive,
        String outcome,
        String comment,
        String skipReason,
        Long latencyMs,
        String errorCode) {

    static Builder builder(long postId) {
        return new Builder(postId);
    }

    static final class Builder {
        private final long postId;
        private String postStatus;
        private String visibility;
        private boolean deleted;
        private Instant postCreatedAt;
        private boolean hasComment;
        private String imageUrl;
        private String species;
        private String petName;
        private Long virtualUserId;
        private String virtualNickname;
        private String model;
        private String promptVersion;
        private boolean aiLive;

        private Builder(long postId) {
            this.postId = postId;
        }

        Builder post(String postStatus, String visibility, boolean deleted, Instant createdAt, boolean hasComment) {
            this.postStatus = postStatus;
            this.visibility = visibility;
            this.deleted = deleted;
            this.postCreatedAt = createdAt;
            this.hasComment = hasComment;
            return this;
        }

        Builder input(String imageUrl, String species, String petName) {
            this.imageUrl = imageUrl;
            this.species = species;
            this.petName = petName;
            return this;
        }

        Builder identity(Long virtualUserId, String virtualNickname) {
            this.virtualUserId = virtualUserId;
            this.virtualNickname = virtualNickname;
            return this;
        }

        Builder ai(String model, String promptVersion, boolean aiLive) {
            this.model = model;
            this.promptVersion = promptVersion;
            this.aiLive = aiLive;
            return this;
        }

        AutoCommentPreview outcome(String outcome, String comment, String skipReason, Long latencyMs,
                String errorCode) {
            boolean candidate = "PUBLISHED".equals(postStatus) && "PUBLIC".equals(visibility) && !deleted
                    && !hasComment;
            return new AutoCommentPreview(postId, postStatus, visibility, deleted, postCreatedAt, hasComment,
                    candidate, imageUrl, species, petName, virtualUserId, virtualNickname, model, promptVersion,
                    aiLive, outcome, comment, skipReason, latencyMs, errorCode);
        }
    }
}
