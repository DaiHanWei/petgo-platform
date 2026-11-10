# Story 10.2: Biteship 物流全链路联调（L2，本地执行）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（10.1 之后执行）
> 口径优先级：`决策日志-shop.md`（「按推荐定下」Biteship 邮编询价 / Webhook 路径密钥 + 回查 / 报价复用 / 自动送达 14 天 / 物流异常暂停自动送达完成；I14；I10）> PRD `电商/1-4-2电商prd.md`（FR-172、§3.0 旅程 D、§4 物流埋点）> `architecture-v1.4.2-shop-delta.md`（D-9、D-10、D-12、D-13、D-24、P-7、§1.2 第 25 条、§6.4）> UI 稿 F6 / F7 / F9 / F10 / F11。
> **🔴 本 story 不在云端跑**（`cloud-rules-shop.md`：Epic 10 留本地）。**不新增功能**；缺陷回 `feat/1.4.2-shop` 修。
> **🔴 环境纪律**：本地后端 + Biteship **测试 key（sandbox）**；Webhook 回调需要公网地址时用本机临时隧道（见 E1），验完即关；**stag 只在用户明确下令部署后才用**；**生产一律不碰**（「上线前检查」里涉及生产数据的查询交用户执行或经用户授权）。Biteship key 只放本机 env，不入库、不进日志、不贴进验收记录。
> 产出：写入 `_bmad-output/implementation-artifacts/v1.4.2/acceptance-v1.4.2-shop.md` 的「物流（10.2）」与「上线前检查」两节（模板见文末）。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-6**：物流异常类型 `LOST_DAMAGED` 拆为 `LOST`、`DAMAGED`（订单头 `logistics_exception` 取值：`PICKUP_FAILED | RETURNED | STALLED | LOST | DAMAGED`）；用户侧「配送异常」条件为已确认的 `RETURNED / LOST / DAMAGED`；埋点 `exception_type ∈ returned | lost | damaged`。取消原因 `CancelReason.LOST_DAMAGED` 保持不拆。
- **C-13**：stag 部署前须用 SQL 补齐存量 SKU 包裹参数、`shipping_settings` 发货地邮编、`shipping_zones.postal_code`，否则全部商品「暂不可售」。

## Story

As a 研发与验收人,
I want 在 Biteship 沙箱里把运单与物流状态走一遍,
so that 上线后发货与签收能自动流转。

## Acceptance Criteria（= epics Story 10.2，逐条对应下文场景）

| # | epics AC | 层级 | 场景 |
|---|---|---|---|
| 1 | 已付款订单经 stag 入口下运单 → 拿到运单号、订单仍待发货；控制台依次触发 `allocated → picked → in_transit → dropping_off → delivered`，App 物流状态逐段变化，`picked` 后进待收货、`delivered` 后已签收 | L2 | L1、L2 |
| 2 | 触发 `courier_not_found` → 揽收失败标记、用户侧不提示；触发 `returned` → 运营确认后用户侧「配送异常」+ 联系客服 | L2 | L3 |
| 3 | 伪造 Webhook（错误密钥 / 篡改载荷）不改订单状态；请求日志中密钥已脱敏 | L1 / L2 | L4 |
| 4 | 询价次数：同一包裹 30 分钟内详情、购物车、结算三处只计 1 次 Biteship 询价 | L2 | L6 |
| 5 | 正式 key 开通、Webhook 地址、发货地信息、存量 SKU 包裹参数与区域邮编补齐情况记入「上线前检查」 | L2 | L8 |
| 补 | 运单失败与告警、物流停滞与恢复计时、自动送达 14 天与异常排除、快递原因重发（架构 D-12 / D-13 的 L1 行为在真沙箱下复核） | L1 / L2 | L5、L7 |

## 验收环境

| 代号 | 配置 |
|---|---|
| **E1** | 10.1 的本地后端（`SPRING_PROFILES_ACTIVE=dev,stag`、全新空库、`PAY_MODE=stub`）改 env：`BITESHIP_MODE=sandbox`、`BITESHIP_API_KEY=<Biteship 测试 key，本机 env，前缀区分测试/正式>`、`BITESHIP_COURIERS=<验收用的快递码列表，如 jne,sicepat,anteraja>`、`BITESHIP_WEBHOOK_SECRET=<随机 32 位>`。Webhook 需公网 HTTPS：本机临时隧道（如 `cloudflared tunnel --url http://localhost:8080`，拿到临时域名）→ 在 Biteship 测试模式后台配置回调 `https://<临时域名>/api/v1/webhooks/biteship/<secret>`；**验收结束立刻关隧道并删掉后台回调配置**。不方便开隧道时，改在 stag 做（须用户下令部署）。 |
| **E2** | Android 模拟器调试包连 E1（`--dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080`），adb 截图。 |
| **E0** | 先跑一遍 L0 / L1（`BiteshipStatusMapper` 15 值穷举单测、Webhook / 运单 / 物流异常集成测试，`BITESHIP_MODE=stub`）全绿，作为沙箱联调的基线。 |

