package com.tailtopia.place.dto;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.shared.media.AliyunOssClient;
import java.util.List;

/**
 * 场所列表项投影（V1.3.0 batch-b1 Story 1.1 · AC2/AC3）。Jackson NON_NULL。
 *
 * <p>🔴 <b>不含自增 id</b>（NFR-1 / AD-1 Rule 3）：对外标识只有 {@code token}。
 * 契约由 {@code PlaceListResponseContractTest} 钉死（CROSS-STORY C5）。
 *
 * @param token              不可枚举对外标识
 * @param name               场所名
 * @param type               类型（7 类，UPPER_SNAKE）
 * @param tags               宠物友好标签全量下发；**≤2 个 +N 的截断归客户端**
 *                           —— 服务端截断的话，详情页与列表页就会显示两套标签集
 * @param firstPhotoUrl      首图（公开桶 CDN URL，**已附去 EXIF + 缩放的 `x-oss-process`**）；
 *                           无照片时省略。见 {@link #LIST_THUMB_WIDTH_PX}
 * @param photoCount         照片数
 * @param distanceMeters     距离（米）。🔴 **只有携带坐标的「按距离」分支才有值**；
 *                           「按最新」分支恒为 null（NON_NULL 省略），客户端据此隐藏距离位。
 *                           <b>不要在按最新分支里填 0</b> —— 0 米是「就在脚下」，不是「不知道」。
 * @param commentCount       评论数
 * @param recommendCount     推荐数（👍）
 * @param notRecommendCount  不推荐数（👎）
 *
 *        <p>⚠️ 后三个计数在 Story 1.1 <b>恒为 0</b>：场所评论（{@code place_comments}，AD-8）
 *        与 Redis 计数器（AD-9）分别由 Story 1.7 / 1.8 交付。字段先在契约里定下来，
 *        免得客户端两次改 DTO —— <b>这不是前向依赖</b>，1.1 自身可独立验收。
 */
public record PlaceListItemResponse(
        String token,
        String name,
        PlaceType type,
        List<PlaceTag> tags,
        String firstPhotoUrl,
        int photoCount,
        Integer distanceMeters,
        long commentCount,
        long recommendCount,
        long notRecommendCount) {

    /**
     * 列表缩略图宽度（物理像素）。
     *
     * <p>列表项缩略图 72pt，3× 屏 = 216px → 320 留足余量，比原图小一到两个数量级。
     *
     * <p>🔴 这个数同时承担 **E4 的服务端 EXIF 兜底**：URL 上那条 `x-oss-process` 里的
     * `format,jpg` 会重编码、丢弃 EXIF/GPS。客户端剥离是主路径，但改过的客户端能绕过它 ——
     * 所以对外分发的公开桶图一律经此（见 {@code AliyunOssClient.exifStrippedThumbUrl} 的说明）。
     */
    public static final int LIST_THUMB_WIDTH_PX = 320;

    /**
     * 「按最新」分支的工厂（无距离）。
     *
     * <p>⚠️ 计数三项由调用方传入 —— Story 1.7/1.8 接真实计数时改<b>服务层的批量聚合</b>，
     * 不要在这里偷偷补一次查询（那正是 N+1 的来源，AD-6 禁逐条查）。
     */
    public static PlaceListItemResponse of(Place p, long commentCount, long recommendCount,
            long notRecommendCount) {
        return of(p, null, commentCount, recommendCount, notRecommendCount);
    }

    /** 规范工厂。{@code distanceMeters} 为 null 即「按最新」分支。 */
    public static PlaceListItemResponse of(Place p, Integer distanceMeters, long commentCount,
            long recommendCount, long notRecommendCount) {
        return new PlaceListItemResponse(
                p.getPublicToken(),
                p.getName(),
                p.getType(),
                p.getTags(),
                // E4：对外分发的公开桶图一律经服务端去 EXIF；顺带取列表尺寸的缩略图。
                AliyunOssClient.exifStrippedThumbUrl(p.firstPhotoUrl(), LIST_THUMB_WIDTH_PX),
                p.photoCount(),
                distanceMeters,
                commentCount,
                recommendCount,
                notRecommendCount);
    }
}
