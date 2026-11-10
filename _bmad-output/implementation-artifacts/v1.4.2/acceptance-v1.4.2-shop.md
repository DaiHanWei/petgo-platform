# V1.4.2 shop 本地联调记录（2026-11-06，进行中）

验收对象：`dev_1.4.2` @ `fd94b3ffa`（PR #49 合入后）+ 下文未提交修复。环境全部在本机：后端 8082（`dev,stag`、`PAY_MODE=stub`、`BITESHIP_MODE=stub`、全新空库 `petgo_l2_142shop`、REDIS_DB 11）；App 为 Android 模拟器调试包。未使用 stag，未动生产。story 写的是 `feat/1.4.2-shop` 修缺陷，该分支已合并，修复落在 `dev_1.4.2`。

## L0 / L1

| 项 | 结果 |
|---|---|
| `flutter analyze` | 零问题 |
| `flutter test` | 4919 过、3 跳过（修复前全量）；修复涉及的模型测试文件复跑 6 过 |
| 全量 `mvn -B clean verify`（新库，修复前） | 8754 例，5 failures + 2 errors。5 个为本线测试写死 `vetBearer(1L)`，已修，受影响 5 个类复跑全过；2 个 `AdminPublishIdentityDrawerIntegrationTest` / `AdminVirtualDisplayLevelIntegrationTest` 为 `nanoTime` 昵称过长（1.4.0 旧账，social-topic 验收已记，非本线） |
| 空库迁移 | 277 条迁移到 `20261105.1650`，`ddl-auto=validate` 通过 |
| 修复后全量 L1 | **未重跑**，待提交后由 CI 确认 |

## 场景结果（接口断言共 126 项，金额期望值按决策规则独立计算）

| 场景 | 结果 | 备注 |
|---|---|---|
| S1 主链路（接口） | ✅ | 券 LOCKED→USED、运费负数存储、成长值入账；quoteToken 不变；不可配送不可提交 |
| S1（模拟器） | ✅ | 立即购买 → 结算 → 支付面板 → 成功页 → 详情，修复详情金额缺行后通过；领券卡、换地址、换券、关 PawCoin 的 UI 操作未逐项点 |
| S2 立即购买（接口） | ✅ | 预览与下单都不动购物车；来源 BUY_NOW |
| S3 纯 PawCoin | ✅ | 财务看板口径未核 |
| S4 超时 / 宽限 / 关单 / 10 分钟出码 | ✅ | UNKNOWN 不关单引用集成测试 |
| S5 迟到款 | ✅ | 重新下单、原券占用拒绝、余额不足拒绝、重复拒绝、退款无补偿无扣回。多退少补由 `LatePaymentIntegrationTest` 覆盖：网关实收恒等于现金段，联调造不出来 |
| S6 退款 | ✅ | 部分取消取整、末件转整单、线下打款、打款失败重填 / 改币、用户原因退回库存只加一次、签收后破损、补偿（比例与封顶）均核对。模拟器看过 F17、F25、退款方式页。S6.7 后台异常页未走页面 |
| S7 旧版 | ⚠️ | 只用不带 quoteToken 的请求验了接口；真实 1.4.1 包未跑 |
| S8 真机推送 | 待部署 | 需 stag，等指令 |
| E5 还原度 95 屏 | 未做 | 只抽看约 10 屏 |

## 物流（10.2，stag + Biteship 测试环境，2026-11-10）

环境：stag 后端（`BITESHIP_MODE=sandbox`，测试 key 只在 `~/.env.petgo-stag`），Webhook 直接配在 `api-stag.tailtopia.id`（未用隧道）。测试账号 user 115，PawCoin 全额支付，JNE REG + 保价。Biteship 测试后台的「Play Webhook」能真实推进测试订单状态（`getOrder` 回查结果同步变化），所以 L2 / L3 都由 Biteship 推进，不是 stub。stag 没配飞书群，告警只核对日志。App 只核对了接口返回，没在模拟器截图。

