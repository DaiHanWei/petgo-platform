# Investigation: 兽医接单后其他排队请求被屏蔽 + B 端倒计时异常

## Hand-off Brief

1. **What happened.** 兽医接 A 后看不到 B、直到 end session 才能接 B —— 「兽医占用互斥」设计使然（Confirmed）；B 端「倒计时回到 1 分钟」是排队超时弹框点「继续排队」后 deadline 重置（Confirmed）。
2. **Resolution（2026-07-27，已实现）.** 正式提报为 bug 20260727-364；用户拍板**取消「一兽医一单」**（决策 M-1，见 CROSS-STORY-DECISIONS.md）：接单去互斥、计费流不再触碰 vet:busy、队列池恒可见、awaitingPay→awaitingPays 列表、FR-53B 判成交改集合差+会话数增量。后端 43 测试 + App 456 测试全绿，未提交待部署 stag 联调。
3. **Status: Concluded.**

## Case Info

| Field            | Value |
| ---------------- | ----- |
| Ticket           | N/A（用户口述） |
| Date opened      | 2026-07-27 |
| Status           | Active |
| System           | v1.1-dev 工作树（petgo-backend + petgo_app），付费问诊计费流（consult_requests） |
| Evidence sources | 源码走读（backend service/repository/controller + Flutter 等待页/仓库/路由） |

## Problem Statement

用户 A、B 同时发起付费兽医问诊，兽医工作台显示 2 个订单。兽医接 A → 显示 5 分钟（支付窗）倒计时，A 进支付流程。此期间 B 的倒计时"又回到 1 分钟且不变"；兽医整个与 A 交互期间拿不到 B 的请求，直到点 end session 后才能获取 B。用户怀疑逻辑有问题。

## Confirmed Findings

### Finding 1: 兽医忙碌时队列池对其恒空（设计使然）

**Evidence:** petgo-backend/src/main/java/com/tailtopia/consult/service/ConsultRequestService.java:227（`if (!presence.isBusy(vetId))` 才查 QUEUEING 池，注释明写「忙（接单中/会话中）则空——不能再接」）

**Detail:** `GET /vet/consultations/queue` 返回 `awaitingPay`（本兽医唯一 ACCEPTED_AWAIT_PAY 单）+ `available`（QUEUEING 池）。兽医 BUSY 时 available 恒为空列表。

### Finding 2: 占用从接单一直持续到会话 close

**Evidence:**
- 接单置 BUSY：ConsultRequestService.java:301（`afterCommit(() -> presence.goBusy(vetId))`）
- 接单入口互斥：ConsultRequestService.java:291（`isBusy` → 409「您有进行中的接单」）
- 释放点仅三处：会话结束 ConsultCloseService.java:77（`goAvailable`，即 end session）；支付窗超时 ConsultRequestService.java:327；用户取消 ConsultRequestService.java:387；另有现金故障路径 ConsultPayService.java:269。

**Detail:** A 支付成功建会话后兽医仍 BUSY，贯穿整个 IM 会话，直到 end session。这解释了「直到点 end session 后才能获取到 B」。

### Finding 3: B 的排队窗只有 60 秒，超时物理删；「继续排队」每次 +60s

**Evidence:**
- `QUEUE_TIMEOUT_SECONDS = 60`：ConsultRequestService.java:50
- 30s 一次扫描物理删过期 QUEUEING：ConsultRequestTimeoutScanner.java:31-41 + ConsultRequestRepository.java:55-59
- 延长端点（bug 20260720-311）：ConsultRequestService.java:192-204（`extendQueue` 重置 deadline = now+60s）

### Finding 4: B 端等待页在剩余 ≤20s 弹「继续排队?」，点继续即回到 1 分钟

**Evidence:** petgo_app/lib/features/consult/presentation/vet_waiting_page.dart:40-42（threshold 20s）、:163-174（`_extendQueue` 后 deadline 重置）

**Detail:** 「倒计时又回到 1 分钟」与 extend 重置行为完全吻合。B 的请求能存活数分钟直到兽医 end session，前提是 B 端不断点「继续排队」（否则 60-90s 内被扫描物理删，B 转「暂无兽医」态）。

### Finding 5: 倒计时显示上限被 clamp 在 60s

