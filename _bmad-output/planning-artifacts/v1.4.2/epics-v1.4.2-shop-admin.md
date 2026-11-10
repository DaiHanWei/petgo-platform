---
version: v1.4.2
theme: shop-admin
status: complete
date: '2026-11-06'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商后台prd.md
  - _bmad-output/planning-artifacts/v1.4.2/电商/ui-admin-1-4-2-shop-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-admin-delta.md
  - _bmad-output/planning-artifacts/v1.4.2/决策日志-shop-admin.md
---

# V1.4.2 shop-admin · Epics 与 Stories

> 9 Epic / 20 Story。Epic 1~8 可交云端，Epic 9 联调留本地。验证层级：〔L0〕编译与纯单测；〔L1〕真库集成；〔L2〕真机与第三方沙箱。云端只验 L0，L1 测试照写。

## 需求覆盖

| 需求 | Story |
|---|---|
| 导航、待办中心、审计、confirm-modal、提醒通道 | 1.1 ~ 1.3、5.3 |
| AB-24 优惠券 | 2.1 ~ 2.3 |
| AB-25 banner | 3.1 |
| AB-27 分享奖励 | 3.2 |
| AB-39 评价 | 3.3 |
| AB-38 运费页、余额、SKU | 4.1、4.2 |
| AB-28 异常订单、退款打款 | 5.1、5.2、5.3 |
| AB-38 履约、物流异常 | 6.1、6.2 |
| AB-28 订单页、取消后到账 | 7.1、7.2 |
| AB-38 对账 | 8.1 |
| AB-29 复购下线 | 1.1 |

## UI 屏覆盖

A1~A10 → 2.2；A11~A13 → 2.3；B1~B6 → 3.1；C1~C2 → 3.2；D0 → 5.3；D1~D8 → 5.1；E1~E5 → 5.2；F1、F7 → 7.1；F2~F6 → 7.2；G1~G4 → 4.1；G5~G6 → 4.2；H1~H7、H13 → 6.1；H8~H12 → 6.2；I1~I2 → 8.1；J1~J6 → 3.3。

---

## Epic 1：后台横切地基

运营后台具备新导航、统一的确认弹层和提醒通道，复购看板下线。

### Story 1.1：导航目录、权限码与复购下线

As a 运营,
I want 商城菜单按新结构排列、过期的复购页消失,
So that 后续页面有落点。

**Acceptance Criteria:**

**Given** 超管登录 **Then** 商城组出现「订单」（`/admin/shop/order-list`，订单履约前）、「退款打款」（`/admin/shop/refund-payouts`，退货审核后）、「优惠券」（`/admin/shop/coupons`，顶部 Banner 后），三页先渲染占位页〔L1〕
**And** 新增权限码 `shop.coupon_view`、`shop.coupon_edit`，四份 properties 补文案，不预授〔L0〕
**And** 无权限账号看不到对应菜单，直访 403〔L1〕
**And** `/admin/shop/repurchase-dashboard` 返回 404；Controller、Service（无其他调用方时）、模板、Catalog 项、文案删除〔L0〕
**And** 商品编辑页不再显示「未填每日建议喂量」警告与喂量卡顶部提示；角色页脚注去掉复购那句；喂量字段保留〔L1〕
**And** `AuditActions` 按 PRD 文档头表补全动作码〔L0〕
**And** `AdminPageCatalogTest`、`AdminPagesRenderSmokeTest` 同步更新〔L0 / L1〕

### Story 1.2：confirm-modal 扩展与商城页替换

As a 运营,
I want 二次确认能列金额明细和选项,
So that 涉钱操作前看得清。

**Acceptance Criteria:**

**Given** `AdminConfirmModal` **Then** 新增可选的金额明细块（标签左、金额右、合计行）、单选组、下拉，未传时渲染与现状一致〔L0〕
**And** 架构 §1.1 第 7 条列出的 10 处 `data-confirm` 全部改为 confirm-modal，原有警示语义不丢〔L1〕
**And** 商城模板内不再出现 `data-confirm`（源码扫描测试钉住）〔L0〕
**And** `admin.css` 新增 `.kv.amt`、汇总格语义色、页签红色计数、队列来源徽标、金额列不换行、tabular-nums、全局字体抗锯齿〔L0〕
**And** 被替换页面业务行为不变〔L1〕

### Story 1.3：运营提醒通道

As a 客服 / 运营 / 技术,
I want 需要人处理的事件发到各自飞书群,
So that 不用盯着后台。

**Acceptance Criteria:**

