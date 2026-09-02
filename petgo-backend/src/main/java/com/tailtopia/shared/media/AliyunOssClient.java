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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AliyunOssClient.class);

    /** OSS 图片处理：任意 transform 都会重编码并丢弃 EXIF/GPS（E4 对外分发兜底）。 */
    static final String EXIF_STRIP_PROCESS = "image/format,jpg";

    private final MediaProperties props;

    public AliyunOssClient(MediaProperties props) {
        this.props = props;
    }

    /** 构建 OSS 客户端（调用方负责 {@link OSS#shutdown()}）。endpoint+主账号 AK，用于服务端操作/签名。 */
    /** 凭证是否已配（env 注入）。空 = 本地/测试环境。 */
    public boolean hasCredentials() {
        return props.getAccessKeyId() != null && !props.getAccessKeyId().isBlank()
                && props.getAccessKeySecret() != null && !props.getAccessKeySecret().isBlank();
    }

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

    /**
     * 服务端上传字节到公开桶①。L2 真实网络。返回对外 CDN URL。
     *
     * <p>🔴 <b>一律带对象级 {@code x-oss-object-acl: public-read}</b>。公开桶的
     * <b>桶级并非公开读</b>（BPA 关闭 + 桶 ACL 私有），对象不带这个标记就是
     * 「上传成功、公网直读 403」—— 而上传链路本身一声不响。
     *
     * <h2>同一个根因，两条路各撞了一次（2026-09-02）</h2>
     * <ul>
     *   <li><b>bug 472 的补漏</b>：472 只改了素材那条链路（另立 {@code putPublicObjectWithAcl}），
     *       标签图标 / 兽医头像 / OG 图三处仍走本方法 ⇒ 后台<b>标签胶囊全部裂图</b>（实机截图）。</li>
     *   <li><b>stag 电商测试 D-2</b>：93 个商品里 10 张图公网 403、App 内空占位。
     *       现象是<b>同一前缀下有的 200、有的 403</b> —— 因为预签名直传
     *       （{@link #presignedPutUrl}）把 ACL 签进了头，服务端上传却没带。</li>
     * </ul>
     *
     * <p>🔴 <b>合并成一个原语</b>而不是逐个改调用点：当时四条服务端上传线里
     * <b>三条</b>走的是不带 ACL 的这条，只有 Lark 转存是对的。
     * 公开桶里<b>不存在</b>「上传后不该被公开读」的对象 —— 它就是为公开分发存在的。
     * 留着一个「看起来能用、但产出的对象必然 403」的重载，下一个新增上传线还会挑中它，
     * 而且同样不会有任何报错。ACL 焊进本体后，这类事故对<b>未来所有调用方</b>都不可能再发生。
     */
    public String putPublicObject(String objectKey, byte[] bytes, String contentType) {
        // 🔴 无凭证时走打桩：直接返回 URL，不打网络（Story 11.5）。
        //
        // 沿用 Gemini 的 mode=stub 先例（`petgo.ai.gemini.mode`）—— 目的不是"让测试变绿"，
        // 而是让**依赖上传的业务路径在无凭证环境仍可验证**：
        // Story 11.5 把"建标签"改成了必须先上传图标成功，于是这条原本纯 L1 的路径
        // 在没有凭证的本机变成了 L2 —— 连"重复标签码要被拦下"这种与上传无关的规则都验不了。
        //
        // ⚠️ 判据是**凭证是否配了**，不是某个开关：生产必然配了凭证 ⇒ 必然走真实上传，
        // 不存在"忘了关 stub 导致线上图没真的传上去"这种事故。
        if (!hasCredentials()) {
            log.warn("OSS 未配凭证，putPublicObject 走打桩（仅本地/测试）key={}", objectKey);
            return publicUrl(objectKey);
        }
        OSS client = buildClient();
        try {
            client.putObject(props.getOss().getPublicBucket(), stripLeadingSlash(objectKey),
                    new ByteArrayInputStream(bytes), publicObjectMetadata(contentType, bytes.length));
            return publicUrl(objectKey);
        } finally {
            client.shutdown();
        }
    }

    /** 对象级公开读的头名。公开桶里每个对象都必须带它，见 {@link #putPublicObject}。 */
    static final String PUBLIC_READ_HEADER = "x-oss-object-acl";

    static final String PUBLIC_READ_VALUE = "public-read";

    /**
     * 公开桶对象的元数据。抽出来是为了让「ACL 到底有没有带」可被 L0 直接断言 ——
     * 这个标记漏掉时<b>上传照样成功</b>，只有公网取图才 403，没有任何一处会报错。
     */
    static com.aliyun.oss.model.ObjectMetadata publicObjectMetadata(String contentType, long length) {
        com.aliyun.oss.model.ObjectMetadata meta = new com.aliyun.oss.model.ObjectMetadata();
        meta.setContentType(contentType);
        meta.setContentLength(length);
        meta.setHeader(PUBLIC_READ_HEADER, PUBLIC_READ_VALUE);
        return meta;
    }

    /**
     * @deprecated 与 {@link #putPublicObject} 已无差别 —— 后者现在一律带 ACL。
     *     保留只为不打断既有调用方（Lark 转存、以及 bug 472 那一批）与它们的用例；
     *     新代码直接用 {@link #putPublicObject}。
     * @return 对外 CDN URL
     */
    @Deprecated
    public String putPublicObjectWithAcl(String objectKey, byte[] bytes, String contentType) {
        return putPublicObject(objectKey, bytes, contentType);
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

    // ⚠️ 删除原语已整体移除（2026-08-19 决策 F21）：OSS 对象任何情况不物理删除——快照（id_cards 等）
    // 长期引用对象，删除即死链且不可恢复。勿再新增 deleteObject 封装；确需删除先回 F21 重新拍板。

    private static String stripLeadingSlash(String key) {
        return key.startsWith("/") ? key.substring(1) : key;
    }
}
