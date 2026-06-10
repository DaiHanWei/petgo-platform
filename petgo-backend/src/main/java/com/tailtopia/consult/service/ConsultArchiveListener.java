package com.tailtopia.consult.service;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.shared.media.ImToOssArchiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * IM→OSS 存档桥接（Story 5.6，FR-16）。{@code @Async} 消费 {@link ConsultClosedEvent}：
 * 把会话所需聊天媒体从腾讯 IM 复制一份到<b>私密桶档案路径</b>（{@link ImToOssArchiver}），
 * 档案只引用应用自有 URL（不引用会过期的 IM URL）。
 *
 * <p>跨模块经事件（consult 发事件，本监听器在 consult 侧编排存档；profile/Epic 2 另行订阅落地成长档案）。
 * <b>存档失败不阻断会话 CLOSED</b>（异步、独立事务）。真实拉取 IM 聊天媒体需 L2（真实 IM + {@code ImMediaFetcher}）。
 */
@Component
public class ConsultArchiveListener {

    private static final Logger log = LoggerFactory.getLogger(ConsultArchiveListener.class);

    private final ImToOssArchiver archiver;

    public ConsultArchiveListener(ImToOssArchiver archiver) {
        this.archiver = archiver;
    }

    @Async
    @TransactionalEventListener
    public void onConsultClosed(ConsultClosedEvent event) {
        try {
            // AI 上下文私密图本就在私密桶②，无需复制（直接被档案引用）。
            // 聊天产生的 IM 媒体复制到私密桶档案路径——真实拉取需 IM fetcher（Epic 5/L2）；
            // 无 fetcher 时 archiver 空操作（不存 IM URL，绝不外泄）。
            var keys = archiver.archiveImImagesToPrivate(event.sessionId(),
                    event.aiImageRefs() == null ? java.util.List.of() : event.aiImageRefs());
            log.info("会话存档桥接完成 sessionId={} archivedKeys={}", event.sessionId(), keys.size());
        } catch (RuntimeException e) {
            // 失败不阻断关闭；DB 状态机层面会话已 CLOSED，存档为额外操作（可后续补偿）。
            log.warn("会话存档桥接失败 sessionId={} cause={}", event.sessionId(), e.getClass().getSimpleName());
        }
    }
}
