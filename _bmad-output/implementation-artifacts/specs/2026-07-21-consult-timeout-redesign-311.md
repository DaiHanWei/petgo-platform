# Spec：付费问诊两类超时重设计（bug 20260720-311）

> 分支 v1.1-dev。自包含，可本地或云端 L0 执行；L1（Docker+postgres）/L2（模拟器）留本地。
> 与用户沟通中文。改动动的是**付费问诊匹配安全链路**，谨慎。

## 1. 背景与目标

付费问诊状态机（`consult_requests`，Story 3.2/3.3）只有两活态：
`QUEUEING`（入队待接单）→ `ACCEPTED_AWAIT_PAY`（兽医接单、待用户 5min 内支付）。**无 CANCELLED 态**——取消/超时=物理删行（A-5「订单中心无已取消态」根因）。

两个 `@Scheduled` 扫描器（`ConsultRequestTimeoutScanner`）：
- **排队超时** `scan()`→`purgeExpiredQueue()`（Story 3.2）：`QUEUEING` 且 `queue_deadline_at` 过期 → **静默物理删**。
- **支付超时** `scanPayWindow()`→`revertExpiredAcceptances()`（Story 3.3/3.4）：`ACCEPTED_AWAIT_PAY` 且 `pay_deadline_at` 过期 → 释放兽医、请求**回 QUEUEING 重新广播**（UX-DR14），直到 `rebroadcast_count≥maxRebroadcast(5)` 或存活超 `requestMaxAgeMinutes(30)` 才删+落 `failed_consult_requests(TIMEOUT)`。

关键常量（`ConsultRequestService`）：`QUEUE_TIMEOUT_SECONDS=60`、`PAY_WINDOW_SECONDS=300`。
前端：等待页 `consult_waiting_page.dart` / 支付页 `vet_timed_pay_page.dart`；`statusOf`（`GET` 请求状态）返回整个 `ConsultRequest`（含 `state`/`queueDeadlineAt`/`payDeadlineAt`/`pausedAt`），前端已据此倒计时。

**用户（产品 HeXin）要的新行为（2026-07-21 拍板）：**
- **排队超时 → 弹框问「是否继续排队」**（不再静默删）。
- **付款超时 → 结束这次请求**（不再自动重播）。

## 2. 反转/影响的既有决策（实现前须知，落 Completion Notes）

- **反转 UX-DR14**：付款超时不再回队重播；兽医侧不再收到超时请求的重播。
- **Story 3.2 排队 purge 保留为兜底**：不删该扫描器（防用户 app 关了不响应时的僵尸请求），只在其触发**之前**由前端加一层「继续排队?」弹框 + 延长。**不算完全反转**。

## 3. 后端改动

### 3.1 付款超时 → 结束请求（简化 `revertExpiredAcceptances`）
`ConsultRequestService.revertExpiredAcceptances()`：**去掉回队重播分支**，让每个过期 `ACCEPTED_AWAIT_PAY` 恒走现有 capHit 路径（`deleteIfState` + `afterCommit(goAvailable(vetId))` 释放兽医 + `publishEvent(ConsultRequestFailedEvent("TIMEOUT",...))`）。
- 即：现有代码里 `if (capHit) {...} continue;` 的 capHit 分支变成无条件执行，删掉后面的 `revertExpiredAcceptance`+rebroadcast 分支。
- 建议方法改名 `endExpiredAcceptances()`（+扫描器调用点+日志文案「支付窗超时结束请求」）。
- `maxRebroadcast`/`rebroadcast_count` 对支付超时不再有意义（可保留字段不动，避免迁移；仅逻辑不再读）。
- 暂停中（`paused_at IS NOT NULL`，跳充值 A-4）仍排除，不变。

