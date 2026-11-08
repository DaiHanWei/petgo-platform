package com.tailtopia.admin.shared;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 后台列表通用分页器的视图模型（V1.4.2 优化项：显示总页数 + 页码按 10 页一批 + 跳页）。
 *
 * <p>配套模板片段 {@code admin/fragments/pager :: pager(p=..., tpl=..., target=...)}。
 * <b>页码一律 0 基</b>（与各列表控制器的 {@code page} 参数一致），只在显示时 +1。
 *
 * <p>两种形态：
 * <ul>
 *   <li><b>已知总数</b>（{@link #of}）：共 N 条 · 第 x / y 页 · 一批 10 个页码（« » 换批）· 跳页框；</li>
 *   <li><b>总数未知</b>（{@link #unknown}）：某些筛选口径在应用层过滤（如内容列表的物种筛选、按点赞时间），
 *       数据库给不出准确总数 —— 只给「第 x 页」+ 上一页 / 下一页，<b>不编一个错的总页数</b>。</li>
 * </ul>
 *
 * @param page       当前页（0 基，已夹到合法范围）
 * @param totalPages 总页数；未知为 -1
 * @param total      总条数；未知为 -1
 * @param hasNext    是否有下一页（总数未知时由调用方给出）
 */
public record AdminPager(int page, int totalPages, long total, boolean hasNext) {

    /** 一批显示多少个页码。 */
    public static final int BATCH = 10;

    /** 已知总数。{@code size} ≤ 0 视为 1；空表时总页数记 1（「第 1 / 1 页 · 共 0 条」）。 */
    public static AdminPager of(int page, int size, long total) {
        int s = Math.max(1, size);
        long t = Math.max(0, total);
        int pages = (int) Math.max(1, (t + s - 1) / s);
        int p = Math.min(Math.max(page, 0), pages - 1);
        return new AdminPager(p, pages, t, p < pages - 1);
    }

    /** 由 Spring Data 的 Page 构造。 */
    public static AdminPager of(org.springframework.data.domain.Page<?> found) {
        return of(found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /** 总数未知：只能上一页 / 下一页。 */
    public static AdminPager unknown(int page, boolean hasNext) {
        return new AdminPager(Math.max(page, 0), -1, -1, hasNext);
    }

    public boolean known() {
        return totalPages >= 0;
    }

    public boolean hasPrev() {
        return page > 0;
    }

    /** 当前批的第一个页码（0 基）。 */
    public int batchStart() {
        return (page / BATCH) * BATCH;
    }

    /** 当前批的页码（0 基）；总数未知时为空。 */
    public List<Integer> batch() {
        if (!known()) {
            return List.of();
        }
        int start = batchStart();
        int end = Math.min(start + BATCH, totalPages);
        return IntStream.range(start, end).boxed().toList();
    }

    public boolean hasPrevBatch() {
        return known() && batchStart() > 0;
    }

    public boolean hasNextBatch() {
        return known() && batchStart() + BATCH < totalPages;
    }

    /** 上一批的最后一页（点 « 落到它，正好与当前批相邻）。 */
    public int prevBatchPage() {
        return Math.max(0, batchStart() - 1);
    }

    /** 下一批的第一页。 */
    public int nextBatchPage() {
        return Math.min(totalPages - 1, batchStart() + BATCH);
    }

    /** 是否值得显示分页条：已知总数且多于 1 页，或总数未知但能翻。 */
    public boolean visible() {
        return known() ? totalPages > 1 : (hasPrev() || hasNext);
    }
}
