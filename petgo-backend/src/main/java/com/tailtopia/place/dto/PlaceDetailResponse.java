package com.tailtopia.place.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import java.util.List;

/**
 * 场所详情投影（V1.3.0 batch-b1 Story 1.5 · AC1/AC6）。Jackson NON_NULL。
 *
 * <h2>🔴 反向验收：这里**没有**的字段（FR-112.6 / AC6）</h2>
 * <ul>
 *   <li>**没有** `favorited` / 收藏相关字段；</li>
 *   <li>**没有** `rating` / 评分打星；</li>
 *   <li>**没有** 营业时间 / 电话 / 商户资料类字段；</li>
 *   <li>**没有** 打卡相关字段（⑧ 打卡在批次 B2）；</li>
 *   <li>**没有** 任何「可编辑」标记 —— 用户不可编辑场所（2026-09-15 拍板）。</li>
 * </ul>
 * ⚠️ 这不是「还没做」，是**明确不做**。往这里加任何一条之前先回 PRD ⑥ / 决策日志改口径。
 * {@code PlaceDetailResponseContractTest} 把这份"没有"钉成了可证伪的断言。
 *
 * @param token             不可枚举对外标识（**响应里没有自增 id**，NFR-1）
 * @param name              场所名
 * @param type              类型
 * @param tags              宠物友好标签（全量，客户端自己决定显示几个）
 * @param photos            照片（Story 1.9 起**每张带上传者**，AC2）。
 *                          ⚠️ 这取代了 1.5 的 {@code photoUrls}（一个字符串数组）——
 *                          数组装不下"这张是谁传的"。URL 仍然带着去 EXIF 的 `x-oss-process`（E4）
 * @param addressText       文字地址。**纯展示 + 一键复制**，平台不做地理编码、不校验它与坐标一致（AD-1 Rule 5）
 * @param description       描述（可空）
 * @param latitude          纬度 —— 详情页的**定位小地图**要用（AD-3 Rule 3 允许的两处之一）
 * @param longitude         经度
 * @param distanceMeters    距离（米）。只有携带坐标时才有值；否则省略（不是 0）
 * @param markedBy          标记人（昵称 / 头像 / 注销态）。本 story 点它走**现有迷你主页卡**，
 *                          Epic 2 退役迷你卡时统一改直跳公开主页
 * @param commentCount      评论数（Story 1.7 接真值前恒 0）
 * @param recommendCount    推荐数 👍（Story 1.8 接真值前恒 0）
 * @param notRecommendCount 不推荐数 👎（同上）
 */
public record PlaceDetailResponse(
        String token,
        String name,
        PlaceType type,
        List<PlaceTag> tags,
        List<PlacePhotoView> photos,
        String addressText,
        String description,
        double latitude,
        double longitude,
        Integer distanceMeters,
        AuthorView markedBy,
        long commentCount,
        long recommendCount,
        long notRecommendCount) {

    /**
     * 详情大图宽度（物理像素）。
     *
     * <p>比列表缩略图（320）大得多：详情是横滑大图、还会点进灯箱（Story 1.6）。
     * 1080 覆盖到 3× 屏的整屏宽。
     *
     * <p>🔴 同时承担 **E4 的服务端 EXIF 兜底**（`format,jpg` 重编码即丢弃 GPS）——
     * 客户端剥离是主路径，但改过的客户端能绕过它，而场所照片进的是**公开桶**。
     *
     * <p>⚠️ Story 1.9 起由 {@link PlacePhotoView#of} 施加（每张照片各自带 URL），
     * 传的仍是这个宽度。
     */
    public static final int DETAIL_PHOTO_WIDTH_PX = 1080;

    public static PlaceDetailResponse of(Place p, AuthorView markedBy,
            List<PlacePhotoView> photos, Integer distanceMeters,
            long commentCount, long recommendCount, long notRecommendCount) {
        return new PlaceDetailResponse(
                p.getPublicToken(),
                p.getName(),
                p.getType(),
                p.getTags(),
                photos,
                p.getAddressText(),
                p.getDescription(),
                p.getLatitude(),
                p.getLongitude(),
                distanceMeters,
                markedBy,
                commentCount,
                recommendCount,
                notRecommendCount);
    }

}
