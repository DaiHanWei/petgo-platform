# Investigation: stag 创建 KTP（身份证卡）500 — 流水号池与卡快照撞号

## Hand-off Brief

1. **What happened.** stag 上 `POST /pet-profiles/me/id-cards` 恒 500：号池 `pet_serial_pool` 里躺着 serial 2，而 serial 2 已被卡快照 `id_cards.id=1` 永久占用，`allocate()` 每次都取池内最小号 2 → 撞 `uq_id_cards_serial`（Confirmed，日志 + DB 双证据）。
2. **Where the case stands.** 根因链完整：V92 回填把档案 serial **复制**进卡但没清档案上的号（双持有）→ 用户删档案时 `ProfileDeletionService` 把该号释放回池 → 号在卡里仍冻结 → 后续任何建卡都撞号。**全平台建卡被阻断**（不止该用户），且 prod 下次发版带 V92 后有同款潜伏雷。
3. **What's needed next.** ① stag 数据修复：从池中剔除已被 id_cards 占用的号；② 代码修复：release 前校验号未被任何卡持有（或 V92 语义改为「号转移给卡」）。

## Case Info

| Field | Value |
| --- | --- |
| Ticket | 用户口述（2026-07-27 stag 建卡失败） |
| Date opened | 2026-07-27 |
| Status | Concluded（根因 Confirmed，修复待授权） |
| System | stag（petgo-server-stag@8085 / petgo_stag 库），代码 v1.1-dev |
| Evidence sources | stag /app/logs/petgo.log、petgo_stag DB、源码 |

## Confirmed Findings

### Finding 1: 500 的直接异常 = uq_id_cards_serial 撞号

**Evidence:** stag 日志 2026-07-27T05:30:05Z traceId=2d6ac1d6-…：`DataIntegrityViolationException: duplicate key value violates unique constraint "uq_id_cards_serial" Detail: Key (serial_id)=(2) already exists`（用户 sub=23，05:30 连续 3 次重试全 500）。

### Finding 2: DB 状态 = 号池与卡快照双持有 serial 2

**Evidence:** petgo_stag 查询：`pet_serial_pool` 仅含 {2}；`id_cards` id=1（user 23,"Haha", 2026-07-21）serial_id=2；user 23 现档案（id=27 "k k"）serial=NULL；`pet_serial_seq` last_value=8。

**Detail:** `SerialAllocationService.allocate()`（SerialAllocationService.java:42-48）优先取池内最小号 → 恒返回 2 → 恒撞 id_cards 已有行。**池不清理则任何用户建卡都 500**。

### Finding 3: serial 2 入池的路径 = V92 回填复制 + 删档释放

**Evidence:**
- V92__alter_id_card_hd_purchases_per_card.sql:16（回填卡 `COALESCE(p.serial_id, nextval(...))` —— 复制档案号进卡，**未清 `pet_profiles.serial_id`**）；
- ProfileDeletionService.java:96-98（删档案时 `release(pet.getSerialId())` 无条件回池）；
- id_cards 决策③「旧卡保留」= 卡上的号永久冻结，不该回池。

**Reasoning（链条）:** user 23 旧档案 "Haha" 持 serial 2（6-1 单卡时代生成）→ V92 回填建卡 id=1 复制 serial 2（档案仍持 2）→ 用户删除 "Haha" 档案 → release(2) 入池 → 今日建新卡 allocate() 复用 2 → 撞卡 id=1。

## Deduced Conclusions

### Deduction 1: 缺陷本质 = 号的所有权分裂后，回收路径只认档案侧

6-7 多卡返工后 serial 由 pet_profiles 与 id_cards 两处持有，但回收仅看档案（ProfileDeletionService），不知道卡快照仍冻结该号。V92「复制而非转移」放大了双持有面。prod 尚未带 V91/V92，下次发版后同类数据会复现。

## Fix Applied（2026-07-27，已执行）

1. **stag 数据修复（已执行）**：`DELETE FROM pet_serial_pool WHERE serial_id IN (SELECT serial_id FROM id_cards);` → 删 1 行（serial 2），池清空，建卡即时恢复（下次分配走序列取 9）。
2. **代码（v1.1-dev，未提交）**：`SerialAllocationService` 双守卫——`release()` 改 `INSERT ... SELECT ... WHERE NOT EXISTS(id_cards 持有)`（卡冻结号不回池）；`allocate()` 复用池号时跳过被 id_cards 持有的号（防御历史污染）。
3. **测试**：`SerialAllocationIntegrationTest.cardFrozenSerialIsNeverRecycled` 回归用例（释放守卫 + 分配防御）。L1 scratch 库实跑：SerialAllocation 5/5、IdCardServiceTest+ProfileDeletionIntegrationTest 8/8 全绿。
4. 未做（评估后不必要）：迁移清双持有档案 serial——release 守卫已覆盖删档场景，双持有本身展示语义无害。

**Prod 注意**：prod 尚未带 V91/V92；下次发版携带本代码修复后，V92 回填的双持有数据不会再触发本事故。
