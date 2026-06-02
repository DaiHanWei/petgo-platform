package com.petgo.profile.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 名片 OG 预渲染图重渲染触发（Story 2.8 · B2，编辑联动 2.6）。
 *
 * <p>档案头像/名字/品种变更后异步重渲染 OG 静态图（{@code @Async}，**不引入 MQ**）。
 * 失败不影响编辑主流程（仅记录）；名片正文 2.6 每请求实时读库，无需此步。
 */
@Service
public class CardRerenderService {

    private static final Logger log = LoggerFactory.getLogger(CardRerenderService.class);

    private final ProfileService profileService;
    private final OgImageService ogImageService;

    public CardRerenderService(ProfileService profileService, OgImageService ogImageService) {
        this.profileService = profileService;
        this.ogImageService = ogImageService;
    }

    /** 异步重渲染指定档案的 OG 图（编辑成功后调用）。 */
    @Async
    public void scheduleRerender(long profileId) {
        try {
            profileService.findById(profileId).ifPresent(ogImageService::regenerate);
        } catch (RuntimeException e) {
            // L2 缺 OSS 凭证或上游异常时不影响编辑主流程，仅记录类型（不落 PII）。
            log.warn("OG 图重渲染失败 profileId={} : {}", profileId, e.getClass().getSimpleName());
        }
    }
}