## 前置检查（T0，任一不过先停下报告）

1. 10.1 已完成（至少 S1 跑通），E0 全绿。
2. 对照已提交代码记录实际名字（本文件是计划名）：Webhook 路径（计划 `POST /api/v1/webhooks/biteship/{secret}`）；D-24 下运单入口（计划 `POST /admin/stag/shop/orders/{token}/waybill`，走后台会话 + SUPER_ADMIN，见 10.1 E1）及 5.1 的「推进 stub 状态」入口；运营侧「确认物流异常为 RETURNED / LOST_DAMAGED」「清除标记」「快递原因重发」「取消旧运单」的 service 入口——**D-24 没有列物流异常相关入口**（见「待确认」1），没有则 L3 后半与 L7 记阻塞；`shipments` 新列与订单头物流异常列的实际名（5.1 / 5.2 迁移 `V20261105_1640`）；定时任务（Webhook 重试 5 分钟、在途回查 6 小时、停滞检测每小时、自动送达扫描 10 分钟）的手动触发方式。
3. Biteship 测试模式能力确认（看 Biteship 测试后台 / 文档）：测试 key 能否调 `POST /v1/orders`（Order API）；测试订单的状态能否在后台**手动推进**（epics 写「沙箱控制台手动触发 Webhook」）。若不能手动推进 → L2 的逐段状态改用 5.1 story 提供的「推进 stub 状态」stag 入口（`BITESHIP_MODE=stub` 时调 `BiteshipStubScript.setStatus` 后立即 `applySnapshot`）在本地走完 App 侧逐段显示，并在验收记录注明「状态由 stub 推进、非 Biteship 沙箱」（见「待确认」2）。
4. 前置数据沿用 10.1「前置数据」1 ~ 6（SKU 包裹参数、发货地 `origin_*`、开通区域邮编、用户与地址）；发货地邮编与收货邮编须是 Biteship 测试环境能报价的真实印尼邮编（如 12730 → 12190）。

## 通用核对 SQL（列名以 5.1 / 5.2 迁移为准，有出入就地改）

```sql
SELECT o.display_no, o.status, o.shipped_at, o.shipped_source, o.delivered_at, o.delivery_source, o.completed_at,
       o.logistics_exception, o.logistics_exception_at, o.logistics_exception_confirmed, o.claim_pending,
       o.timer_resumed_at, o.waybill_failed_at, o.waybill_fail_reason, o.courier_code, o.courier_service_code,
       o.insurance_selected
  FROM shop_orders o WHERE o.public_token = :orderToken;
SELECT id, provider, carrier, courier_code, courier_service_code, biteship_order_id, waybill_id, tracking_id,
       provider_status, provider_status_at, insurance_amount, superseded_at, shipped_at, delivered_at
  FROM shipments WHERE order_id = :orderId ORDER BY id;
SELECT id, biteship_order_id, event, received_at, processed_at, attempts, last_error
  FROM biteship_webhook_events ORDER BY id DESC LIMIT 20;      -- 不 SELECT payload 原文贴记录（含地址 / 电话）
SELECT dest_postal_code, items_hash, origin_postal_code, lowest_price, created_at, expires_at
  FROM shipping_quotes ORDER BY id DESC LIMIT 10;
```

接口请求日志（全接口落盘）按日期文件检索；**贴记录前对地址、手机号、key 打码**。

## 场景

### L1 下运单（AC1 前半，E1 + E2）

1. U1 下一单（勾保价）并用 curl 付款（10.1 前置检查 3）→ `PENDING_SHIPMENT`。App 详情 F6「Menunggu dikirim」、无物流卡（截图）。
2. 调 D-24 下运单。**核对**：Biteship 测试后台出现该订单（`reference_id` = 订单号 + 序号）；`shipments` 一行 `provider=BITESHIP`，`biteship_order_id` / `waybill_id` / `tracking_id` 有值，`courier_code` / `courier_service_code` = 订单锁定的快递与服务（D-8 提交时锁定，**不重新询价**——`shipping_quotes` 无新增）；`insurance_amount` = 商品实付（勾保价）；订单**仍 `PENDING_SHIPMENT`**（运单创建 ≠ 发货，PRD 11-05）；App 仍是 F6（运单创建后用户侧仍是「待发货」）。
3. 同一订单重复调下运单 → 不产生第二张有效运单（幂等或拒绝，以 5.1 实现为准，记录行为）。

