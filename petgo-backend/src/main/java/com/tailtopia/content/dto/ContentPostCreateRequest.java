package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.ImageSize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 发布内容请求（{@code POST /api/v1/content-posts}）。author 取自 JWT，不在 DTO。
 *
 * @param type      内容类型（DAILY/GROWTH_MOMENT/KNOWLEDGE），必填
 * @param petId     成长日历绑定的宠物 id（仅 GROWTH_MOMENT 需要，且须属当前用户）
 * @param text      正文 ≤1000
 * @param imageUrls 公开桶 CDN URL 列表 ≤9（经 Story 2.1 直传得到）
 * @param eventDate 成长日历事件日期（F9）：仅 GROWTH_MOMENT 有值、不可未来；日常/科普忽略（强制 null）
 */
public record ContentPostCreateRequest(
        @NotNull(message = "内容类型不能为空") ContentType type,
        Long petId,
        @Size(max = 1000, message = "正文不能超过 1000 字") String text,
        @Size(max = 9, message = "最多 9 张图片") List<@Size(max = 1024) String> imageUrls,
        LocalDate eventDate,
        /**
         * 可见范围（Story 4.1 字段 / Story 4.2 发布页同步开关）。省略 = {@code PUBLIC}
         * —— 老客户端与后台补发不传该字段时保持既有「公开」行为。
         *
         * <p>⚠️ 只在**创建时**接受：发布后不可更改（FR-83 AC7 —— 让内容不再被他人看到的
         * 唯一途径是删除该条内容）。因此没有、也不要加任何「转为私密」的更新端点。
         */
        ContentVisibility visibility,
        /**
         * 图片原始宽高（V1.1.6 Story 3.1 · AD-5），与 {@link #imageUrls()} <b>同序等长</b>。
         *
         * <p>客户端在判容差区间时尺寸已在手，顺手上报即可，省掉服务端一次网络往返。
         *
         * <p>🛡 <b>长度对不上就整组作废</b>（见 {@code ContentService} 里的校验）——
         * 错位的后果是图文不符，比没有尺寸严重得多。省略 / 为 null 时全部走服务端兜底测量。
         */
        @Size(max = 9, message = "最多 9 张图片") List<ImageSize> imageSizes) {

    /** 兼容无事件日期的发布（日常/科普 / 后台补发）：eventDate 省为 null，可见范围默认公开。 */
    public ContentPostCreateRequest(ContentType type, Long petId, String text,
            List<String> imageUrls) {
        this(type, petId, text, imageUrls, null, null, null);
    }

    /** 兼容不带可见范围的调用（老客户端 / 既有测试）：默认公开。 */
    public ContentPostCreateRequest(ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate) {
        this(type, petId, text, imageUrls, eventDate, null, null);
    }

    /** 兼容不带图片尺寸的调用（老客户端 / 既有测试 / 后台种子发布）：一律走服务端兜底测量。 */
    public ContentPostCreateRequest(ContentType type, Long petId, String text,
            List<String> imageUrls, LocalDate eventDate, ContentVisibility visibility) {
        this(type, petId, text, imageUrls, eventDate, visibility, null);
    }

    /** 省略即公开（NFR-6：私密只由用户主动关开关产生）。 */
    public ContentVisibility visibilityOrPublic() {
        return visibility == null ? ContentVisibility.PUBLIC : visibility;
    }
}
