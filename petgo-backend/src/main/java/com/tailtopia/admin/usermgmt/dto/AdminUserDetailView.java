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
        boolean deleted,
        /**
         * 🔴 用户填写的手机号（V1.1.6 Story 11.4 · PII）。
         *
         * <p>🛡 **无 `user.phone_view` 权限时这里恒为 null** —— 服务端就不装它。
         * 只在模板里隐藏是不够的：数据已经到了浏览器，查看源码或抓接口就能拿到。
         *
         * <p>⚠️ **字段名必须叫 `phone`。** 日志脱敏是**按字段名匹配**的
         * （见 `users.phone` 那条迁移的注释）：叫 phoneNumber / mobile / contact
         * 都不会命中脱敏名单，一旦这个对象被打进日志，真实号码就明晃晃落盘了。
         */
        String phone,
        long pawcoinBalance,
        List<PetRow> pets,
        List<PostSummary> posts,
        List<SessionMeta> sessions) {

    /** 宠物档案行（只读摘要）。 */
    public record PetRow(long id, String name, String petType, String breed) {
    }
}
