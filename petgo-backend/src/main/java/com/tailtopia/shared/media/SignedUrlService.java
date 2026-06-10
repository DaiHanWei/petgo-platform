package com.petgo.shared.media;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.petgo.shared.error.AppException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 私密桶短 TTL 签名 URL 签发（Story 2.1 · AC3）。
 *
 * <p>私密桶②对象仅经签名 URL 访问，TTL={@code media.signed-url.ttl-seconds}（默认 300s）。
 * 复用入口：Epic 4 GeminiClient 拉私密图、Story 2.5 健康历史展示。
 *
 * <p>护栏：签名 URL 含 Signature/Expires，属敏感，**绝不落 INFO 日志**（本类仅 DEBUG 记录对象 key 计数，
 * 不记录 URL 本身）。OSS 预签名为本地 HMAC 计算，不连网。
 */
@Service
public class SignedUrlService {

    private static final Logger log = LoggerFactory.getLogger(SignedUrlService.class);

    private final AliyunOssClient ossClient;
    private final MediaProperties props;

    public SignedUrlService(AliyunOssClient ossClient, MediaProperties props) {
        this.ossClient = ossClient;
        this.props = props;
    }

    /** 为单个私密桶对象签发短 TTL GET 签名 URL。 */
    public String sign(String objectKey) {
        long ttl = props.getSignedUrl().getTtlSeconds();
        Date expiration = new Date(System.currentTimeMillis() + ttl * 1000L);
        OSS oss = ossClient.buildClient();
        try {
            return oss.generatePresignedUrl(
                    ossClient.privateBucket(), stripLeadingSlash(objectKey), expiration, HttpMethod.GET)
                    .toString();
        } catch (RuntimeException e) {
            log.warn("Signed URL generation failed: {}", e.getClass().getSimpleName());
            throw AppException.mediaCredential("私密图访问凭证暂不可用");
        } finally {
            oss.shutdown();
        }
    }

    /** 批量签名（健康历史多图）。复用单个 OSS 客户端，减少重复构建。 */
    public List<String> signAll(List<String> objectKeys) {
        long ttl = props.getSignedUrl().getTtlSeconds();
        Date expiration = new Date(System.currentTimeMillis() + ttl * 1000L);
        OSS oss = ossClient.buildClient();
        try {
            List<String> urls = new ArrayList<>(objectKeys.size());
            for (String key : objectKeys) {
                urls.add(oss.generatePresignedUrl(
                        ossClient.privateBucket(), stripLeadingSlash(key), expiration, HttpMethod.GET)
                        .toString());
            }
            return urls;
        } catch (RuntimeException e) {
            log.warn("Batch signed URL generation failed: {}", e.getClass().getSimpleName());
            throw AppException.mediaCredential("私密图访问凭证暂不可用");
        } finally {
            oss.shutdown();
        }
    }

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }
}