### L2 状态逐段推进（AC1 后半，E1 + E2）

在 Biteship 测试后台依次把该测试订单推进到下列状态（每步等 Webhook 到达并处理，或手动触发 Webhook 重试任务）：

| 步 | Biteship 状态 | 期望：订单 | 期望：用户侧（App 详情 / 列表） | 核对 |
|---|---|---|---|---|
| 1 | `allocated` | `PENDING_SHIPMENT` 不变 | F6 待发货，无物流卡 | `biteship_webhook_events` 一行且 `processed_at` 有值；`shipments.provider_status='allocated'` |
| 2 | `picked` | → `SHIPPED`、`shipped_source=BITESHIP_PICKED`、`shipped_at` 有值 | 列表进「Dikirim」组且角标 +1；详情 F7：物流卡 5 段第 2 段「Sudah dijemput」点亮（截图） | 处理时日志有 `getOrder` 回查（以回查结果为准） |
| 3 | `in_transit` | `SHIPPED` | 第 3 段「Dalam perjalanan」（截图） | 列表卡状态文字随之变化 |
| 4 | `dropping_off` | `SHIPPED` | 第 4 段「Sedang diantar」（截图） | |
| 5 | `delivered` | → `DELIVERED`、`delivery_source=BITESHIP`、`delivered_at` | F9「Paket sudah diterima」5 段全亮（截图）；仍在「Dikirim」组、可「Konfirmasi terima」 | |
| 6 | 用户点「Konfirmasi terima」 | → `COMPLETED` | F11「Selesai」+ 物流卡仍显示（截图）；无二次确认弹窗 | |

另：同一状态的 Webhook 重复投递（在后台重发或用正确密钥 + 真实 `order_id` 再 POST 一次）→ `biteship_webhook_events` 多一行但订单 / 运单无变化、无重复副作用（幂等）。

### L3 揽收失败与退回（AC2，E1 + E2）

1. 新单下运单后推进到 `courier_not_found`。**核对**：订单 `logistics_exception=PICKUP_FAILED`、`logistics_exception_at` 有值；订单状态不变；**App 不提示异常**（待发货 / 「待收货 · 待揽收」照常，截图）；该单不参与自动送达 / 完成（L7.3 复核）。揽收失败的三种处置（重下运单 / 手工运单 / 取消）执行前先调 Biteship 取消旧运单（`cancelWaybill`）——若有运营入口则各做一次并核对 Biteship 后台旧单已取消，无入口记阻塞。
2. 另一单推进到 `picked` 后再到 `return_in_transit` / `returned`。**核对**：订单 `logistics_exception=RETURNED`、`logistics_exception_confirmed=false`；App 仍显示最后物流状态（**未确认前不提示**，截图）。
3. 运营确认为退回（5.2 service 入口；D-24 未列，见「待确认」1）→ `logistics_exception_confirmed=true`。**核对**：App 详情 F10「Pengiriman bermasalah / Silakan hubungi layanan pelanggan kami…」+ 状态头「Hubungi CS」+ 底栏次按钮「Konfirmasi terima」、物流卡只留快递与运单号不画 5 段（截图）；列表卡「Pengiriman bermasalah」+「Hubungi CS」「Konfirmasi terima」并列（截图）；埋点 `toko_delivery_exception_shown{exception_type: returned}` 一次、点「Hubungi CS」报 `_cs_tapped`；点「Hubungi CS」打开客服 WhatsApp（与页底同一入口）。
4. 配送异常下用户点「Konfirmasi terima」→ 订单 `COMPLETED`、`logistics_exception` 清空、运营提醒（订单备注 + 日志）写入。
5. 快递原因退回：`claim_pending=true`（后台线展示「待索赔」）；用户原因退回的取消与退款在 10.1 S6.5 已验，此处只核对标记。

### L4 伪造 Webhook 与密钥脱敏（AC3，E1）