### 3.2 排队超时 → 前端弹框 +「延长排队」新端点
- **新端点**：`POST /api/v1/consult/vet-requests/{requestToken}/extend-queue`（命名对齐既有 `/consult/vet-request/*` 路由风格，实现时核对 `ApiPaths` 与 controller）。
  - 语义：仅本人 + `state=QUEUEING` + `paused_at IS NULL` → CAS 把 `queue_deadline_at = now + QUEUE_TIMEOUT_SECONDS`；非法（不存在/非本人/非 QUEUEING）→ 404/409 防枚举。返回新 deadline。
  - service 方法 `extendQueue(userId, requestToken)`；repo 加 CAS `bumpQueueDeadlineIfQueueing`。
- **`purgeExpiredQueue()` 不改**（兜底删无响应请求）。

## 4. 前端改动

### 4.1 支付超时（`vet_timed_pay_page.dart`）
- 现 `_gotoActiveOrExit()`：404→查 active→无会话时弹 `vetPayExpired` toast + `go('/triage')`。
- 文案由「支付超时（将重播）」语义改为**「支付超时，本次请求已结束」**（新 arb key 或复用，两份 arb 同步），退回 `/triage`。无「重新匹配」按钮（付款超时即终结）。

### 4.2 排队超时弹框（付费队列等待页）
- 目标页：付费问诊排队等待页（路由 `/consult/vet-request/waiting/:token`，实现时确认是 `consult_waiting_page.dart` 还是 `vet_waiting_page.dart`）。
- 页内已有 1s `_display` 倒计时 + 轮询 `statusOf`。新增：倒计时逼近 `queueDeadlineAt`（建议 **剩余 ≤20s** 时）弹**一次性**对话框「排队超时，是否继续排队?」`[继续排队]` / `[取消]`。
  - `[继续排队]` → 调 extend 端点 → 成功后本地按新 deadline 续计时、关弹框；失败（已被 purge→404）→ 提示已结束 + `go('/triage')`。
  - `[取消]` → 调既有取消/删除端点 → `go('/triage')`。
  - 无响应（app 关/不点）：purge 扫描器到点删 → 下个 `_tick` 拿 404 → 现有 404 分流退出。
- arb 两份同步加：`consultQueueTimeoutTitle` / `consultQueueTimeoutContinue` / `consultQueueTimeoutCancel`（印尼语 + 英语）。

## 5. 待定子项（默认值，可改）

| 子项 | 默认 |
|---|---|
| 继续排队顺延时长 | +`QUEUE_TIMEOUT_SECONDS`(60s)/次 |
| 继续次数上限 | 不设显式上限（排队不占兽医、低危）；无响应由 purge 兜底 |
| 弹框提前量 | 剩余 ≤20s 弹（留足调 extend 的时间，避开 30s 扫描周期抢删） |
| 付款超时前端去向 | 回 `/triage` + 「已结束」提示 |

## 6. 验证

- **L0**：后端 `mvn -B compile` + `test-compile`；`flutter analyze` + `flutter test test/consult`。
- **L1（本地，Docker+postgres）**：⚠️ **现有 Story 3.3/3.4 测试断言了「回队重播」，改成 end 后必挂 → 须同步重写**（断言：支付超时后请求被删 + `ConsultRequestFailedEvent(TIMEOUT)` + 兽医 goAvailable + 不再有 QueuedForBilling 重播事件）。新 extend 端点加集成测试（QUEUEING 顺延、非本人 404、非 QUEUEING 409）。跑法见 [[shared-dev-db-and-parallel-flyway-collision]]（flush Redis + scratch 库 + DB_NAME env）。
- **L2（本地模拟器）**：真机走排队超时弹框「继续/取消」+ 付款超时「已结束」两条路径视觉验收。

## 7. 落地纪律

- 一次只碰一侧：后端(3.1→3.2) → 前端(4.1→4.2) → 联调。最小改。
- 无迁移（不加列/不动 `failed_consult_requests`）。
- Completion Notes 记：反转 UX-DR14、重写的 3.3 测试清单、extend 端点契约、L1/L2 待本地。
- 关联记忆：[[v11-p0-order-triage-batch]]、[[v11-consult-qris-fix]]、[[v11-bugfix-workflow]]。