**Evidence:** vet_waiting_page.dart:76-81（`clamp(0, _fallbackSeconds)`，`_fallbackSeconds = 60`）；deadline 为 null 时 `_remaining` 保持原值（初始 60）。路由未传 queueDeadlineAt（app_router.dart:274），首帧起靠 3s 轮询回填。

### Finding 6: 支付窗为 5 分钟（与兽医端看到的 5 分钟倒计时吻合）

**Evidence:** `PAY_WINDOW_SECONDS = 300`：ConsultRequestService.java:53。（注意 VetConsultRequestController.java:25 与 ConsultRequestStatusResponse javadoc 仍写「1.5min」，为陈旧注释。）

## Deduced Conclusions

### Deduction 1: 「兽医接 A 后拿不到 B」不是 bug，是「一兽医一单」占用互斥模型

**Based on:** Findings 1、2

**Reasoning:** 抢单模型下 QUEUEING 池对所有「在线且不忙」兽医开放；接单即占用，占用贯穿 支付窗+会话。多兽医时 B 会被其他空闲兽医接走，模型自洽。

**Conclusion:** 行为符合 Story 3.3/3.6 设计。但在「只有 1 个在线兽医」的现状下退化成：B 盲等、兽医盲忙。

### Deduction 2: 真正的设计缺口是双向无感知 + 窗口失配

**Based on:** Findings 1-4

**Reasoning:** ① B 端拿不到任何「兽医正忙」信号，只能每 ~40s 被弹一次「继续排队?」无限续命；② 兽医端 BUSY 期间 available 恒空，完全不知道有人在排队（连计数都没有）；③ 排队窗 60s 远短于兽医占用时长（5min 支付窗 + 无上限会话），单兽医时 B 几乎必然经历多轮弹框或超时失败。

**Conclusion:** 功能正确性成立，产品体验链路在单兽医环境事实上断裂；是否改（如：忙碌提示/预计等待、兽医端显示排队计数、排队窗自动顺延）是产品决策。

## Hypothesized Paths

### Hypothesis 1: 「倒计时不变（冻结在 1:00）」= 设备与服务器时钟偏差 + 显示 clamp

**Status:** Open

**Theory:** deadline 是服务端绝对时刻，前端用本机时钟差值计算并 clamp 到 60；若模拟器时钟落后服务器 N 秒，重置后剩余 = 60+N，显示恒 01:00 冻结 N 秒。

**Supporting indicators:** Finding 5 的 clamp 逻辑；模拟器时钟漂移常见。

**Would confirm:** 复现时对比 `adb shell date` 与服务器时间；或在 B 端打印 `queueDeadlineAt` 与本机 now 差值。

**Would refute:** 时钟一致仍冻结。

### Hypothesis 2: 「不变」是观察口径问题（弹框循环，局面不变）

**Status:** Open

**Theory:** B 实际处在「60→20→弹框→点继续→回 60」循环，数字在动但始终回到 1 分钟、局面无进展；或弹框（barrierDismissible=false）挡住倒计时期间被理解为冻结。

**Would confirm:** 用户回忆/复现确认是否反复出现弹框。

## Missing Evidence

| Gap | Impact | How to Obtain |
| --- | --- | --- |
| B 端是否点过「继续排队」弹框、点了几次 | 区分 H1/H2；解释 B 请求为何存活数分钟 | 询问用户 / 复现 |
| 测试连接的后端环境（stag or prod）及其部署版本是否含 bug-311 改动 | 若后端是旧版（超时回队重播/无 extend 端点），行为解释需重算 | 用户确认 + 环境版本核查 |
| 冻结时刻的前端日志（poll 返回的 queueDeadlineAt vs 本机时间） | 直接裁决 H1 | 复现 + 控制台日志 |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | 复现 B 端倒计时冻结并取日志 | High | Open | 裁决 H1/H2 |
| 2 | 产品层决策：忙碌提示/排队计数/窗口失配是否立单 | Medium | Open | 属 UX 决策非代码缺陷 |
| 3 | 清理陈旧注释：Controller/DTO「1.5min 支付窗」、Scanner 类注释「回 QUEUEING 重播」 | Low | Open | 纯文档，防后人误读 |
