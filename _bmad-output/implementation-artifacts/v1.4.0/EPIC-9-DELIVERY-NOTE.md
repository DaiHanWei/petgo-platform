# Epic 9 交付说明（V1.4.0 精选自营电商）

> 本文件是 **Story 9.3 的 AC 交付物**：显式记录「本版本不适用 / 不在范围内」的护栏项，
> **避免下游误以为遗漏**。各节相互独立，验收时逐节勾。

---

## §1 NFR-4 —— 凭证 env 注入、绝不入库

🔴 **本版本不引入任何新的第三方凭证**，故**无新增 `.env` 项**。

核实方式与结果：

| 检查 | 结果 |
|---|---|
| 是否接承运商 API？ | **否**。FR-103 明确「App 只做跳转到承运商官网」，不接 API、不在 App 内渲染物流轨迹 |
| 是否接新支付渠道？ | **否**。沿用既有 QRIS / PawCoin 两段，`PaymentIntent` 只做 channel 扩展（Story 3.1） |
| `shop/` 与 `admin/shop/` 下是否有 `System.getenv` / 新 `@Value` 凭证？ | **无**。全部 `petgo.shop.*` 属性都是**调参**（SKU 上限、低库存阈值、扫描间隔、年龄段边界），带代码内默认值，非机密 |
| `.env.example` 是否变更？ | **未变更** |

⚠️ **既有凭证约束不变**：凭证全部 env 注入、绝不入库，`.env.example` 只放占位。
（唯一与「凭证」沾边的新增是 `shipping_settings` 的退款收款账号列 —— 那是**运营配置的商户账号**、
按 D1 加密存储，不是第三方 API 凭证，不适用 NFR-4 的 env 注入条款。）

---

## §2 NFR-12 —— 验证层级逐条落地

🔴 **已逐条落在全部 57 条 story 的 AC 上**，本 story 复核无遗漏。

| 层级 | 含义 | 本版本执行状态 |
|---|---|---|
| `[L0]` | 静态：`flutter analyze` / `flutter test` / `mvn -B package` | ✅ 前端 `analyze` 零告警 + 953 例全绿；后端 1991 例 / 0 失败 / 6 跳过（**BUILD SUCCESS**） |
| `[L1]` | Docker postgres + redis 真跑 | ✅ 各 Epic 联调 story 均有 `*ChainIntegrationTest` |
| `[L2]` | 真机 · 模拟器视觉 · 真实第三方凭证 | ⏳ **全部待本地人工** —— 云端 headless 做不到 |

### ✅ 全量跑时发现并已修掉的一个既有缺陷（NOTIFY-CURSOR-TIE）

`NotificationControllerEndpointTest.list_paginatesWithCursor` 在某一轮全量跑时红了一次，
单跑与复跑均绿。**这不是 flaky 测试，是一个真实的分页丢数据缺陷**，测试只是偶尔撞上：

游标是 **`created_at` 截断到毫秒**、查询是严格 `created_at < cursor`。
🔴 **同一毫秒内有 ≥2 条通知、且分页边界正好落在中间时，那一毫秒里的记录会被整批跳过** ——
用户永久看不到那几条通知。一毫秒内写入多条通知在生产上完全正常（一次批量触达就是）。

**已修**（2026-08-18，经用户明确要求跨模块动手）：
- 游标改 `(created_at, id)` 复合，`ORDER BY created_at DESC, id DESC`
  （顺带修掉第二个问题：同刻记录原本**没有确定顺序**，翻页时同一条会重复或消失）
- 游标编码按**微秒** —— ⚠️ 截到毫秒会让复合分支因精度失配而恒不命中，等于没修
- `V125` 补配套索引 `(recipient_user_id, created_at DESC, id DESC)`
- 回归：`list_doesNotSkipRowsSharingTheSameInstant`（**把 5 条时间戳写成同一个值，
  让缺陷从「偶发」变成「必现」**）+ `list_toleratesGarbageCursor`；两次变异验证先红后绿