**Given** `OpsAlertService.send(channel, dedupeKey, text)` **Then** 三个 channel 各读 `LARK_SHOP_{CS,OPS,TECH}_WEBHOOK_URL` / `_SECRET`，空值只写 WARN〔L0〕
**And** 事务提交后才发送，事务回滚不发；发送失败不影响业务〔L1〕
**And** 同 `dedupeKey` 只发一次（迁移 `V20261106_1000`）〔L1〕
**And** 文案不含手机号、地址、收款账户（测试断言）〔L0〕
**And** 电商线告警按调用点分流：技术类走 TECH，运营类（确认收货、运单取消失败）走 OPS〔L0〕
**And** `.env.example`、runbook 登记新 env〔L0〕

---

## Epic 2：优惠券管理（AB-24）

### Story 2.1：券后台服务补齐与状态流水

As a 运营,
I want 编辑券、看数据、查流水的后端能力,
So that 页面有东西可调。

**Acceptance Criteria:**

**Given** 发放期未开始的券 **When** 编辑 **Then** 可改全部字段；已开始的只能加总量，减量或改其他字段拒绝〔L1〕
**And** 校验：固定有效期结束日不早于发放期结束日；免运券最高减免必填；发放期必填；新人券发放期不重叠〔L0 / L1〕
**And** 面额 ≥ 门槛硬拒（O-14）；防误配：门槛为 0、总量不限且每人不限，未带 `acknowledgeRisk` 时返回风险列表，不落库〔L0〕
**And** `listForAdmin` 现算状态（未开始 / 进行中 / 已结束 / 已停用），可按状态、类型、发放方式筛选〔L1〕
**And** `detailStats` 返回已领、占用中、已使用、带来的订单金额〔L1〕
**And** `user_coupon_events` 只由 `CouponLedgerService` 写，发放、锁定、退回、使用、作废各一行；源码扫描钉住单入口〔L0 / L1〕
**And** 通用码口径：总量 = 全部用户合计可兑换张数；批量码生成数量 = 总量，加总量时补生成差额〔L1〕
**And** 建、改、加量、停用、作废、生成码写审计；作废记张数，生成码记数量不记明文〔L1〕

### Story 2.2：优惠券页

As a 运营,
I want 在后台建券、停券、看数据,
So that 不用改 SQL。

**Acceptance Criteria:**

**Given** 优惠券页 **Then** 列表列、摘要条、筛选、空态按 A1、A2〔L1〕
**And** 新建抽屉按券类型与发放方式变化，三态按 A3~A5；保存后回列表定位新行〔L1〕
**And** 校验失败抽屉顶部报错、字段红框（A6）〔L1〕
**And** 防误配 confirm-modal（A7，风险项按 O-14 调整）〔L1〕
**And** 已开始发放的券只读，只改总量（A8）〔L1〕
**And** 停用 confirm-modal 二选一继续可用 / 作废（A9）；待支付占用的券照旧可用于该订单〔L1〕
**And** 详情抽屉四格数据，批量码可导出 CSV（A10），导出要 `shop.coupon_edit`，审计记数量〔L1〕
**And** 无 `shop.coupon_edit` 只读〔L1〕

### Story 2.3：用户详情优惠券页签

As a 客服,
I want 按用户查券,
So that 能回答「券为什么没退回」。

**Acceptance Criteria:**

**Given** 用户抽屉 **Then** 9 个页签保留，末尾加「优惠券」，只读，列按 A11〔L1〕
**And** 点一行展开状态变化记录；占用或已使用显示订单号，可点进订单〔L1〕
**And** 空态 A12〔L1〕
**And** 用户被兑换锁定时顶部显示剩余分钟，无解锁按钮（A13）〔L1〕
**And** 不提供修改券或补发入口〔L0〕

---

## Epic 3：banner、分享奖励、评价

### Story 3.1：banner 管理扩展（AB-25）

As a 运营,
I want 多张 banner 排期和拖动排序,
So that 能安排活动。

**Acceptance Criteria:**

**Given** banner 列表 **Then** 权重列改拖动手柄，新增排期列，「原始尺寸」列保留（B1）〔L1〕
**And** 六种状态由纯函数算出，与 App 取数共用生效判定；超 6 张的标超出展示上限，目标失效自动标记〔L0〕
**And** 拖动写回 `sort_weight`，App 顺序随之变化〔L1〕
**And** 抽屉去权重、去品类，加优惠券类型（只列用户领取券）与上下线时间（B3）〔L1〕
**And** 存量品类 banner 编辑须改选，未改选不能保存，服务端同样拒绝（B4）〔L1〕
**And** 目标无效保存失败（B5）〔L1〕
**And** 尺寸说明常驻；比例偏差超 ±3% 提醒、不拦截（B6）〔L0〕
**And** 修「编辑」按钮文字与抽屉标题两个小 bug〔L1〕
**And** 排序、排期、跳转目标改动写审计〔L1〕
**And** 空态 B2〔L1〕

