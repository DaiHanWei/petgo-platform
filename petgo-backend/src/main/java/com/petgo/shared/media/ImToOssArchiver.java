package com.petgo.shared.media;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 腾讯 IM 聊天媒体 → 私密桶② 桥接归档器（占位）。
 *
 * <p><b>Story 2.1 仅占位以固化目录结构</b>，避免 Story 2.5 改动 shared 结构。实际实现（拉取 IM
 * 托管媒体、去 EXIF、写入私密桶、回填 health_events）留 <b>Story 2.5</b>；后续 Epic 5 复用。
 */
@Component
public class ImToOssArchiver {

    /**
     * 将一次会话的 IM 图片归档到私密桶②，返回落桶对象 key（供 health_events 引用）。
     *
     * @param userId       归属用户（前缀隔离）
     * @param imMessageIds 待归档的 IM 消息 id
     * @return 归档后的私密桶对象 key 列表
     * @throws UnsupportedOperationException 占位：实现见 Story 2.5
     */
    public List<String> archiveImImagesToPrivate(long userId, List<String> imMessageIds) {
        throw new UnsupportedOperationException("ImToOssArchiver 实现留 Story 2.5（本 Story 仅占位）");
    }
}
