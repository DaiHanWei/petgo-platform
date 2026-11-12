package com.tailtopia.shared.analytics;

import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * 服务端行为分析上报口（V1.1.2 Story 6.1 · T-12；analytics-pipeline Story 1.9 改为写入自建埋点接收队列）。
 *
 * <p><b>为什么后端也要埋点</b>：里程碑达成、支付成功等判定全在服务端（健康记录事件、兽医问诊关闭、
 * 计数阈值、组合解锁、支付回调），客户端根本看不到。前端补不了这一环。
 *
 * <p><b>去向</b>：唯一实现 {@link SelfHostedAnalyticsClient} 把事件写入自建埋点接收队列（Story 1.4 的
 * {@code IngestQueue}），由写库线程批量落到埋点库原始层。业务模块只经本接口发事件，不直接碰队列。
 *
 * <p><b>三条硬约束</b>（实现方必须遵守）：
 * <ol>
 *   <li><b>绝不阻塞主流程</b>：入队是内存非阻塞操作，任何失败只丢埋点、吞异常，不影响业务。</li>
 *   <li><b>绝不传 PII / 健康数据</b>：属性只放受控枚举与布尔/数字（{@link AnalyticsEventGuard} 守着）。</li>
 *   <li><b>凭证 env 注入</b>，绝不入库、绝不进日志。</li>
 * </ol>
 */
public interface AnalyticsClient {

    /**
     * 上报一个登录用户的服务端事件（{@code source=SERVER}）。
     *
     * @param userId     内部用户 id（只在埋点库内部用，不对外暴露）
     * @param event      事件名（snake_case，带可读模块前缀）
     * @param properties 属性（snake_case 键；值只放受控枚举 / 数字 / 布尔）
     */
    void captureForUser(long userId, String event, Map<String, Object> properties);

    /**
     * 上报一个 H5 匿名访客事件（{@code source=H5}）。
     *
     * @param visitorId  {@link AnonymousVisitorId} 的访客 id（32 位小写十六进制）；为空直接丢弃
     * @param event      事件名
     * @param properties 属性
     */
    void captureForVisitor(String visitorId, String event, Map<String, Object> properties);

    /**
     * 旧入口（PostHog 时代）。
     *
     * <p>🔴 <b>不路由到任何实现</b>：{@code distinctId} 是 {@link AnalyticsDistinctId} 的哈希，反推不出 {@code user_id}，
     * 无法正确入库。调用只打一条 WARN（只含事件名）后丢弃。改用 {@link #captureForUser} / {@link #captureForVisitor}。
     *
     * @deprecated analytics-pipeline Story 1.9 起废弃，待移除
     */
    @Deprecated(forRemoval = true)
    default void capture(String distinctId, String event, Map<String, Object> properties) {
        LoggerFactory.getLogger(AnalyticsClient.class)
                .warn("deprecated AnalyticsClient#capture called, event dropped: event={}", event);
    }
}
