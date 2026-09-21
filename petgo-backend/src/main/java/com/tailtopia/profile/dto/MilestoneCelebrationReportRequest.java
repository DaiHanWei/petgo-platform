package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 庆祝回报请求（V1.3.0 Story 1.4 · FR-111 · AD-A2.3b）：客户端展示完庆祝后，
 * 把**本次实际展示所覆盖的那批** code 回传，服务端按列表置位 {@code celebrated_at}。
 *
 * <p>🔴 <b>为什么必须由客户端点名，而不是服务端 mark-all</b>：客户端从读取里程碑列表到发出本请求
 * 之间隔着几百毫秒，这期间完全可能因为被点赞、被评论而新解锁一条。若服务端执行
 * 「把该宠物所有 {@code celebrated_at IS NULL} 的都置位」，那条新解锁的会被静默盖章为已庆祝、
 * <b>永不补弹</b> —— 等于用 FR-111 造出 FR-111 本来要修的 bug。
 *
 * <p>上限 {@code 64}：一次庆祝实际覆盖的条目是「弹的那一条 + KOLEKSI 圆点带过的若干条」，
 * 量级在个位到十几；64 足够宽松，同时挡住把整张清单甩上来的请求。
 *
 * @param codes 本次庆祝已展示的里程碑 code 列表（如 {@code ["C-M3", "C-L4"]}），非空
 */
public record MilestoneCelebrationReportRequest(
        @NotEmpty(message = "codes 不能为空") @Size(max = 64) List<String> codes) {
}
