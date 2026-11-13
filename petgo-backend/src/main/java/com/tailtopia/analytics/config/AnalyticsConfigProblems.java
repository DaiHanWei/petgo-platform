package com.tailtopia.analytics.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 启动期发现的埋点配置问题登记处（analytics-pipeline Story 4.9 AC11）。不是 {@code @ConfigurationProperties} 的地方
 * （数据源装配、调度注册）在发现非法值时登记一条（<b>只含键名，不含配置值</b>），{@link AnalyticsConfigAlert} 在应用就绪时
 * 连同各属性类的 {@code invalidReason} 一起发一条 TECH 告警。
 */
@Component
public class AnalyticsConfigProblems {

    private final List<String> problems = new ArrayList<>();

    /** 登记一条问题（只含键名与处理方式）。 */
    public synchronized void report(String problem) {
        if (problem != null && !problem.isBlank() && !problems.contains(problem)) {
            problems.add(problem);
        }
    }

    /** 目前登记的全部问题（快照）。 */
    public synchronized List<String> all() {
        return List.copyOf(problems);
    }
}
