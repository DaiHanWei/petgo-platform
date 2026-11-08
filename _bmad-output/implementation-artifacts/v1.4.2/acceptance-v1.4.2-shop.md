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

## 物流（10.2，全部 stub，非 Biteship 沙箱）

| 场景 | 结果 | 备注 |
|---|---|---|
| L1 下运单 / L2 逐段 / 确认收货 | ✅ | 运单不改状态、不重新询价、重复拒绝（409） |
| L3 揽收失败 / 退回 | 部分 ✅ | 标记与用户不提示已验；运营确认无 stag 入口，App F10 未看，靠 `LogisticsExceptionIntegrationTest` |
| L4 伪造 Webhook | ✅ | 错密钥 404、篡改载荷不生效、未知单留表；日志无密钥 |
| L5 / L7 | 部分 | stub 不会失败；停滞与自动送达靠集成测试 |
| L6 询价 | ✅ | 详情 / 购物车 / 结算只计 1 次 |
| 沙箱实测、保价费字段 | 阻塞 | 本机没有 Biteship 测试 key |

## 缺陷（均未提交）

1. **App 订单详情缺免运减免行**：`shippingDiscount` 接口为负数，App 判 `> 0`，明细加起来对不上实付；取消弹窗「券会退回」判断同因。测试假数据写成正数所以 L0 未发现。已在模型解析处统一。
2. **收款账号明文写入接口日志**：`account` / `accountHolderName` / `payoutAccount` 不在脱敏名单。已修，并实测日志无账号与户名。旧接口 `FillPayoutRequest`、退货 `CashDestinationRequest` 同样受影响，**线上历史日志可能已有明文**。
3. **stag 入口传空操作人**：`actor=null → 0L` 违反外键，取消与入库 500。已改传当前管理员。真实后台入口不受影响。
4. 本线 5 个测试写死兽医 id，随测试顺序时红时绿。已改 `activeVetBearer()`。

## 上线前检查

- `compensation_premium_rate` 默认 0：不配置时选 PawCoin 没有 bonus，App 也不显示。
- SKU 没有采购入库记录时，取消已付订单被 422 拦住，退款走不下去；上线前核对全部在售 SKU。
- C-12（GemPay 过期查单返回）、C-13（存量 SKU 包裹参数与区域邮编）、Biteship 正式 key 与 Webhook 均未做。
- 桩网关 `queryCharge` 固定 EXPIRED，本地点「我已付款」会把意图置 EXPIRED；真网关 `pending` 不推进，属桩行为。
