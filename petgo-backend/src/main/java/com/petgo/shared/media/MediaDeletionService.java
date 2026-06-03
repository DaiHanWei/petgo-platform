package com.petgo.shared.media;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 注销级联删除 OSS 对象（Story 7.3）。逐个 best-effort 删除（单个失败不阻断其余，由作业重试兜底）。
 * 日志只记计数，不落对象 key/URL（key 不含 PII 但 URL 可能含签名/路径，统一不记）。
 */
@Service
public class MediaDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MediaDeletionService.class);

    private final AliyunOssClient ossClient;
    private final MediaProperties props;

    public MediaDeletionService(AliyunOssClient ossClient, MediaProperties props) {
        this.ossClient = ossClient;
        this.props = props;
    }

    /** 删私密桶②对象 key 列表。 */
    public void deletePrivateKeys(List<String> keys) {
        if (keys == null) {
            return;
        }
        int ok = 0;
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                ossClient.deletePrivateObject(key);
                ok++;
            } catch (RuntimeException e) {
                log.warn("私密图删除失败 cause={}", e.getClass().getSimpleName());
            }
        }
        log.info("注销私密图删除完成 count={}", ok);
    }

    /** 删公开桶①个人图（按 URL 解析回对象 key）。 */
    public void deletePublicByUrls(List<String> urls) {
        if (urls == null) {
            return;
        }
        int ok = 0;
        for (String url : urls) {
            String key = toPublicKey(url);
            if (key == null) {
                continue;
            }
            try {
                ossClient.deletePublicObject(key);
                ok++;
            } catch (RuntimeException e) {
                log.warn("公开个人图删除失败 cause={}", e.getClass().getSimpleName());
            }
        }
        log.info("注销公开个人图删除完成 count={}", ok);
    }

    /** 公开 URL → 对象 key：剥 CDN base 或 OSS 公网域名前缀（best-effort）。 */
    String toPublicKey(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String cdn = props.getOss().getCdnBaseUrl();
        if (cdn != null && !cdn.isBlank() && url.startsWith(cdn)) {
            return stripLeadingSlash(url.substring(cdn.length()));
        }
        // 兜底：取路径部分（去协议+域名）。
        int schemeIdx = url.indexOf("://");
        if (schemeIdx >= 0) {
            int pathIdx = url.indexOf('/', schemeIdx + 3);
            if (pathIdx >= 0) {
                return stripLeadingSlash(url.substring(pathIdx));
            }
        }
        return null;
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
