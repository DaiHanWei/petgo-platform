package com.tailtopia.shared.media;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Date;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 薄封装（Story 2.1）：构建 {@link OSS} 客户端、对象 key 生成、E4 服务端 EXIF 兜底 URL。
 *
 * <p>仅基础设施，不含业务。凭证经 {@link MediaProperties}（env 注入）。构建 OSS 客户端是本地操作
 * （不连网），网络调用发生在具体 OSS 方法时——本类提供的 URL 拼接/key 生成均为纯函数，L0 可测。
 */
@Component
public class AliyunOssClient {

    /** OSS 图片处理：任意 transform 都会重编码并丢弃 EXIF/GPS（E4 对外分发兜底）。 */
    static final String EXIF_STRIP_PROCESS = "image/format,jpg";

    private final MediaProperties props;

    public AliyunOssClient(MediaProperties props) {
        this.props = props;
    }

    /** 构建 OSS 客户端（调用方负责 {@link OSS#shutdown()}）。endpoint+主账号 AK，用于服务端操作/签名。 */
    public OSS buildClient() {
        return new OSSClientBuilder().build(
                props.getOss().getEndpoint(),
                props.getAccessKeyId(),
                props.getAccessKeySecret());
    }

    /**
     * 生成预签名 PUT URL（客户端凭此直传 OSS，**真 key 始终只在后端**）。
     *
     * <p>用 env 注入的 AccessKey 现签，无需 STS/RAM 角色。{@code contentType} 计入签名——客户端 PUT
     * 时必须发同名 {@code Content-Type} 头，否则 SignatureDoesNotMatch。{@code publicRead=true}（公开域）
     * 时把 {@code x-oss-object-acl:public-read} 签入，客户端须同发该头，对象落桶即公开可读。
     *
     * @return 预签名上传 URL 字符串
     */
    public String presignedPutUrl(String bucket, String objectKey, String contentType,
            long ttlSeconds, boolean publicRead) {
        OSS client = buildClient();
        try {
            GeneratePresignedUrlRequest req =
                    new GeneratePresignedUrlRequest(bucket, stripLeadingSlash(objectKey), HttpMethod.PUT);
            req.setExpiration(new Date(System.currentTimeMillis() + ttlSeconds * 1000L));
            req.setContentType(contentType);
            if (publicRead) {
                req.addHeader("x-oss-object-acl", "public-read");
            }
            URL url = client.generatePresignedUrl(req);
            return url.toString();
        } finally {
            client.shutdown();
        }
    }

    public String privateBucket() {
        return props.getOss().getPrivateBucket();
    }

    public String publicBucket() {
        return props.getOss().getPublicBucket();
    }

    /**
     * 公开桶对象的对外公开 URL（CDN base + key）。仅公开桶可用；私密对象绝不给公开 URL。
     */
    public String publicUrl(String objectKey) {
        return props.getOss().getCdnBaseUrl() + "/" + stripLeadingSlash(objectKey);
    }

    /**
     * （E4 服务端 EXIF 兜底）公开桶对外图片附 {@code x-oss-process} 去元数据样式：
     * 即便客户端绕过了客户端剥离，对外分发（尤其 H5 名片）经此 URL 取回的图也已重编码、无 GPS。
     */
    public String publicExifStrippedUrl(String objectKey) {
        return publicUrl(objectKey) + "?x-oss-process=" + EXIF_STRIP_PROCESS;
    }

    /**
     * （E4 兜底）给已有公开 URL 追加 {@code x-oss-process} 去 EXIF 样式（Story 2.6 H5 名片对外图）。
     * 对外分发的头像/快乐时刻/OG 图一律经此，防改过的客户端绕过客户端剥离泄漏 GPS。
     */
    public static String exifStrippedDeliveryUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return publicUrl;
        }
        String sep = publicUrl.contains("?") ? "&" : "?";
        return publicUrl + sep + "x-oss-process=" + EXIF_STRIP_PROCESS;
    }

    /** 服务端上传字节到公开桶①（Story 2.6 OG 预渲染图）。L2 真实网络。返回对外 CDN URL。 */
    public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
        OSS client = buildClient();
        try {
            com.aliyun.oss.model.ObjectMetadata meta = new com.aliyun.oss.model.ObjectMetadata();
            meta.setContentType(contentType);
            meta.setContentLength(bytes.length);
            client.putObject(props.getOss().getPublicBucket(), stripLeadingSlash(objectKey),
                    new ByteArrayInputStream(bytes), meta);
            return publicUrl(objectKey);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 服务端上传字节到私密桶②（Story 2.5 IM→OSS 桥接用）。L2 真实网络。
     * 调用方负责字节已去 EXIF（IM 图复制场景由 {@link ImToOssArchiver} 控制）。
     */
    public void putPrivateObject(String objectKey, byte[] bytes) {
        OSS client = buildClient();
        try {
            client.putObject(props.getOss().getPrivateBucket(), stripLeadingSlash(objectKey),
                    new ByteArrayInputStream(bytes));
        } finally {
            client.shutdown();
        }
    }

    /** 删除私密桶②对象（Story 7.3 注销级联删除：分诊/健康/consult 存档图）。L2 真实网络。 */
    public void deletePrivateObject(String objectKey) {
        OSS client = buildClient();
        try {
            client.deleteObject(props.getOss().getPrivateBucket(), stripLeadingSlash(objectKey));
        } finally {
            client.shutdown();
        }
    }

    /** 删除公开桶①对象（Story 7.3：纯个人图如头像）。L2 真实网络。 */
    public void deletePublicObject(String objectKey) {
        OSS client = buildClient();
        try {
            client.deleteObject(props.getOss().getPublicBucket(), stripLeadingSlash(objectKey));
        } finally {
            client.shutdown();
        }
    }

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }
}
