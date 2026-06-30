package com.tailtopia.admin.usermgmt.dto;

import com.tailtopia.consult.service.ConsultHistoryService.SessionMeta;
import com.tailtopia.content.service.ContentService.PostSummary;
import java.time.Instant;
import java.util.List;

/**
 * 后台用户详情聚合（Story 3.1，只读五块）。问诊仅元数据（{@link SessionMeta}，不含对话内容/AI 上下文/媒体）。
 */
public record AdminUserDetailView(
        long id,
        String displayName,
        String nickname,
        String email,
        Instant createdAt,
        boolean deactivated,
        List<PetRow> pets,
        List<PostSummary> posts,
        List<SessionMeta> sessions) {

    /** 宠物档案行（只读摘要）。 */
    public record PetRow(long id, String name, String petType, String breed) {
    }
}
