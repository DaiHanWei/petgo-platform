#!/usr/bin/env python3
"""每日运营体检：拉 PostHog → 对阈值 → 生成「诊断 + 候选动作」报告，呈人拍板。

这是《TailTopia 留存运营作战手册》第五章「通向 AI agent 自动化」的**第 2 步**：

    第 1 步  先人工按 SOP 跑 2 周，把每个判断阈值跑准
    第 2 步  把「每日体检」自动化 ←── 本脚本
             数据监控指标清单 → 自动拉数 → 对比阈值 → 生成「诊断 + 候选动作」呈你拍板
    第 3 步  把可逆的、低风险的动作交给 agent 执行

所以本脚本**只读、只诊断、不执行任何动作**。它不发推送、不改投放、不写库。
候选动作是给人看的清单，"你拍板，agent 干活"——顺序反了，agent 只会自动化地犯错。

用法
----
    export POSTHOG_PERSONAL_API_KEY=phx_...        # Personal API key（读权限即可），绝不写进文件
    export POSTHOG_PROJECT_ID=211847               # 可选，默认 211847
    export POSTHOG_HOST=https://eu.posthog.com     # 可选，默认 EU

    python3 scripts/ops/daily-health-check.py                    # 体检昨天（WIB）
    python3 scripts/ops/daily-health-check.py --date 2026-08-20  # 体检指定日
    python3 scripts/ops/daily-health-check.py --dry-run          # 只打印 HogQL，不联网
    python3 scripts/ops/daily-health-check.py --out report.md    # 落盘

🔴 两个口径上的硬约束（不遵守，出来的数全是错的）
----------------------------------------------
1. **必须排除 v1.1.0**。它是断链包：473 个新用户 D1 只有 2.3%，占总量 38%，
   把整体数字全部拉平。诊断报告原话：「任何不分版本看的数字都是错的。」
2. **必须排除 -stag 内测包**。stag 出包带 `-stag` 版本后缀正是为了让埋点能把
   内测数据和生产数据分开，混着看等于白加。
   两条都在 thresholds.json 的 data_hygiene 里，改那里，别改这里。

⚠️ PostHog 拿不到的三块，本脚本**不猜**，只在报告里标「需后台取数」并给出 SQL：
   - 在线兽医数（后端 Redis ZSET vet:online，不落埋点）
   - 运营种子内容发布量（走 /admin/seed-post，不经 App 埋点）
   - 召回 push 的送达/点击/建档（后端 lifecycle_push_marks + notifications）
   传 --db-url 可让脚本顺带把第三块查出来（只读 SELECT）。
"""

import argparse
import json
import os
import statistics
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone

# 印尼西部时间（WIB，无夏令时）。PostHog 项目时区就是 Asia/Jakarta，
# 运营说的「昨天」也是这个「昨天」——用 UTC 算会整体错开 7 小时。
WIB = timezone(timedelta(hours=7))

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_THRESHOLDS = os.path.join(HERE, "thresholds.json")

def tv(block, key):
    """取阈值的 value。thresholds.json 每条阈值都是 {value, source, confidence} 三元组
    —— 三元组不是啰嗦：手册要求两周后回来校准，没有 source/confidence 就没人知道
    哪条是手册明写的、哪条是当初拍脑袋的，最后一条都不会被改。"""
    return block[key]["value"]


DEFAULT_HOST = "https://eu.posthog.com"
DEFAULT_PROJECT_ID = "211847"


# --------------------------------------------------------------------------- #
# PostHog HogQL 客户端
# --------------------------------------------------------------------------- #

