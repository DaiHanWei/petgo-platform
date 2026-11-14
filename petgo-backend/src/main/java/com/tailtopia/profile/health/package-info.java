/**
 * V1.5.0 健康记录提醒的<b>纯规则</b>（架构增量 health-reminder §2 / §3）：Next date 预填、疫苗系列、驱虫间隔、发情周期、
 * 疫苗关键词匹配、覆盖组、记录显示状态。规则只有这一份：App 只调 {@code /health-records/preview} 与读列表字段，
 * 禁止在 Dart 里复制任何日期规则。所有「今天」= {@code LocalDate.now(ScheduleWindow.WIB)}。
 */
package com.tailtopia.profile.health;
