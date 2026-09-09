package com.tailtopia.admin.dashboard.web;

import com.tailtopia.admin.dashboard.service.DashboardMaterializer;
import com.tailtopia.admin.shared.StagOnly;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * stag 专用手动跑批入口（V1.3.0 Story 3.3 AC3）：{@code POST /admin/dashboard/materialize} 同步执行一次「找缺日 → 逐日物化」
 * 并返回 {@code {materializedDates, skipped, failedDates, tookMs}} JSON（后台内部用）。
 * <p>{@link StagOnly} = {@code @Profile("stag")}：<b>生产不注册这个 Bean</b>（路由不存在，不是 403）。超管专属。</p>
 */
@StagOnly
@RestController
public class AdminDashboardMaterializeController {

    public static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final DashboardMaterializer materializer;

    public AdminDashboardMaterializeController(DashboardMaterializer materializer) {
        this.materializer = materializer;
    }

    @PostMapping("/admin/dashboard/materialize")
    @PreAuthorize(AUTH)
    public DashboardMaterializer.Result materialize() {
        return materializer.materializeMissing();
    }
}
