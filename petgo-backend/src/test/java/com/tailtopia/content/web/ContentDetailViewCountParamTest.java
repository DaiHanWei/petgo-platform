package com.tailtopia.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.dto.ContentDetailResponse;
import com.tailtopia.content.service.CommentQueryService;
import com.tailtopia.content.service.ContentDetailService;
import com.tailtopia.content.service.ContentViewStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：详情接口 {@code countView}（V1.3.4 follow · Story 3.4 复审决定）—— 通知中心跳转前的「帖子已删」探测不计浏览，
 * 正常打开照常计；可见范围判定同一个 {@code getDetail}。
 */
class ContentDetailViewCountParamTest {

    private ContentDetailService detailService;
    private ContentViewStatsService viewStats;
    private ContentDetailController controller;

    @BeforeEach
    void setUp() {
        detailService = mock(ContentDetailService.class);
        viewStats = mock(ContentViewStatsService.class);
        controller = new ContentDetailController(detailService, mock(CommentQueryService.class), viewStats);
        ContentDetailResponse resp = mock(ContentDetailResponse.class);
        when(resp.isAuthor()).thenReturn(false);
        when(detailService.getDetail(anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(resp);
    }

    @Test
    void defaultCountsView() {
        controller.detail(null, 55L, "anon", true, "video");
        verify(viewStats).recordView(55L, null, "anon");
    }

    @Test
    void probeDoesNotCountViewButStillRunsVisibilityChecks() {
        controller.detail(null, 55L, "anon", false, "video");
        verify(detailService).getDetail(55L, null, true);
        verify(viewStats, never()).recordView(anyLong(), any(), any());
    }
}
