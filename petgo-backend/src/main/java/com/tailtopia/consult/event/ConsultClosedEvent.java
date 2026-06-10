package com.tailtopia.consult.event;

import java.util.List;

/**
 * 会话已关闭事件（Story 5.6）。CLOSED 时发布，触发：
 * <ul>
 *   <li>IM→OSS 存档桥接（{@code ImToOssArchiver} 复制聊天媒体到私密桶档案路径，FR-16）。</li>
 *   <li>profile 模块订阅落地成长档案（Epic 2，跨模块经事件，不直调 repository）。</li>
 * </ul>
 * 过去式命名。{@code imConversationId}/{@code aiImageRefs} 供存档拉取媒体（真实拉取属 L2）。
 */
public record ConsultClosedEvent(
        long sessionId,
        long userId,
        Long vetId,
        Long petId,
        String imConversationId,
        List<String> aiImageRefs,
        boolean rated) {
}
