package com.tailtopia.shared.media;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 腾讯 IM 聊天媒体 → 私密桶② 桥接归档器（Story 2.1 占位，<b>Story 2.5 实现</b>）。
 *
 * <p>从腾讯 IM 取所需图，复制一份到 OSS 私密桶，返回应用自有 key 列表。档案 {@code image_keys}
 * 只存这些自有 key，<b>绝不存会过期的 IM URL</b>；展示走 {@link SignedUrlService} 短 TTL 签名。
 *
 * <p>{@link ImMediaFetcher} 由 Epic 5 提供（2.5 期无 bean）：无图则空操作；有图但无 fetcher 抛错。
 * 真实跨云复制需 L2（真实 IM + 私密桶）。
 */
@Component
public class ImToOssArchiver {

    private static final Logger log = LoggerFactory.getLogger(ImToOssArchiver.class);
    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final AliyunOssClient ossClient;
    private final ObjectProvider<ImMediaFetcher> fetcherProvider;
    private final SecureRandom random = new SecureRandom();

    public ImToOssArchiver(AliyunOssClient ossClient, ObjectProvider<ImMediaFetcher> fetcherProvider) {
        this.ossClient = ossClient;
        this.fetcherProvider = fetcherProvider;
    }

    /**
     * 将一组 IM 图复制到私密桶②，返回落桶自有 key（供 health_events.image_keys 引用）。
     * 空引用 → 空操作返回空列表（无图问诊存档常见）。
     */
    public List<String> archiveImImagesToPrivate(long petId, List<String> imImageRefs) {
        if (imImageRefs == null || imImageRefs.isEmpty()) {
            return List.of();
        }
        ImMediaFetcher fetcher = fetcherProvider.getIfAvailable();
        if (fetcher == null) {
            // Epic 5 未接入 IM fetcher：此处不应被有图问诊触达；记录后返回空，避免存 IM URL。
            log.warn("ImMediaFetcher 未配置（Epic 5 提供），跳过 {} 张 IM 图归档", imImageRefs.size());
            return List.of();
        }
        List<String> keys = new ArrayList<>(imImageRefs.size());
        for (String ref : imImageRefs) {
            byte[] bytes = fetcher.fetch(ref);
            String key = buildPrivateKey(petId);
            ossClient.putPrivateObject(key, bytes);
            keys.add(key);
        }
        return keys;
    }

    /** 私密桶健康图对象 key：{@code private/health/<petId>/<随机>.jpg}（不可枚举）。 */
    String buildPrivateKey(long petId) {
        StringBuilder token = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            token.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return "private/health/" + petId + "/" + token + ".jpg";
    }
}