1. 错误密钥：`curl -X POST https://<临时域名>/api/v1/webhooks/biteship/wrongsecret -H 'Content-Type: application/json' -d '{"event":"order.status","order_id":"<真实 biteship_order_id>","status":"delivered"}'` → **404**；`biteship_webhook_events` 无新行；订单不变。
2. 正确密钥 + 篡改载荷（订单真实状态为 `in_transit`，载荷写 `delivered`）→ 200、收件表多一行；异步处理回查 `getOrder` 得到真实 `in_transit` → **订单不变为 DELIVERED**、`shipments.provider_status` 仍为真实值。
3. 正确密钥 + 不存在的 `order_id` → 200 落表、处理时回查失败 / 找不到运单 → 留表待重试或记为无效（以 5.1 实现为准），订单无变化。
4. **脱敏**：在当日接口请求日志文件中 `grep -c '<BITESHIP_WEBHOOK_SECRET 真值>'` 结果必须为 0（路径密钥段已脱敏为占位）；同时 grep `BITESHIP_API_KEY` 真值为 0；应用日志里 Biteship 调用失败的 WARN 不含 key、地址、手机号（P-7）。
5. 常量时间比较：代码评审确认（`MessageDigest.isEqual` 或等价），记录类名与行号。

### L5 运单失败、重试告警与快递原因重发（补，E1）

1. 下运单失败（例：临时把 `BITESHIP_COURIERS` / 订单快递码改成测试环境不支持的值，或用无效收件邮编的单；只在本地库）→ 订单 `waybill_failed_at` 与 `waybill_fail_reason` 有值、订单仍 `PENDING_SHIPMENT`、App 不提示。
2. 5 分钟内全局重试 ≥ 10 次 → ERROR 日志 + 飞书机器人告警（本地为桩 / 日志）；少于 10 次不告警。
3. 快递原因重发：对一张已有运单的订单走「原订单新建运单」入口 → 旧运单 `superseded_at` 有值、新运单为最新；App 详情显示**最新**运单号与状态。
4. 手工运单（后台「标记已发货」手填，`provider=MANUAL`）→ App 物流卡只有快递名、运单号、「Lacak di situs kurir」，**不画 5 段**（截图）。

### L6 询价次数与报价复用（AC4，E1 + E2）

1. 清空本场景相关报价：`DELETE FROM shipping_quotes WHERE dest_postal_code='12730';`（本地库）。U1 购物车只放 SKU-1 ×1 且默认地址 A（12730）。
2. 依次：打开 SKU-1 详情（数量 1）→ 进购物车 → 去结算 → 在结算页切一次保价、换一次券（只重算不重新询价）。**核对**：`shipping_quotes` 对 (12730, SKU-1×1 的 items_hash, 发货邮编) 只有 **1 行**；应用日志中 Biteship `rates` 调用只有 **1 次**；Biteship 测试后台的 API 调用记录同为 1 次（能看到时记录）；结算响应的 `quoteToken` 即该行 token。
3. 改数量为 2 → 新的 items_hash → 新询价 1 次（第 2 行）；换地址 B → 新询价 1 次。
4. 30 分钟复用边界：`UPDATE shipping_quotes SET expires_at = now() - interval '1 minute' WHERE …;` 后刷新结算 → 重新询价一次；提交时若报价已过期 → 409 `quote-expired`，App 静默重新试算（7.1）。
5. 地址簿探测包裹（I10）：打开地址簿 → 对每个开通区邮编按「标准探测包裹」询价一次、24 小时复用（`shipping_quotes` 中探测包裹 items_hash 每个邮编 1 行）；未开通区（Tobelo）不询价直接 `serviceable=false`；把 Biteship key 临时改错 → `serviceable=null`、App 不标注（截图 H1）。

### L7 物流停滞、自动送达 14 天与异常排除（补，E1；时间用 SQL 回拨，只在本地库）

1. **停滞**：在途订单 `UPDATE shipments SET provider_status_at = now() - interval '4 days' WHERE id=:sid;` → 触发 `LogisticsStallJob`。**核对**：订单 `logistics_exception=STALLED`；App 不提示、仍显示最后物流状态。之后在 Biteship 后台推进一个新状态 → Webhook 处理后 `STALLED` 自动清除、`timer_resumed_at` 写入。
2. **自动送达 14 天**：无异常的 `SHIPPED` 单 `UPDATE shop_orders SET shipped_at = now() - interval '15 days' …` → 扫描后 `DELIVERED`（`delivery_source` 为自动兜底值）；同样拨 13 天的单不送达。
3. **异常排除**：带任一 `logistics_exception` 的单拨 15 天 → 扫描**不**送达；清除标记后以 `timer_resumed_at` 为起点重新计时（拨 `timer_resumed_at` 15 天前 → 才送达）。
4. 自动完成仍为送达后 7 天（拨 `delivered_at` 8 天 → `COMPLETED`；带异常的不完成）。