🔴 **wire 格式变了**：`nextCursor` 由「纯 epochMillis」→ `"<epochMicros>_<id>"`。
对客户端是**不透明串**（Flutter 侧只原样回传，已核对不解析），服务端另留一轮过渡兼容。

⚠️ **同一类缺陷的兄弟没修**：`OrderCenterService#listOrders` 也是「epochMillis + 严格 `<`」，
但它是**三线共享**文件且为跨 3 源 in-memory 归并，`(created_at, id)` 不直接成立
（id 来自不同表，需先定跨源全序键）。记为 `ORDER-CENTER-CURSOR-TIE`，须先认领再动。

### 主动留白的两条（不是遗漏）

理由写在各自 story 里：
- Story 5.9 退货进度页 —— UX-DR5 无视觉稿，**实现前不得自行发挥**
- Story 7.2 评价页 —— UX-DR4 无视觉稿，同上

⚠️ 这两条不是遗漏，是**按纪律主动留白**。视觉稿到位即可开工，后端接口都已就位。

---

## §3 UX-DR12 —— Toko Tab 图标常态/激活态

🔴 **本版本不实现。**

- 归 **DEP-2**（设计未交付）：两态图标没有稿。
- 归 **DEP-1**（未拍板）：Tab 位序未定，`AppTab` 枚举 4 值**无空位**。

🔴 **两项闭合前不得改 `bottom_tab_bar.dart`**（并行契约 C 类文件）。
当前 Toko **只挂路由 `/shop`、不占 Tab 位** —— 这是有意的，不是忘了接。

> ⚠️ 连带后果见 §4：Tab 没接，基线也就无从谈起。

---

## §4 ⚠️ 基线埋点缺口（本版本无法弥补，必须知情）

**电商 Tab 替换既有 Tab 时，被替换 Tab 的曝光基线，须在【前一个版本】先行发布才有对比。**
本版本发布时，那个基线**不存在**。

后果，说明白：

- 上线后能看到 Toko 的绝对数（曝光、加购、下单、转化）。
- **看不到**「用一个 Tab 位换来的东西，比原来那个 Tab 值不值」。
- 🔴 **不要**拿上线后的 Toko 数据去和「凭印象记得的」旧 Tab 数据比 —— 那个对比不成立。

该项归 **DEP-1**，不在本版本 Epic 范围内。

> **L-6 前车之鉴：** V1.1.2 把埋点集中为收尾 story 且与改版同版本发布，
> 导致三项核心指标不可得、PRD 的「唯一裁决指标」与处置原则一并失效。
> 本版本已把功能埋点**分散进各 Epic 的 AC**（砍不掉），但基线这一项是**时间顺序问题**，
> 分散实现解决不了 —— 只能靠提前一个版本发布。**记在这里，别让它变成第二次 L-6。**

---

## §5 看板事件名对照（配 PostHog 时用这一列）

AC 里的口径名与代码里的实际名不同（埋点命名守卫要求「模块前缀 + 对象 + 动作词尾」）。
**权威对照表是 `test/analytics/v140_events_inventory_test.dart` 的 `inventory` 常量** ——
它是测试断言的输入，代码改名会立刻红，不会像文档那样漂移。

高频三条先列在这里：

| 口径名 | 实际事件名 |
|---|---|
| `order_submitted` | `toko_order_submitted`（带 `items[]` 行级归因） |
| `payment_succeeded` | `toko_order_payment_succeeded`（带 `attribution_source`） |
| `push_permission_revoked` | `notify_push_permission_toggled`（`enabled=false` 即撤销） |

---

## §6 上线后建议立刻挂的两个告警

| 指标 | 涨得快意味着 |
|---|---|
| `triage_category_jump_tapped` 占比 | 🔴 **问诊与商品的边界在被侵蚀**（FR-110 / decision-log N-3） |
| `notify_push_permission_toggled(enabled=false)` | 🔴 **推送在把人推走** —— 这是终点信号，到这一步就没有第二次机会 |