### Story 3.2：分享奖励资金提醒（AB-27）

As a 运营,
I want 改奖励时看到真钱风险和估算,
So that 不误配。

**Acceptance Criteria:**

**Given** 分享奖励卡 **Then** 顶部常驻警示（C1）〔L1〕
**And** 迁移 `V20261106_1010` 只改列默认值，**不改现有行**（断言迁移无 UPDATE）〔L0〕
**And** 改奖励金额或月度上限保存时 confirm-modal 列新旧值与估算（C2）〔L1〕
**And** 估算 = min(月度上限, Σ(每次发放 × 每日上限) × 当月天数)，纯函数单测含大小月〔L0〕
**And** 配置变更日志照旧〔L1〕

### Story 3.3：商品评价管理（AB-39）

As a 运营,
I want 隐藏违规评价并控制何时展示,
So that 历史评价先过一遍再上线。

**Acceptance Criteria:**

**Given** 商品编辑页 **Then** 加「商品信息 / 评价」页签，原表单不动（J1）〔L1〕
**And** 评价列表含已隐藏、分页；空态 J2〔L1〕
**And** 隐藏须选原因（J3），恢复须确认（J4），无删除入口〔L1〕
**And** 隐藏后 App 不显示，均分条数重算〔L1〕
**And** 运营配置「商品评价展示」开关默认关，打开须确认（J5、J6），权限 `config.edit`〔L1〕
**And** 隐藏、恢复、开关写审计；不通知用户〔L1〕

---

## Epic 4：运费与包裹数据（AB-38）

### Story 4.1：服务范围与运费页

As a 运营,
I want 配发货地址、免运封顶和 Biteship 余额预警,
So that 询价下单不中断。

**Acceptance Criteria:**

**Given** 运费页 **Then** 新增发货地址卡、Biteship 账户卡，区域表去运费列，三张原卡保留（G1）〔L1〕
**And** 区域新增 / 编辑可填邮编〔L1〕
**And** 发货地址未配置页顶报错（G2）〔L1〕
**And** 门槛 > 0 而最高减免留空不能保存（G4），服务端同样拒绝〔L1〕
**And** 预估余额 = 上次录入 − 此后未取消 Biteship 运单的运费与保价，纯函数单测含已取消、未录入〔L0〕
**And** 低于阈值页顶警示、数字标红（G3），飞书提醒运营，同一次录入只提醒一次〔L1〕
**And** 重新录入后从新数重扣，记录入值、录入时预估值、录入人、时间〔L1〕
**And** 发货地址、最高减免、余额录入、阈值写审计；保存走 confirm-modal〔L1〕

### Story 4.2：SKU 包裹数据

As a 运营,
I want 在商品页补重量体积,
So that 上线前存量 SKU 可售。

**Acceptance Criteria:**

**Given** 商品编辑 **Then** SKU 表加重量、长宽高，表单四项必填，未填不能保存（G6）〔L1〕
**And** 改动进审计〔L1〕
**And** 商品列表加「包裹数据」筛选、摘要、缺失徽标（G5）〔L1〕
**And** 现有 SKU 保存流程不受影响，退货规则、进货价列保留〔L1〕

---

## Epic 5：异常订单与退款打款（AB-28）

### Story 5.1：异常订单队列、原因类别与退款试算

As a 运营,
I want 所有要处置的订单进一个队列、取消时明确原因,
So that 钱退得对。

**Acceptance Criteria:**

**Given** 队列 **Then** 收缺货、运单失败、揽收失败、退回、丢件破损（含已签收 / 已完成且无赔付单），标来源，优先级按 AD-9（D1）；空态 D2〔L0 / L1〕
**And** 取消后的订单出队，运单失败标记随取消清除；「联系用户后继续」只对缺货来源显示〔L1〕
**And** `confirmReturned` 落库 `return_cause`，判定后不可改；重发要求快递原因〔L1〕
**And** 类别映射与绑定由服务端校验，不符 409，运营不能单独开关补偿与退券；退回待判定不能取消（表驱动单测）〔L0〕
**And** 整单取消先选类别，显示试算明细；试算与落库同一计算（D3~D5）〔L0 / L1〕
**And** 平台原因全额、给补偿、退券；用户原因扣用户实付运费与保价、不补偿、不退券；丢件破损按保价或协商额，协商额 ≤ 商品实付〔L0〕
**And** 此前部分取消已退的，各类退款都减去已退额〔L0〕
**And** 用户原因须 `return_inbound_at` 非空，否则按钮置灰、服务端 409（D4）；取消时直接出退款单、不回补库存〔L1〕
**And** 已签收 / 已完成的丢件破损走 `compensate`，订单状态不变〔L1〕
**And** 揽收失败取消走 `resolvePickupFailureByCancel`，旧运单取消失败则中止、不回补库存〔L1〕
**And** 部分取消显示拆分明细（D6）；最后一行转整单，类别只能平台原因（D7）；有活动运单时拒绝〔L0 / L1〕
**And** 处置成功 toast 含现金段去向，队列刷新（D8）〔L1〕
**And** 审计记类别、退款合计、币现拆分、退券、库存回补（门店返佣按 O-23 不记）〔L1〕