### L8 上线前检查（AC5；只列检查项与结果，生产数据查询交用户执行或经用户授权）

| 项 | 检查方法 | 结果 |
|---|---|---|
| Biteship 正式 key 已申请、**Order API 已开通** | 用户在 Biteship 后台确认（正式 key 只放生产 env `~/.env.petgo`，不经本仓库） | |
| 生产 Webhook 地址 | `https://api.tailtopia.id/api/v1/webhooks/biteship/<生产 secret>` 已在 Biteship 正式后台配置；生产 env 有 `BITESHIP_WEBHOOK_SECRET` | |
| 生产 env 齐全 | `BITESHIP_API_KEY`、`BITESHIP_MODE=live`、`BITESHIP_COURIERS`、`BITESHIP_WEBHOOK_SECRET`；`.env.example` 只有占位 | |
| 发货地信息 | `shipping_settings.origin_postal_code / origin_contact_name / origin_contact_phone / origin_address` 已由运营填写（生产只读查询由用户执行） | |
| 免运封顶 / 保价费率 | `free_shipping_cap`（空或 0 = 不设额外封顶）、`insurance_rate_bp = 50` | |
| 存量在售 SKU 包裹参数补齐 | `SELECT count(*) FROM shop_skus s JOIN shop_products p ON p.id = s.product_id WHERE p.is_active AND (s.package_weight_g IS NULL OR s.package_length_cm IS NULL OR s.package_width_cm IS NULL OR s.package_height_cm IS NULL);` = 0（否则这些 SKU 上线即「暂不可售」） | |
| 开通区域邮编补齐 | `SELECT count(*) FROM shipping_zones WHERE active AND postal_code IS NULL;` = 0（否则该区按不支持配送） | |
| 偏远地区邮编询价精度 | 抽 3 个偏远开通区邮编在测试 key 下询价，记录是否返回快递（架构 §3.6 第 3 条，上线后观察） | |
| 评价展示开关 | `platform_config` `shop.review.display-enabled` 保持 false，直到运营过完历史评价 | |
| 与后台线同批上线 | 券 / banner / 运单 / 退款打款 / 物流异常处理的后台页面已就绪（架构 §6.4） | |
| 商家返佣扣回遗留（S-5） | 记录为已知遗留：门店结算开始前须在商家仓补扫 `shop_order_refunds` | |
| 部署节奏 | 前后端同批；部署避开自动评论 10:30 / 16:30 窗口；部署前先 `clean` 打包 | |

## Tasks / Subtasks

- [ ] **T0** 前置检查 1 ~ 4（记录实际名字、Biteship 测试模式能力）
- [ ] **T1** E1 环境（sandbox env、临时隧道、测试后台回调配置）；E0 基线
- [ ] **T2** L1 ~ L7 逐场景执行、截图、贴 SQL 与日志（打码）
- [ ] **T3** L8 上线前检查表（生产相关项交用户执行 / 授权）
- [ ] **T4** 写入 `acceptance-v1.4.2-shop.md` 的「物流（10.2）」「上线前检查」两节；关闭隧道、删除测试后台回调配置、恢复临时改动的 env / 配置

## Dev Notes

### 现状（2026-11-05 核 `dev_1.4.2` @ 2c507755）

- 线上物流是**手工运单**：`shipments(carrier ∈ JNE/SICEPAT/ANTERAJA, tracking_no, carrier_cost, status, shipped_at, delivered_at)`，后台 `POST /admin/shop/orders/{token}/ship` 手录（架构 §1.2 第 10 条）；`AUTO_DELIVER_AFTER = 7` 天（`ShopOrder.java`:310-313，5.1 改 14 天）；扫描在 `ShopOrderExpiryScanner`（自动送达 / 完成每 10 分钟）。本线 5.1 / 5.2 新增 Biteship 客户端、Webhook、映射、停滞任务与异常列——本 story 只验收。
- 请求日志全接口落盘（架构 §1.2 第 25 条），含路径密钥的 URL 必须脱敏（5.1 实现，本 story L4.4 验证）。
- App 物流展示在 9.1（`shop_order_detail_page_v3.dart` 物流卡、5 段状态、F10）。

### 纪律