| 场景 | 结果 | 证据 / 备注 |
|---|---|---|
| L1 下运单 | ✅ | 拿到真实运单号、订单仍待发货、不重新询价；重复下运单 409；保价金额 = 商品实付 |
| L2 allocated → picking_up → picked → in_transit → dropping_off → delivered → 确认收货 | ✅ | TOKO-20261110-17MBF8：picked 后 SHIPPED（`BITESHIP_PICKED`），delivered 后 DELIVERED（来源 BITESHIP），用户确认后 COMPLETED（`USER_CONFIRM`） |
| L2 重复 Webhook | ✅ | 已完成订单再收 delivered：200、处理成功、订单不变 |
| L3 courier_not_found | ✅ | `PICKUP_FAILED`、订单仍待发货；App 详情 / 列表只显示待揽收 |
| L3 returned 未确认 / 确认后 | ✅ | 未确认前 App 只显示最后物流状态；运营判定「快递原因」后详情 `exception=DELIVERY_ISSUE`、列表 `deliveryIssue=true`、`claim_pending=true`。Biteship 后台要先到 dropping_off 才能选退回，所以多一条 dropping_off 事件 |
| L3 异常下确认收货 | ✅ | 另一单确认丢件（LOST）后用户确认收货 → COMPLETED、标记清空、`claim_pending` 保留、日志提醒运营核对 |
| L3 揽收失败三种处置 | ✅（修复后） | 见下方缺陷 5。修复后：转手工运单（本地取消旧单、不调 Biteship）、重下运单（Biteship 取消 200 + 新运单）、取消订单（取消旧单 + 整单退款，标记保留） |
| L4 伪造 Webhook / 脱敏 | ✅ | 错密钥 404 且不落表；篡改载荷（delivered）回查后订单不变；未知 order_id 记 `UNKNOWN_ORDER`，重试满 10 次停止；访问日志路径密钥打码，日志无 key；常量时间比较在 `BiteshipWebhookController`（`MessageDigest.isEqual`） |
| L5 运单失败 | ✅ | 改成不支持的快递码 → `REJECTED:40002031`，`waybill_failed_at` 有值、订单仍待发货、App 不提示 |
| L5 重试告警 | ✅ | 连续 11 次重试：第 10 次一条 ERROR「重下运单激增 count=10」，第 11 次不重复 |
| L5 快递原因重发 | ✅ | 新运单建成、旧运单 `superseded_at` 有值且保留、标记清除、`claim_pending` 保留 |
| L5 手工运单 | ✅ | `provider=MANUAL`，接口只有快递名、运单号、官网链接，无 `statusKey`（App 不画 5 段） |
| L6 询价 | ✅ | 详情 / 购物车 / 结算只计 1 次（11-06 本地已验，本轮未重跑） |
| L7 停滞 | ✅ | `provider_status_at` 与 `timer_resumed_at` 回拨 4 天 → 停滞检测标 STALLED（App 不提示）；另一单收到 Biteship 新状态 allocated → 标记自动清除、`timer_resumed_at` 写入 |
| L7 14 天自动签收 / 异常排除 | ✅ | 同一轮扫描：无异常单回拨 15 天 → DELIVERED（`AUTO_TIMEOUT`）；STALLED 单回拨 15 天 → 不签收；回拨 13 天的单不签收。运营清除停滞后 `timer_resumed_at` = 当时，回拨 15 天 → 签收 |
| L7 7 天自动完成 | ✅ | `delivered_at` 回拨 8 天 → COMPLETED。「带异常的不完成」本轮未造数据，以集成测试为准 |

注意：停滞清除后「从 `timer_resumed_at` 起算 72 小时」是设计如此——重发 / 揽收失败处置也会写这个时间，回拨停滞时要一并回拨。定时任务没有手动入口，本轮用重启 stag 容器触发（停滞检测启动后 5 分钟、签收扫描启动即跑）。

