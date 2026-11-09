package com.tailtopia.admin.aiquery.web;

import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.service.AiQueryQueries;
import org.springframework.stereotype.Component;

/** 模板格式化（{@code ${@aiqFormat.clock(secs)}}）：计时器 mm:ss / h:mm:ss。 */
@Component("aiqFormat")
public class AiQueryFormat {

    /** 某一轮的已用时长（秒）：排队中从提交算，查询中从派发算。 */
    public long elapsedSecs(AiQueryMessage m) {
        return AiQueryQueries.elapsedSecs(m);
    }

    public String clock(long secs) {
        long s = Math.max(0, secs);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long r = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, r) : String.format("%02d:%02d", m, r);
    }
}