- 不新增功能；缺陷修在 `feat/1.4.2-shop` 并在对应 story 记一笔。
- Biteship 测试 key、Webhook secret 只在本机 env；验收记录、截图、日志摘录里一律打码。
- 本机临时隧道只开验收期间，结束即关；Biteship 测试后台的回调地址随之删除（避免测试平台继续往失效地址推）。
- 生产：只出检查表，不登录、不查询；需要生产数据时由用户执行或明确授权。

### References

- epics Story 10.2；决策日志「按推荐定下」、I10、I14
- 架构 D-9、D-10、D-12、D-13、D-24、P-7、§1.2 第 10 / 25 条、§3.6、§6.4
- PRD §3.0 旅程 D、§3.10、§4（`toko_delivery_exception_*`）
- 10.1 的环境与前置数据、`acceptance-v1.4.2-social-topic.md`

## 验收记录模板（追加到 `acceptance-v1.4.2-shop.md`）

```markdown
## 物流（10.2）
环境：本地后端 BITESHIP_MODE=sandbox（测试 key，已打码）；Webhook 经临时隧道 <已关闭>；Biteship 测试后台手动推进：支持 / 不支持（不支持时以 L1 集成测试为准：<测试类名>）。

| 场景 | 结果 | 证据（截图 / SQL / 日志摘录，已打码） | 备注 |
|---|---|---|---|
| L1 下运单（运单号、仍待发货、不重新询价、重复下单行为） | | | |
| L2 allocated → picked → in_transit → dropping_off → delivered → 确认收货 | | | |
| L2 重复 Webhook 幂等 | | | |
| L3 courier_not_found（用户不提示）/ returned 未确认不提示 / 确认后 F10 / 异常下确认收货 / claim_pending | | | |
| L4 错误密钥 404 / 篡改载荷不改状态 / 不存在 order_id / 日志密钥脱敏 / 常量时间比较 | | | |
| L5 运单失败 / 重试告警阈值 / 快递原因重发 / 手工运单不画 5 段 | | | |
| L6 三处一次询价 / 改数量与换地址重新询价 / 30 分钟过期 / quote-expired / 地址簿探测包裹 24h | | | |
| L7 停滞标记与自动清除 / 14 天自动送达 / 异常排除与重新计时 / 7 天自动完成 | | | |

### 状态推进明细
| 步 | Biteship 状态 | Webhook 到达时间 | 处理结果（processed_at / attempts） | 订单状态 | App 显示（截图名） |
|---|---|---|---|---|---|

## 上线前检查
| 项 | 结果 | 由谁确认 / 日期 |
|---|---|---|
```

## 待确认 / 发现的规划冲突

1. **D-24 没有物流异常相关的 stag 入口**：只列了「下运单」。L3 的「运营确认退回 / 丢件破损」「清除标记」、揽收失败三种处置前的「取消旧运单」、L5 的「快递原因重发」都需要 5.1 / 5.2 的 service 入口（后台线才有页面）。建议 5.1 / 5.2 一并加进 `ShopStagController`；没有则这些子场景记阻塞。
2. **Biteship 测试模式能否手动推进订单状态未核实**：epics 写「沙箱控制台手动触发 Webhook」，架构 §1.3 也注明「Webhook 验签与重试机制公开文档未写」。若测试后台不能推进状态，由于后端**只信回查结果**（D-12），仅 POST 伪造载荷无法驱动状态变化——L2 的逐段推进只能以 L1 集成测试（stub `getOrder` 可指定返回状态）为准。需在 T0 用测试 key 实测确认；不支持时用 5.1 的「推进 stub 状态」stag 入口走 App 侧（该入口只在 `BITESHIP_MODE=stub` 可用，与 sandbox 不能同时开——两段分开跑）。
3. **本地开隧道接 Webhook 会把本机后端暴露到公网**（临时域名 + 路径密钥）。若用户不接受，本 story 需在 stag 执行（须用户下令部署 stag，并在 Biteship 测试后台把回调指向 stag 域名）。
4. **「三处只计 1 次询价」的前提**：详情页按「所选 SKU × 数量」询价，购物车按「已勾选的全部商品」询价，二者 items_hash 只有在购物车恰好只有该 SKU 且数量一致时才相同。L6 按此前提造数；一般情况下三处会是不同包裹、分别询价（符合 D-9 复用键设计，不是缺陷）。
5. **生产相关检查项**：按「生产只读也不碰」纪律，本 story 只产出检查表，SKU 包裹参数 / 区域邮编 / 发货地的生产查询由用户执行或授权后执行。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev）。
