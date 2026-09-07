package com.tailtopia.content.service;

import com.tailtopia.content.event.ContentUnavailableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 顶置内容被下架 → 即时结束该顶置排期（V1.1.6 Story 4.1 · AC5 / AD-9 Rule 3）。
 *
 * <p>🛡 **走事件订阅，不靠定时扫描器** —— 扫描器没跑到就会出现"该下线的没下线"，
 * 而顶置位是首屏第一条，最刺眼。
 *
 * <h2>⚠️ 这里用的是 {@code @EventListener}，不是 {@code @TransactionalEventListener}</h2>
 * 与 notify 那批监听器**刻意不同**：通知是"事后告知"，事务提交后再发才对（发失败也不该回滚业务）；
 * 而这里是**数据副作用** —— 下架回滚了，顶置也必须跟着回滚，两者要在同一个事务里。
 * 换成提交后触发会出现"下架失败但顶置已被结束"的漂移。加 {@code @Transactional}（默认 REQUIRED）
 * 是为了**汇入调用方那个事务**，而不是另起一个。
 */
@Component
public class ContentPinTakedownListener {

    private static final Logger log = LoggerFactory.getLogger(ContentPinTakedownListener.class);

    private final ContentPinService pins;

    public ContentPinTakedownListener(ContentPinService pins) {
        this.pins = pins;
    }

    @EventListener
    @Transactional
    public void onContentUnavailable(ContentUnavailableEvent event) {
        int ended = pins.terminateForContent(event.postId(), event.at());
        if (ended > 0) {
            log.info("顶置排期随内容下架提前结束 postId={} reason={} count={}",
                    event.postId(), event.reason(), ended);
        }
    }
}