### Story 5.2：退款打款页

As a 财务,
I want 集中看待退现金并回填打款,
So that 查得到钱打了没有。

**Acceptance Criteria:**

**Given** 退款打款页 **Then** 五个页签、列、摘要按 E1；空态 E2；不含退货单〔L1〕
**And** 待用户选择超 7 天标记，每日任务发客服提醒（每张一次），不推送用户〔L1〕
**And** 标记已打款填流水号、可传截图（E3），用户侧变已退款，成长值此时扣回；流水号重复提示不拦截〔L1〕
**And** 打款失败选原因（E4），提醒客服，不推送用户；用户重填回待打款〔L1〕
**And** 操作人是发起人时置灰（E5），服务端 403，超管不豁免，被拦截也写审计且不随事务回滚〔L1〕
**And** 列表账号打码、户名显示；抽屉完整账号只给 `refund.payout`；审计只记末 4 位；不进日志〔L1〕
**And** 截图私有存储，签名 URL 查看限 `refund.payout`，限图片 ≤10MB〔L1〕
**And** 注销用户的退款单单列页签，不计角标，线下结案须填说明，受职责分离约束，写审计（O-17）〔L1〕
**And** 无 `refund.payout` 只读〔L1〕

### Story 5.3：待办中心商城待办

As a 运营,
I want 待办中心看到商城待处理数,
So that 不漏。

**Acceptance Criteria:**

**Given** 有权限账号 **Then** 待办中心末尾显示「商城待办」两项，点击跳商城页（D0）〔L1〕
**And** 计数口径按 PRD，并入 `/admin/nav/badges` 与组角标，注销用户线下处理不计入〔L1〕
**And** 无页面权限不渲染、不计入〔L1〕

---

## Epic 6：订单履约与物流异常（AB-38）

### Story 6.1：履约抽屉 Biteship 下单与失败处置

As a 运营,
I want 在抽屉里下运单并处理失败,
So that 不再手工录单。

**Acceptance Criteria:**

**Given** 待发货订单 **Then** 吸底「发货」换成「Biteship 下单」，显示快递、运费、保价（H2）；吸底判定是有单测的集中函数〔L0 / L1〕
**And** 下单成功回填运单与 `insurance_cost`，订单仍待发货，出现「标记已发货」（H3）〔L1〕
**And** 已发货显示来源、时间、操作人，只在后台可见（H4）〔L1〕
**And** 运单失败显示原因，三选一（H5）；「联系用户后取消」跳异常订单该单〔L1〕
**And** 揽收失败同三选一，执行前先取消旧运单，已发货且无活动运单也可重提，状态不变（H6）〔L1〕
**And** 结果未知时重提须先核实，confirm-modal 确认〔L1〕
**And** 手工填写运单复用线上表单，按钮「保存并发货」（H7）；手工单只有手工送达（H13）〔L1〕
**And** 列表加「异常标记」筛选、徽标、摘要（H1）〔L1〕
**And** 下单、重提、取消运单、手工运单、标记已发货写审计，手工运单记承运成本〔L1〕

### Story 6.2：物流异常处置

As a 运营,
I want 处理退回、丢件、停滞和索赔,
So that 异常有头有尾。

**Acceptance Criteria:**