### 本轮新增缺陷（已修、已合 `dev_1.4.2` 与 stag 并部署）

5. **Webhook 安装探测失败**：Biteship 后台添加 Webhook 时发空体 POST，要求 200，原实现回 400 → 无法安装。已改空体回 200（`cdf44c3f6`）。
6. **揽收失败的单无法处置**：Biteship 对 `courier_not_found` 的运单调取消回 400，而三种处置都要求先取消旧运单 → 全部 409。已改为 `cancelled` / `courier_not_found` 视为 Biteship 已作废、只落本地（`1183599f9`）。

## 缺陷（均未提交）

1. **App 订单详情缺免运减免行**：`shippingDiscount` 接口为负数，App 判 `> 0`，明细加起来对不上实付；取消弹窗「券会退回」判断同因。测试假数据写成正数所以 L0 未发现。已在模型解析处统一。
2. **收款账号明文写入接口日志**：`account` / `accountHolderName` / `payoutAccount` 不在脱敏名单。已修，并实测日志无账号与户名。旧接口 `FillPayoutRequest`、退货 `CashDestinationRequest` 同样受影响，**线上历史日志可能已有明文**。
3. **stag 入口传空操作人**：`actor=null → 0L` 违反外键，取消与入库 500。已改传当前管理员。真实后台入口不受影响。
4. 本线 5 个测试写死兽医 id，随测试顺序时红时绿。已改 `activeVetBearer()`。

## 上线前检查

- `compensation_premium_rate` 默认 0：不配置时选 PawCoin 没有 bonus，App 也不显示。
- SKU 没有采购入库记录时，取消已付订单被 422 拦住，退款走不下去；上线前核对全部在售 SKU。
- C-12（GemPay 过期查单返回）未做。

### 物流上线前检查（10.2 L8，2026-11-10；生产未查询，生产项交 Dai）

| 项 | stag 现状 | 生产需要做 |
|---|---|---|
| Biteship 正式 key、Order API 开通 | 测试 key 可下单 / 取消 / 回查 | Dai 在 Biteship 正式后台确认，key 只写入 `~/.env.petgo` |
| 生产 env | stag 有 `BITESHIP_MODE` / `API_KEY` / `WEBHOOK_SECRET` | 补 `BITESHIP_MODE=live`、`BITESHIP_API_KEY`、`BITESHIP_WEBHOOK_SECRET`；不配时结算不可用 |
| 生产 Webhook | stag 已配（事件 `order.status`） | 正式后台添加 `https://api.tailtopia.id/api/v1/webhooks/biteship/<生产 secret>`；需先部署含空体修复的版本，否则安装失败 |
| 发货地信息 | 已填（邮编 10110、联系人 / 电话 / 地址） | 运营在生产后台填写 |
| 免运门槛 / 封顶 / 保价费率 | 门槛 0（stag 测试值）、封顶 50000、费率 50bp | 门槛改回 200.000 |
| 在售 SKU 包裹参数 | **80 个在售 SKU 缺**（上线即「暂不可售」） | 生产按同一 SQL 核对并补齐 |
| 开通区域邮编 | 6 个开通区全有邮编（stag 已改为真实邮编） | 生产核对 `postal_code IS NULL` = 0 |
| 评价展示开关 | `review_display_enabled=false` | 保持关，运营过完历史评价再开 |
| 偏远地区询价精度 | 本测试账号只有 JNE 返回报价 | 上线后观察 |
| 运营告警 | stag 未配飞书群，只写日志 | 生产配 `LARK_SHOP_*` |
| 部署节奏 | — | 前后端同批；避开 10:30 / 16:30 自动评论；`clean` 打包 |
- 桩网关 `queryCharge` 固定 EXPIRED，本地点「我已付款」会把意图置 EXPIRED；真网关 `pending` 不推进，属桩行为。
