package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.content.service.ManualReviewGate;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ManualReviewGate} 的 admin 侧实现（Story 4.3）。content 模块经接口调用、不反向依赖 admin 包。
 * {@link #enabled()} 读开关；{@link #enqueue(long)} 在发布事务内写一条 PENDING 队列项。
 */
@Component
public class ManualReviewGateImpl implements ManualReviewGate {

    private final AdminSettingsService settingsService;
    private final ManualReviewItemRepository queue;

    public ManualReviewGateImpl(AdminSettingsService settingsService,
            ManualReviewItemRepository queue) {
        this.settingsService = settingsService;
        this.queue = queue;
    }

    @Override
    public boolean enabled() {
        return settingsService.isManualReviewEnabled();
    }

    @Override
    @Transactional
    public void enqueue(long contentId) {
        queue.save(ManualReviewItem.pending(contentId, Instant.now()));
    }
}