**Given** 带退回标记 **Then** 判定快递 / 用户原因（H8）〔L1〕
**And** 快递原因：标待索赔，「新建运单重发」写 `is_reship`、运费记平台成本、原运单保留（H9）；重发失败可再次提交〔L1〕
**And** 用户原因：抽屉出现「确认退回入库」，按订单行填实收件数，可分次，累计不超应收，库存同事务增加，收齐放行退款；短收不放行〔L1〕
**And** 库存只加一次：入库 + 用户原因取消全流程后库存净增 = 实收件数（测试钉住）〔L1〕
**And** 开始入库后用户确认收货返回 409，App 提示联系客服；其余异常照常可确认（O-16）〔L0 / L1〕
**And** 标记丢件破损（H10）：已发货、已签收、已完成都打标 + 待索赔，引导异常订单〔L1〕
**And** 索赔结案填结果与到账金额（H11），任何订单状态可结案，进对账〔L1〕
**And** 物流停滞可手工清除，只允许 STALLED（H12）；产生时飞书提醒运营〔L1〕
**And** 用户确认收货清除异常时飞书提醒运营，已标待索赔的提示撤回〔L1〕
**And** 待索赔产生时提醒运营〔L1〕
**And** 全部操作写审计〔L1〕

---

## Epic 7：订单页与取消后到账（AB-28）

### Story 7.1：订单总表与退款成长值节

As a 客服,
I want 一张订单总表和完整的售后信息,
So that 查单不用翻履约页。

**Acceptance Criteria:**

**Given** 订单页 **Then** 7 列、抽屉复用履约页；筛选加「异常标记」，摘要按 F1〔L1〕
**And** 抽屉吸底由页面上下文决定，履约页行为不变〔L1〕
**And** 新增「退款与成长值」节：每笔退款来源、两段金额、去向、状态；成长值入账、已扣回、待扣回（F7）〔L1〕
**And** 存量订单空值降级显示〔L1〕

### Story 7.2：取消后到账处置

As a 客服,
I want 处理迟到到账,
So that 用户的钱有去处。

**Acceptance Criteria:**

**Given** 迟到款订单 **Then** 抽屉显示标记、金额与两个按钮（F2），权限 `shop.order_fulfill`；到账时飞书提醒客服；重复付款单只显示退还到账款〔L1〕
**And** 核对清单系统项自动给结果，PawCoin 按实际需扣额显示，手勾项客服勾，全部满足才可提交，服务端重查（F3）〔L1〕
**And** 券被占用或用掉不能提交；已过期可以〔L1〕
**And** 部分缺货可提交，缺货行不锁库存，新单自动标缺货（F4）；全部缺货只能退款〔L1〕
**And** `stock_unlocked` 行部分取消与整单取消都不回补库存；未处置前所有发货入口都拒绝；「联系用户后继续」做 lock + commit 并清标记，锁不到则拒绝〔L1〕
**And** 下运单时原快递不可用自动改选最便宜，抽屉提示，订单快照不变〔L1〕
**And** 重新下单后原单显示新单链接、操作人、时间（F5）〔L1〕
**And** 退还到账款确认后进待用户选择，无补偿（F6）〔L1〕
**And** 同一笔只能处理一次，并发安全〔L1〕
**And** 审计记到账金额、新单号或去向、清单勾选结果〔L1〕
**And** 不提供恢复原订单〔L0〕

---

## Epic 8：对账（AB-38）

### Story 8.1：优惠与物流成本区块

As a 财务,
I want 看到券、运费、保价、补偿、索赔的成本,
So that 对得上账。

**Acceptance Criteria:**

**Given** 对账页 **Then** 新增整宽卡，12 行按架构 AD-15，原四卡不动（I1）〔L1〕
**And** 用户实付运费 = `shipping_fee + shipping_discount`（后者存负数）；运费净成本 = Biteship 实际运费 + 重发运费 − 用户实付运费，减免不重复计（单测）〔L0〕
**And** 已转新订单的到账不重复计入现金收入〔L1〕
**And** 无数据时只在新卡显示空态（I2）〔L1〕
**And** 履约抽屉显示是否保价、保价费、用户实付运费与实际运费〔L1〕

---

## Epic 9：联调（本地）

### Story 9.1：后台全链路联调

As a 验收人,
I want 后台与 App 真实联动走通,
So that 同批上线有把握。

**Acceptance Criteria:**

**Given** stag 或本地真后端 **Then** 按 PRD 逐条走：先在沙箱核实 Biteship 保价费字段与 `price` 是否含保价；建券 → App 领券使用；banner 排期与领券；Biteship 沙箱下运单到签收；运单失败、揽收失败、退回两种原因、丢件破损、索赔；异常订单三类取消；退款打款成功与失败；取消后到账重下单（含部分缺货）与退款〔L1 / L2〕
**And** 后台动作在 App 订单详情的显示符合 App 稿〔L2〕
**And** 飞书三群提醒到达、内容脱敏〔L2〕
**And** UI 逐屏对照 69 屏，还原度 ≥ 95%〔L2〕
**And** 三语切换无缺 key〔L2〕
**And** 产出 `implementation-artifacts/v1.4.2/acceptance-v1.4.2-shop-admin.md`，含上线前检查〔L2〕
