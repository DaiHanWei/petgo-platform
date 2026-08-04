package com.tailtopia.shared.analytics;

import java.util.Map;

/**
 * 服务端行为分析上报口（V1.1.2 Story 6.1 · T-12）。
 *
 * <p><b>为什么后端也要埋点</b>：里程碑达成的判定全在服务端（健康记录事件、兽医问诊关闭、
 * 计数阈值、组合解锁），客户端根本看不到「这次是走哪条路径点亮的」。前端补不了这一环。
 *
 * <p><b>为什么不引第三方 SDK</b>：PostHog 的 capture 是一个单纯的 HTTP POST，用既有的
 * {@code RestClient} 十几行就够；引 SDK 会为一个事件多背一条供应链依赖 + 它自带的后台线程池，
 * 与「异步只用 {@code @Async}、不加中间件」的架构护栏也别扭。故本接口 = 抽象 + 一个 HTTP 实现。
 *
 * <p><b>三条硬约束</b>（实现方必须遵守）：
 * <ol>
 *   <li><b>绝不阻塞主流程</b>：实现异步 + 吞异常。埋点挂了不能影响里程碑落库。</li>
 *   <li><b>绝不传 PII / 健康数据</b>：{@code distinctId} 只能是
 *       {@link AnalyticsDistinctId} 出的哈希；属性只放受控枚举与布尔/数字。</li>
 *   <li><b>凭证 env 注入</b>，绝不入库、绝不进日志。</li>
 * </ol>
 */
public interface AnalyticsClient {

    /**
     * 上报一个事件。
     *
     * @param distinctId 用户标识，必须是 {@link AnalyticsDistinctId#of(long)} 的哈希值
     *                   （与前端逐字一致，同一个人在看板上才是同一个人）
     * @param event      事件名（snake_case，带可读模块前缀）
     * @param properties 属性（snake_case 键；值只放受控枚举 / 数字 / 布尔）
     */
    void capture(String distinctId, String event, Map<String, Object> properties);
}
