package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;

/**
 * 「这条内容现在还能对外展示吗」—— <b>唯一判定</b>（V1.1.6 Story 11.1 抽出）。
 *
 * <h2>🔴 为什么必须只有一份</h2>
 * 顶置坑位有两个读它的地方：<b>App 的 Feed</b>（决定坑位里到底渲染什么）与
 * <b>后台的顶置列表</b>（决定要不要标注「内容失效未生效」）。
 * 两处各写一遍的表现最难查 —— <b>后台显示「生效中」，而 App 上那个坑位是空的</b>，
 * 运营会以为是缓存或故障，日志里什么都没有。
 *
 * <p>本类是从 {@code FeedService} 里那段私有 {@code isDisplayable} 原样提出来的，
 * <b>判定口径一字未改</b>（不可展示 = 已软删 / 非 PUBLISHED / 非 PUBLIC）。
 *
 * <p>⚠️ <b>注意它不含「作者被封号」这一条。</b>Story 11.1 的 AC5 原文写的是
 * 「未被下架 / 未被删除 / 作者账号未被封禁」，但 Feed 侧今天**并不**按作者封号过滤内容。
 * 若只在后台加这一条，就会造出反方向的谎：<b>后台说失效、App 上照样展示</b>。
 * 因此本类严格跟随 Feed 的现有口径；「Feed 是否也该排除封号作者的内容」是另一个问题，
 * 已在 Story 11.1 的完成记录里留档，待产品确认后**两处一起改**。
 *
 * <p>举报预处置（{@code report_hidden_at}）无需单列条件：它会把状态翻成
 * {@code UNDER_REVIEW}，已被「非 PUBLISHED」覆盖。
 */
public final class ContentDisplayability {

    private ContentDisplayability() {
    }

    /** 对外可展示（Feed 坑位、顶置列表同用这一条）。 */
    public static boolean isDisplayable(ContentPost post) {
        return post != null
                && post.getDeletedAt() == null
                && post.getStatus() == PostStatus.PUBLISHED
                && post.getVisibility() == ContentVisibility.PUBLIC;
    }
}
