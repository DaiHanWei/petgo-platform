package com.tailtopia.ops.popup.service;

import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注销联动（V1.3.4 Story 5.1 · AC5 · D1/D2）：物理删除该用户全部弹窗曝光行，幂等。
 *
 * <p>🔴 注销<b>不物理删 users 行</b>（只匿名化），FK 的 {@code ON DELETE CASCADE} 在生产注销路径上不会触发 ——
 * 必须由 {@code AccountDeletionService} 显式调用本方法。FK CASCADE 保留是为测试库 {@code deleteAll} 不被阻断。
 */
@Service
public class OpsPopupImpressionDeletionService {

    private final OpsPopupImpressionRepository impressions;

    public OpsPopupImpressionDeletionService(OpsPopupImpressionRepository impressions) {
        this.impressions = impressions;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        impressions.deleteByUserId(userId);
    }
}