class PostHog:
    """HogQL 查询客户端。只用标准库——这脚本要能在任何一台机器上 `python3 xxx.py` 就跑。"""

    def __init__(self, host, project_id, api_key, dry_run=False, timeout=60):
        self.host = host.rstrip("/")
        self.project_id = project_id
        self.api_key = api_key
        self.dry_run = dry_run
        self.timeout = timeout
        self.queries_run = []

    def query(self, name, hogql):
        """跑一条 HogQL，返回 results（行的二维数组）。dry-run 时只记录不联网。"""
        self.queries_run.append((name, hogql))
        if self.dry_run:
            return None
        payload = json.dumps({"query": {"kind": "HogQLQuery", "query": hogql}}).encode("utf-8")
        req = urllib.request.Request(
            "%s/api/projects/%s/query/" % (self.host, self.project_id),
            data=payload,
            headers={
                "Authorization": "Bearer %s" % self.api_key,
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8")).get("results", [])
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:500]
            raise SystemExit(
                "PostHog 查询失败 [%s] HTTP %s\n%s\n"
                "→ 401/403 多半是 key 没有该 project 的读权限；404 检查 POSTHOG_PROJECT_ID。"
                % (name, e.code, detail))
        except urllib.error.URLError as e:
            raise SystemExit("PostHog 连不上 [%s]：%s" % (name, e.reason))


# --------------------------------------------------------------------------- #
# 取数
# --------------------------------------------------------------------------- #

def version_filter(hygiene):
    """版本过滤 SQL 片段（见文件头「两个口径上的硬约束」）。"""
    clauses = []
    for v in tv(hygiene, "excluded_app_versions"):
        clauses.append("coalesce(properties.$app_version, '') != '%s'" % v)
    if tv(hygiene, "exclude_stag_builds"):
        clauses.append("position(coalesce(properties.$app_version, ''), '-stag') = 0")
    return ("\n      AND " + "\n      AND ".join(clauses)) if clauses else ""


def day_window(d):
    """[当日 00:00 WIB, 次日 00:00 WIB) 的 HogQL 边界串。"""
    return ("toDateTime('%s 00:00:00', 'Asia/Jakarta')" % d.isoformat(),
            "toDateTime('%s 00:00:00', 'Asia/Jakarta')" % (d + timedelta(days=1)).isoformat())


def scalar(rows, default=0):
    if not rows or not rows[0]:
        return default
    return rows[0][0] if rows[0][0] is not None else default


def fetch_dau(ph, d, vf):
    lo, hi = day_window(d)
    rows = ph.query("DAU %s" % d, """
SELECT count(DISTINCT person_id)
FROM events
WHERE timestamp >= {lo} AND timestamp < {hi}{vf}
""".format(lo=lo, hi=hi, vf=vf))
    return scalar(rows)


def fetch_event_users(ph, d, event, vf, label=None):
    lo, hi = day_window(d)
    rows = ph.query(label or ("%s %s" % (event, d)), """
SELECT count(DISTINCT person_id)
FROM events
WHERE event = '{event}'
  AND timestamp >= {lo} AND timestamp < {hi}{vf}
""".format(event=event, lo=lo, hi=hi, vf=vf))
    return scalar(rows)


def fetch_event_count(ph, d, event, vf):
    lo, hi = day_window(d)
    rows = ph.query("%s 次数 %s" % (event, d), """
SELECT count()
FROM events
WHERE event = '{event}'
  AND timestamp >= {lo} AND timestamp < {hi}{vf}
""".format(event=event, lo=lo, hi=hi, vf=vf))
    return scalar(rows)


def fetch_d1_retention(ph, measure_day, vf):
    """D1 = 「首次活跃在 D0 的人，D0+1 当天是否又来了」。

    口径刻意用**首次活跃**而非注册：手册的北极星 9.6%→20% 引的就是这个口径
    （诊断报告 §1.1「首次活跃日 = D0」）。换成注册口径分母会小一大截、数字虚高，
    看着涨了其实是换了尺子。
    """
    d0 = measure_day - timedelta(days=1)
    d0_lo, d0_hi = day_window(d0)
    d1_lo, d1_hi = day_window(measure_day)
    rows = ph.query("D1 留存 cohort=%s" % d0, """
WITH first_seen AS (
    SELECT person_id, min(timestamp) AS t0
    FROM events
    WHERE 1 = 1{vf}
    GROUP BY person_id
),
cohort AS (
    SELECT person_id FROM first_seen
    WHERE t0 >= {d0_lo} AND t0 < {d0_hi}
)
SELECT
    (SELECT count() FROM cohort) AS cohort_size,
    count(DISTINCT person_id) AS retained
FROM events
WHERE person_id IN (SELECT person_id FROM cohort)
  AND timestamp >= {d1_lo} AND timestamp < {d1_hi}{vf}
""".format(vf=vf, d0_lo=d0_lo, d0_hi=d0_hi, d1_lo=d1_lo, d1_hi=d1_hi))
    if not rows or not rows[0]:
        return d0, 0, 0
    size = rows[0][0] or 0
    retained = rows[0][1] or 0
    return d0, size, retained


def fetch_dau_baseline(ph, d, vf, days=7):
    """近 N 日（不含体检日）每日 DAU，用于判断今天是不是塌了。"""
    lo = "toDateTime('%s 00:00:00', 'Asia/Jakarta')" % (d - timedelta(days=days)).isoformat()
    hi = "toDateTime('%s 00:00:00', 'Asia/Jakarta')" % d.isoformat()
    rows = ph.query("近 %d 日 DAU 基线" % days, """
SELECT toDate(toTimeZone(timestamp, 'Asia/Jakarta')) AS day, count(DISTINCT person_id)
FROM events
WHERE timestamp >= {lo} AND timestamp < {hi}{vf}
GROUP BY day
ORDER BY day
""".format(lo=lo, hi=hi, vf=vf))
    return [(r[0], r[1]) for r in (rows or [])]


def fetch_share_flywheel(ph, d, vf, window=7):
    """分享飞轮（手册抓手 2）：近 N 日「发过内容的人」vs「分享出去的人」。

    手册指出的浪费就是这两个数的落差：106 人发布过，只有 15 人分享——链路没接上。

    <b>分享路径不止一条，必须全算，否则严重低估。</b>目前共五条：
      - `post_share_card_*`   内容分享卡（tapped → generated → sent）
      - `id_card_share_*`     宠物身份证卡分享（tapped → sent → rewarded）
      - `pet_card_share_tapped`   成长档案页分享 FAB（对应指标清单 SM-2 的核心拉新飞轮指标）
      - `milestone_share_created` 里程碑庆祝弹层分享（/m/{token}）
      - `publish_done_share_tapped` 发布成功页的成长册分享（/p/{cardToken}，抓手 2 接的那条）

    ⚠️ 各路径的埋点粒度不一致，所以这里返回两个数、不混成一个：
      - <b>发起</b>：任何 `*_tapped` / `created`。口径偏松——点了不等于真发出去。
      - <b>确认发出</b>：只认 `*_sent`。口径严，但只有内容卡和身份证卡这两条有 `_sent`，
        名片/里程碑/成长册三条<b>没有完成事件</b>，所以「确认发出」天然小于真实值。
    真实的分享量落在这两个数之间。别把其中任何一个当成唯一真相。
    """
    lo = "toDateTime('%s 00:00:00', 'Asia/Jakarta')" % (d - timedelta(days=window - 1)).isoformat()
    _, hi = day_window(d)
    initiated = ("'post_share_card_tapped', 'id_card_share_tapped', 'pet_card_share_tapped', "
                 "'milestone_share_created', 'publish_done_share_tapped'")
    completed = "'post_share_card_sent', 'id_card_share_sent'"
    rows = ph.query("分享飞轮 近 %d 日" % window, """
SELECT
    count(DISTINCT if(event = 'content_publish_submitted', person_id, NULL)) AS publishers,
    count(DISTINCT if(event IN ({initiated}), person_id, NULL))              AS sharers_initiated,
    count(DISTINCT if(event IN ({completed}), person_id, NULL))              AS sharers_sent
FROM events
WHERE event IN ('content_publish_submitted', {initiated}, {completed})
  AND timestamp >= {lo} AND timestamp < {hi}{vf}
""".format(lo=lo, hi=hi, vf=vf, initiated=initiated, completed=completed))
    if not rows or not rows[0]:
        return 0, 0, 0
    return rows[0][0] or 0, rows[0][1] or 0, rows[0][2] or 0


# --------------------------------------------------------------------------- #
# 判定
# --------------------------------------------------------------------------- #

class Check:
    """一条体检项：指标值 + 判定 + 候选动作。"""

    OK, WARN, ALARM, NODATA = "✅ 正常", "⚠️ 注意", "🔴 报警", "— 无数据"

    def __init__(self, name, value, threshold, verdict, note="", actions=None):
        self.name = name
        self.value = value
        self.threshold = threshold
        self.verdict = verdict
        self.note = note
        self.actions = actions or []


def pct(x):
    return "—" if x is None else "%.1f%%" % (x * 100)


def build_checks(m, th):
    """把取到的数按阈值判定。m=metrics dict, th=thresholds dict。"""
    daily = th["daily"]
    weekly = th["weekly"]
    north = th["north_star"]
    checks = []

    # ---- ① 北极星：D1 留存 ----
    size, retained = m["d1_cohort_size"], m["d1_retained"]
    rate = (retained / size) if size else None
    if size < tv(daily, "d1_cohort_min_size"):
        checks.append(Check(
            "D1 留存（北极星）",
            "%s（%d/%d）" % (pct(rate), retained, size),
            "≥ %s；cohort < %d 不判定" % (pct(tv(north, "d1_retention_target")), tv(daily, "d1_cohort_min_size")),
            Check.NODATA,
            "cohort 只有 %d 人，样本太小——3/20 和 1/5 都是 20%%，这个数不能拿来做决策。" % size,
            ["把 cohort 拉成 7 日滚动再看趋势，别看单日"]))
    elif rate < tv(daily, "d1_retention_alarm_below"):
        checks.append(Check(
            "D1 留存（北极星）",
            "%s（%d/%d）" % (pct(rate), retained, size),
            "≥ %s" % pct(tv(north, "d1_retention_target")),
            Check.ALARM,
            "低于手册报警线 %s。手册指定的第一反应是**先查推送有没有正常发出去**，"
            "而不是先改文案——推送没发出去和文案不行，长得一模一样。" % pct(tv(daily, "d1_retention_alarm_below")),
            ["查生命周期推送日扫日志：`lifecycle push daily scan: users=.. planned=.. dispatched=..`",
             "planned=0 → 是取数/开关问题（LIFECYCLE_PUSH_ENABLED 是否还是 false）",
             "dispatched < planned → 撞到 daily-cap，按天放量中，正常",
             "planned>0 且 dispatched>0 但 D1 没动 → 才轮到换文案（抓手 1 的 A/B）"]))
    elif rate < tv(north, "d1_retention_target"):
        checks.append(Check(
            "D1 留存（北极星）",
            "%s（%d/%d）" % (pct(rate), retained, size),
            "≥ %s" % pct(tv(north, "d1_retention_target")),
            Check.WARN,
            "在报警线之上、北极星之下。这是常态，不是事故——一切运营资源朝 20% 打。",
            ["继续跑生命周期推送，看 D1 文案 A/B 的分臂差异"]))
    else:
        checks.append(Check(
            "D1 留存（北极星）",
            "%s（%d/%d）" % (pct(rate), retained, size),
            "≥ %s" % pct(tv(north, "d1_retention_target")),
            Check.OK,
            "达到恢复放量的准入线。",
            ["可以开始考虑放量——但先确认归因修好了，否则放量还是买不到人"]))

    # ---- ② DAU ----
    base = [v for _, v in m["dau_baseline"]]
    median = statistics.median(base) if base else None
    if median:
        floor = median * tv(daily, "dau_drop_alarm_ratio")
        verdict = Check.ALARM if m["dau"] < floor else Check.OK
        checks.append(Check(
            "DAU", str(m["dau"]),
            "≥ 近 7 日中位数 %.0f × %.0f%% = %.0f" % (median, tv(daily, "dau_drop_alarm_ratio") * 100, floor),
            verdict,
            "近 7 日：%s" % ", ".join(str(v) for v in base),
            ["DAU 塌方先排除埋点/发版问题，再看是不是投放停了"] if verdict == Check.ALARM else []))
    else:
        checks.append(Check("DAU", str(m["dau"]), "—", Check.NODATA, "无基线数据"))

    # ---- ③ 兽医供给（手册抓手 4）----
    req, sess = m["consult_requests"], m["consult_sessions"]
    unanswered = max(0, req - sess)
    if req == 0:
        checks.append(Check("兽医接诊", "0 请求", "无人接单 < %d" % tv(daily, "consult_unanswered_alarm"),
                            Check.NODATA, "当日无问诊请求。"))
    else:
        verdict = Check.ALARM if unanswered >= tv(daily, "consult_unanswered_alarm") else Check.OK
        checks.append(Check(
            "兽医接诊", "%d 发起 / %d 建立会话（%d 单无人接）" % (req, sess, unanswered),
            "无人接单 < %d" % tv(daily, "consult_unanswered_alarm"), verdict,
            "手册抓手 4：供给不足比功能 bug 更伤信任——「发起→等待→无人接」会直接烧掉本地信任护城河。",
            ["立即看后台在线兽医数（Redis ZSET vet:online，PostHog 拿不到）",
             "在线兽医为 0 → 补人；补不上就限流，别让用户发起了再空等"] if verdict == Check.ALARM else []))

    # ---- ④ Feed 新内容（手册抓手 3）----
    posts = m["publish_events"]
    verdict = Check.ALARM if posts < tv(daily, "feed_new_posts_min") else Check.OK
    checks.append(Check(
        "Feed 当日新内容（真实用户）", "%d 条" % posts,
        "≥ %d 条" % tv(daily, "feed_new_posts_min"), verdict,
        "⚠️ 只统计 App 内发布的埋点。**运营种子内容走 /admin/seed-post，不经埋点，这里看不见**——"
        "补种子前先去后台确认今天实际发了多少。",
        ["空房间是留存杀手：去 /admin/seed-post?tab=batch 批量补种子",
         "《宠物科普内容运营智能体》prompt 就是给这一步用的"] if verdict == Check.ALARM else []))

    # ---- ⑤ 分享飞轮（手册抓手 2）----
    pub, shr = m["flywheel_publishers"], m["flywheel_sharers"]
    ratio = (shr / pub) if pub else None
    if not pub:
        checks.append(Check("分享飞轮（近 7 日）", "0 人发布", "—", Check.NODATA))
    else:
        verdict = Check.OK if ratio >= tv(weekly, "share_rate_of_publishers_min") else Check.WARN
        checks.append(Check(
            "分享飞轮（近 7 日）",
            "%d 人发布 → %d 人发起分享（%s），其中 %d 人确认发出"
            % (pub, shr, pct(ratio), m.get("flywheel_sharers_sent", 0)),
            "≥ %s" % pct(tv(weekly, "share_rate_of_publishers_min")), verdict,
            "手册抓手 2：这是「ROI 最高却完全没启动」的那一个。分享出去→朋友点进来→"
            "看到真实日记→注册（这条链路已验证 70% 转化）。",
            ["首次发布后**当场**给可分享的成长卡，别等他自己发现里程碑",
             "D1/D7 push 引用用户自己发过的内容"] if verdict != Check.OK else []))

    return checks


# --------------------------------------------------------------------------- #
# 报告
# --------------------------------------------------------------------------- #

BACKEND_SQL = {
    "召回 push 漏斗（手册每日 SOP 第 4 查）": """-- 昨日各生命周期节点的投递量与分层分布（后端库，PostHog 没有）
SELECT push_kind,
       variant,
       count(*) AS pushed
FROM lifecycle_push_marks
WHERE pushed_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Jakarta') - interval '1 day'
  AND pushed_at <  date_trunc('day', now() AT TIME ZONE 'Asia/Jakarta')
GROUP BY 1, 2
ORDER BY 1, 2;

-- 送达→点击：通知被读过即视为点击过（notifications.read_at 非空）
SELECT n.type,
       count(*)                                   AS sent,
       count(*) FILTER (WHERE n.read_at IS NOT NULL) AS opened
FROM notifications n
WHERE n.type LIKE 'LIFECYCLE_%'
  AND n.created_at >= now() - interval '7 days'
GROUP BY 1
ORDER BY 1;""",
    "召回→建档转化": """-- 收到 CREATE_PROFILE 变体推送的人，之后有没有真的建档
SELECT count(DISTINCT m.user_id)                                    AS pushed_users,
       count(DISTINCT p.owner_id)                                   AS created_profile
FROM lifecycle_push_marks m
LEFT JOIN pet_profiles p
       ON p.owner_id = m.user_id
      AND p.created_at > m.pushed_at
WHERE m.variant = 'CREATE_PROFILE'
  AND m.pushed_at >= now() - interval '14 days';""",
}


def render(report_date, checks, ph, th, metrics):
    L = []
    L.append("# TailTopia 每日运营体检 · %s（WIB）" % report_date.isoformat())
    L.append("")
    L.append("> 生成时间：%s WIB · 数据源：PostHog project %s"
             % (datetime.now(WIB).strftime("%Y-%m-%d %H:%M"), ph.project_id))
    L.append("> 口径：已排除 %s 与所有 `-stag` 内测包（诊断报告 §0：不分版本看的数字全是错的）"
             % "/".join(tv(th["data_hygiene"], "excluded_app_versions")))
    L.append("")

    alarms = [c for c in checks if c.verdict == Check.ALARM]
    L.append("## 一句话")
    L.append("")
    if alarms:
        L.append("**今天有 %d 项报警：%s。**" % (len(alarms), "、".join(c.name for c in alarms)))
    else:
        L.append("**今天无报警项。**")
    L.append("")

    L.append("## 体检表")
    L.append("")
    L.append("| 指标 | 实测 | 阈值 | 判定 |")
    L.append("|---|---|---|---|")
    for c in checks:
        L.append("| %s | %s | %s | %s |" % (c.name, c.value, c.threshold, c.verdict))
    L.append("")

    detail = [c for c in checks if c.note or c.actions]
    if detail:
        L.append("## 诊断与候选动作")
        L.append("")
        L.append("> 手册第五章：**你拍板，agent 干活**。下面是候选动作，不是已执行的动作——"
                 "本脚本只读，一条推送都不会发。")
        L.append("")
        for c in detail:
            L.append("### %s %s" % (c.verdict.split()[0], c.name))
            L.append("")
            if c.note:
                L.append(c.note)
                L.append("")
            for a in c.actions:
                L.append("- [ ] %s" % a)
            if c.actions:
                L.append("")

    L.append("## PostHog 拿不到的三块（需后台取数）")
    L.append("")
    L.append("| 缺口 | 为什么拿不到 | 去哪看 |")
    L.append("|---|---|---|")
    L.append("| 在线兽医数 | 存在后端 Redis ZSET `vet:online`，不落埋点 | 后台兽医管理页 |")
    L.append("| 运营种子内容发布量 | 走 `/admin/seed-post`，虚拟账号发布不经 App 埋点 | 后台种子发布页 |")
    L.append("| 召回 push 送达/点击/建档 | 在后端库 `lifecycle_push_marks` / `notifications` | 下方 SQL |")
    L.append("")
    for title, sql in BACKEND_SQL.items():
        L.append("**%s**" % title)
        L.append("")
        L.append("```sql")
        L.append(sql)
        L.append("```")
        L.append("")

    L.append("## 阈值校准状态")
    L.append("")
    L.append("> 手册第五章第 1 步：**先人工按 SOP 跑 2 周，把每个判断阈值跑准**。"
             "标 `guess` 的都还是拍脑袋，是最该被两周真实数据推翻的那些。改 "
             "`scripts/ops/thresholds.json`，别改脚本。")
    L.append("")
    L.append("| 阈值 | 值 | 依据 | 信心 |")
    L.append("|---|---|---|---|")
    for section in ("north_star", "daily", "weekly", "data_hygiene"):
        block = th[section]
        for key in sorted(k for k in block if not k.startswith("_")):
            spec = block[key]
            L.append("| `%s.%s` | %s | %s | %s |"
                     % (section, key,
                        json.dumps(spec["value"], ensure_ascii=False),
                        str(spec.get("source", "—")).replace("|", "/"),
                        spec.get("confidence", "—")))
    L.append("")
    return "\n".join(L)


# --------------------------------------------------------------------------- #

def main():
    p = argparse.ArgumentParser(description="TailTopia 每日运营体检（只读、只诊断）")
    p.add_argument("--date", help="体检日 YYYY-MM-DD（WIB），默认昨天")
    p.add_argument("--thresholds", default=DEFAULT_THRESHOLDS)
    p.add_argument("--out", help="报告落盘路径（默认只打印到 stdout）")
    p.add_argument("--dry-run", action="store_true", help="只打印将要执行的 HogQL，不联网")
    args = p.parse_args()

    with open(args.thresholds, encoding="utf-8") as f:
        th = json.load(f)

    if args.date:
        report_date = datetime.strptime(args.date, "%Y-%m-%d").date()
    else:
        report_date = datetime.now(WIB).date() - timedelta(days=1)

    api_key = os.environ.get("POSTHOG_PERSONAL_API_KEY", "")
    if not api_key and not args.dry_run:
        raise SystemExit(
            "缺 POSTHOG_PERSONAL_API_KEY。\n"
            "→ PostHog → Settings → Personal API keys → 建一个只读 key（Query Read 权限即可）。\n"
            "→ 先用 --dry-run 看要跑哪些查询，不需要 key。")

    ph = PostHog(os.environ.get("POSTHOG_HOST", DEFAULT_HOST),
                 os.environ.get("POSTHOG_PROJECT_ID", DEFAULT_PROJECT_ID),
                 api_key, dry_run=args.dry_run)
    vf = version_filter(th["data_hygiene"])

    d0, cohort_size, retained = fetch_d1_retention(ph, report_date, vf)
    metrics = {
        "d1_cohort_day": d0,
        "d1_cohort_size": cohort_size,
        "d1_retained": retained,
        "dau": fetch_dau(ph, report_date, vf),
        "dau_baseline": fetch_dau_baseline(ph, report_date, vf) or [],
        "signups": fetch_event_users(ph, report_date, "signup_succeeded", vf),
        "profiles": fetch_event_users(ph, report_date, "pet_profile_create_submitted", vf),
        "publish_events": fetch_event_count(ph, report_date, "content_publish_submitted", vf),
        # 🔴 用 consult_started 而不是 consult_request_submitted 当「请求量」——
        #    后者在 2026-08-06 就加进代码（ad58dc32），但 PostHog 近 90 日**一次都没上报过**，
        #    而同期 consult_session_started 有 5 次。埋点活着却零上报 = 那个兽医请求确认页
        #    要么不可达、要么 capture 没执行到，本身是个待查缺陷（见 docs/ops/rollout-plan.md 已知缺口）。
        #    拿它当分母会让「无人接单」永远算成 0，把供给断裂整个盖住。
        "consult_requests": fetch_event_users(ph, report_date, "consult_started", vf),
        "consult_sessions": fetch_event_users(ph, report_date, "consult_session_started", vf),
    }
    pub, shr, sent = fetch_share_flywheel(ph, report_date, vf)
    metrics["flywheel_publishers"] = pub
    metrics["flywheel_sharers"] = shr
    metrics["flywheel_sharers_sent"] = sent

    if args.dry_run:
        print("# DRY RUN —— 以下 %d 条 HogQL 将被执行，未联网\n" % len(ph.queries_run))
        for name, q in ph.queries_run:
            print("-- [%s]%s\n" % (name, q))
        return

    report = render(report_date, build_checks(metrics, th), ph, th, metrics)
    print(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(report + "\n")
        print("\n(已写入 %s)" % args.out, file=sys.stderr)


if __name__ == "__main__":
    main()
