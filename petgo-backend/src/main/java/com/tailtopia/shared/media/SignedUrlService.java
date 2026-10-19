package com.tailtopia.shared.media;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.tailtopia.shared.error.AppException;
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
        if (!ossClient.hasCredentials()) {
            return stub(objectKey);
        }
        long ttl = props.getSignedUrl().getTtlSeconds();
        Date expiration = new Date(System.currentTimeMillis() + ttl * 1000L);
        // 🔴 buildClient 也要在 try 里：凭证缺失时 SDK 在这一步就抛（InvalidCredentialsException），
        //    放在外面会绕过下面的 mediaCredential 转换，调用方的「签名失败降级」接不住它 → 500
        //    （2026-09-18 场所表对齐 L1 抓到：后台场所抽屉在无凭证环境整页 500）。
        OSS oss = null;
        try {
            oss = ossClient.buildClient();
            return oss.generatePresignedUrl(
                    ossClient.privateBucket(), stripLeadingSlash(objectKey), expiration, HttpMethod.GET)
                    .toString();
        } catch (RuntimeException e) {
            log.warn("Signed URL generation failed: {}", e.getClass().getSimpleName());
            throw AppException.mediaCredential("私密图访问凭证暂不可用");
        } finally {
            if (oss != null) {
                oss.shutdown();
            }
        }
    }

    /** 批量签名（健康历史多图）。复用单个 OSS 客户端，减少重复构建。 */
    public List<String> signAll(List<String> objectKeys) {
        if (!ossClient.hasCredentials()) {
            return objectKeys.stream().map(this::stub).toList();
        }
        long ttl = props.getSignedUrl().getTtlSeconds();
        Date expiration = new Date(System.currentTimeMillis() + ttl * 1000L);
        OSS oss = null; // 同 sign()：buildClient 也必须在 try 里
        try {
            oss = ossClient.buildClient();
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
            if (oss != null) {
                oss.shutdown();
            }
        }
    }

    /**
     * 🔴 无凭证时打桩（与 {@link AliyunOssClient#putPublicObject} 的 Story 11.5 同一判据）：不签名，返回一个
     * 不带签名的占位 URL。判据是**凭证是否配了**而不是开关 —— 生产必然配了凭证 ⇒ 必然走真实签名。
     * 不打桩时 {@code buildClient()} 在 try 外直接抛 InvalidCredentialsException，本地 / CI 上所有带私密图的
     * 后台抽屉（问诊异常处理图、场所照片……）一律 500，连与图片无关的规则都验不了。
     */
    private String stub(String objectKey) {
        log.warn("OSS 未配凭证，签名 URL 走打桩（仅本地/测试）");
        return ossClient.publicUrl(objectKey);
    }

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }
}
