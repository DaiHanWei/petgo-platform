package com.tailtopia.admin.virtual.service;

import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.content.event.ContentUnavailableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容不再可展示 → 清掉它的去重指纹（V1.1.6 Story 13.4 · AC4 第三处）。
 *
 * <h2>🔴 不做这件事的后果：同样的文案永久无法重发</h2>
 * 指纹表此前<b>没有任何清理逻辑</b>。运营发了一条、发现有错、删掉重发 ——
 * 第二次会被去重吞掉，而且<b>看不出原因</b>（界面只显示一个"跳过 1 条"）。
 *
 * <h2>🛡 为什么挂事件，而不是在每个删除入口各加一行</h2>
 * story 自己写了这条风险：「要挂在**所有**删除路径上，<b>漏一条就是那条路径删掉的内容
 * 对应的文案永久无法重发</b>」。而删除路径不止一条（作者自删 / 运营下架 /
 * 按作者批量下架 / 审核判违规丢弃），将来还会加。
 *
 * <p>{@link ContentUnavailableEvent} 是 Story 4.1 建的**"这条内容没了"通用事件**，
 * 语义正是"凡是引用它的地方都该收手"，且**软删时无论什么原因都发**。
 * 订阅它 ⇒ 现有四条路径一次覆盖，<b>将来新增的删除路径自动覆盖</b>。
 *
 * <h2>⚠️ 用 {@code @EventListener} + {@code @Transactional}，不是提交后触发</h2>
 * 与 {@code ContentPinTakedownListener} 同一判断：这是**数据副作用**，
 * 删除回滚了指纹清理也必须跟着回滚 —— 否则会出现"内容还在、但指纹已经没了"，
 * 于是同一文案能被重复发布两遍。
 */
@Component
public class SeedHashCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(SeedHashCleanupListener.class);

    private final SeedContentHashRepository hashes;

    public SeedHashCleanupListener(SeedContentHashRepository hashes) {
        this.hashes = hashes;
    }

    @EventListener
    @Transactional
    public void onContentUnavailable(ContentUnavailableEvent event) {
        long removed = hashes.deleteByPostId(event.postId());
        if (removed > 0) {
            // 🛡 留日志：这类"顺带清理"出问题时最难查，有条日志就能回答"那次删除到底清没清"。
            log.info("去重指纹随内容下架清理 postId={} reason={} count={}",
                    event.postId(), event.reason(), removed);
        }
    }
}
