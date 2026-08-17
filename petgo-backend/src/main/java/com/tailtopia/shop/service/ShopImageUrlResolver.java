package com.tailtopia.shop.service;

import com.tailtopia.shared.media.MediaProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把商品图的 OSS objectKey 换成<b>可直接显示的公开桶 CDN URL</b>（Story 1.6）。
 *
 * <p>🔴 <b>不是签名 URL，也不需要签名机制。</b>本仓库此前<b>没有任何读侧签名 URL 能力</b>
 * （{@code support} 模块正因如此明确「附件只显数量，详情不渲染缩略图」）。
 * 商品目录图属<b>公开信息、非 PII</b>，与内容帖图片同源——走公开桶 + CDN 全 URL，
 * 拼法与 {@code PresignedUploadService} 的 {@code publicUrl = cdnBase + "/" + objectKey} 一致。
 *
 * <p>⚠️ 因此：<b>运营上传商品图必须落公开桶</b>（{@code MediaScope.PUBLIC}）。
 * 落到私有桶的 key 在这里也会拼出 URL，但公网取不到——那是上传侧的问题，不在本类职责内。
 *
 * <p>🔴 <b>CDN base 未配置时一律返回 {@code null}</b>，让前端优雅降级到占位图；
 * 绝不返回半截 URL（如 {@code "/shop/xxx.jpg"}）——那会让客户端拿相对路径去打自己的域名，
 * 表现为一堆 404 而不是「没有图」，排查成本高得多。
 *
 * <p>🔴 派生逻辑<b>只存在于本类</b>：DTO 装配处、后台预览、将来的详情页都走它，
 * 各写一遍必然漂移（少个斜杠、多个前缀），而两边各自的测试都会是绿的。
 */
@Component
public class ShopImageUrlResolver {

    private final MediaProperties props;

    public ShopImageUrlResolver(MediaProperties props) {
        this.props = props;
    }

    /**
     * @param objectKey OSS objectKey（可空）
     * @return 公开桶 CDN 全 URL；objectKey 为空或 CDN base 未配置时返回 {@code null}
     */
    public String publicUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String cdnBase = props.getOss().getCdnBaseUrl();
        if (cdnBase == null || cdnBase.isBlank()) {
            return null;
        }
        return trimTrailingSlash(cdnBase) + "/" + stripLeadingSlash(objectKey);
    }

    /** 批量版本（详情页图集）。空列表进、空列表出；单个不可解析的元素被剔除而非留 null。 */
    public List<String> publicUrls(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return List.of();
        }
        return objectKeys.stream().map(this::publicUrl).filter(u -> u != null).toList();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
