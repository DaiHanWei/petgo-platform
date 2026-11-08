---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
status: complete
updated: '2026-11-05'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/电商/1-4-2电商prd.md
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-shop-delta.md
  - _bmad-output/planning-artifacts/v1.4.2/决策日志-shop.md
  - _bmad-output/planning-artifacts/v1.4.2/电商/ui-1-4-2-shop-integrated-v1.html
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
version: v1.4.2
theme: shop
---

# petgo-platform V1.4.2 shop - Epic Breakdown

## Overview

V1.4.2「电商模块重构」PRD 拆分（FR-127 / 128 / 130 / 131 / 132 / 133 / 134 / 136 / 172；FR-129 到店自提已延后不做）。分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全部需求同一分支）。

> 🔴 **口径优先级**：`决策日志-shop.md`（S-0 ~ S-9）> PRD（变更记录日期最晚的一条为准）> `architecture-v1.4.2-shop-delta.md`（D-1 ~ D-24、P-1 ~ P-12）> UI 稿（定稿）。
>
> 🔴 **范围**：App + 后端。后台**页面**不在本线（后台线另起）；后台操作背后的 service 层在本线实现（S-2），本线验收靠 SQL 种子与 `@StagOnly` 入口（D-24）。
>
> 🔴 **文案**：App 所有新 / 改文案严格取 PRD §6「App 文案定稿」表（印尼语主、英语次），屏号对应 UI 稿；表中标「复用」的引用原 key。

## Requirements Inventory

### Functional Requirements

**FR-127 配送范围与运费前置**
- **FR-127.1** 只在商品详情显示送达地区：已登录有默认地址「送至 {Kecamatan}」+「运费 Rp X 起」；未登录或无地址「选择送达地区」→ 省 / 市 / 区三级抽屉（数据取开通区域树），结果只本地记住、不生成地址。
- **FR-127.2** 所选地区不在开通范围或 Biteship 无快递可达 →「该地区暂不支持快递配送」；询价失败 →「运费暂时无法获取」点击重试。
- **FR-127.3** 免运进度：门槛 > 0 时在详情 / 购物车 / 结算显示「再买 RpX 享运费减免」/「已享运费减免」；门槛 0 不出现；基数 = 商品实付（商品金额 − 满减券）。

**FR-128 一页结算**
- **FR-128.1** 结算页自上而下：配送卡（地址 + 快递 + 保价）→ 商品（默认折叠，点击原地展开）→ 优惠（满减一行、免运一行，顶部显示共省多少）→ 金额明细（止于应付，不含 PawCoin）→ 支付方式（PawCoin 开关 + 剩余 QRIS 金额 + 开封不退同意勾选）→ 吸底栏（应付 +「已省 Rp X」+ 提交；混合时「还需用 QRIS 支付 Rp b」，足额时「全部用 PawCoin 支付」）。
- **FR-128.2** 默认值：每次进入默认选中地址簿默认地址（换地址只对本单）；按地址询价、默认最便宜快递；券默认最优组合；PawCoin 有余额默认勾选。无地址 → 地址区「新增收货地址」、不询价、不可提交。
- **FR-128.3** 页内换地址 / 新增地址（存入地址簿）→ 重新询价，原快递仍可选则保留否则改最便宜；换快递、保价、券、PawCoin → 重算。
- **FR-128.4** 不可提交：地址不在开通区域或无快递可达；询价失败（提示 + 重试）。
- **FR-128.5** 立即购买直达结算：不写购物车、不改勾选；返回详情页不保存任何东西。
- **FR-128.6** 提交失败：部分商品不能买（居中弹窗逐行「Sisa X, diminta Y」/「Sudah ditarik」，「Hapus & lanjut」/ 取消）；这单下不了（toast「Pesanan tidak bisa dibuat sekarang. Muat ulang halaman.」）；券失效（toast「优惠券已失效，已为你重新选择」、按最优重选、停在结算页）；其他（toast「Gagal membuat pesanan. Coba lagi.」）；价格变动不提示。
- **FR-128.7** 提交后：PawCoin 足额直接成功；否则直接拉起支付面板（不再中转待支付详情）。关掉面板 → 待付款、倒计时继续，可从订单列表 / 详情「去支付」拉起同一面板。
- **FR-128.8** 支付面板（电商专用参数）：二维码 + 付款倒计时 + 本次需扫码支付的金额（标签改「需扫码支付」类）+ 保存二维码（提示打开电子钱包或手机银行）+「我已付款」（主动查一次）；去掉红色「取消支付」；支付指引收进「付款方法与支持的 App」折叠；高度自适应。送礼 / 解锁 / 月租等调用方不变。
- **FR-128.9** 支付成功页：应付金额 +「查看订单」「继续逛」；超时：提示付款已超时、订单取消（S-1：服务端以码失效 + 最后查单为准）。

**FR-130 优惠券**
- **FR-130.1** 两种券：满减（满门槛减固定额）、免运（减本单运费，有封顶）；每单最多 1 满减 + 1 免运；无系统默认券。
- **FR-130.2** 计算顺序：商品金额 − 满减 + 运费 − 满额免运或免运券（取减得多，相等用满额免运；减免 ≤ min(本单最低报价, 运营上限)）+ 保价费 = 应付；满减门槛按券前适用商品金额；免运门槛按商品实付；指定范围满减只算范围内商品；免运券有范围须全部商品在范围内；抵扣不超过作用对象。
- **FR-130.3** 结算默认最优组合（抵扣最大的满减 + 优于满额免运的免运券），可换可不用；不可用券单独成组并标原因：未达门槛 / 不在适用范围 / 已过期 / 满额免运减得更多。
- **FR-130.4** 获取：新人券（首次进入 Toko 发放）、Toko 首页「给你的券」领取、商品详情领取、banner 领券、「我的券」兑换码。
- **FR-130.5** 新人 = 注册 30 天内且从未有已支付电商订单的已注册用户；首次进入 Toko 时判定发放（活动开始前进过的，下次进入补发）；不弹窗，在「给你的券」置顶标「新人专享」；游客进 Toko 弹新人券弹窗（同设备每活动一次，「注册领取」进注册，关闭不再弹）；同一时间只有一张新人券在发放期。
- **FR-130.6** 券状态：已领可用 → 被待付款订单占用 → 付款后已使用 / 取消退回为可用（已过期则过期）；过有效期 = 已过期；运营停用并作废 = 已失效（用户端不显示、不通知；已被占用的仍可用于该订单，取消时变失效不退回）；有效性按下单时刻判断；发放期与使用有效期分开。
- **FR-130.7** 退券：支付前取消、超时取消、平台原因整单取消（缺货、运单失败、揽收失败、快递原因物流异常）、丢件破损赔付 → 退回；部分取消、用户原因被退回 → 不退。
- **FR-130.8** 我的券：入口在「订单 → 购物」与首页「给你的券」右上；可用 / 已使用两组，已过期不展示；48 小时内到期标「即将过期」；页内兑换码输入框。
- **FR-130.9** 兑换失败文案：码不存在「兑换码无效」/ 已被使用 / 不在有效期 / 已领取过 / 已领完 / 未登录先登录；同账号 1 小时输错 10 次锁 1 小时「尝试次数过多，请稍后再试」。
- **FR-130.10** 快过期推送：到期前 48 小时一条，同用户同天合并，点击进「我的券」；归「资讯与促销 → 促销与活动」开关，关闭不发。

**FR-131 PawCoin 自选**
- **FR-131.1** 用户决定本单用不用 PawCoin（废止自动抵扣）；有余额默认勾选，取消后本次结算内保持、不跨订单记忆。
- **FR-131.2** 单笔上限保留（后台可配，默认 100 万）；余额超上限时最多用上限、超出 QRIS 付，并提醒「单笔使用超过 {上限} PawCoin 会触发套现判定」（数字读配置）。
- **FR-131.3** PawCoin 是支付方式不是折扣：结算与订单详情的金额明细止于应付，其下单列支付方式区（PawCoin 支付额 / QRIS 支付额）。

**FR-132 Toko 首页 banner**
- **FR-132.1** 多张轮播（上限 6）、自动轮播 + 手动滑动 + 页码点；无生效 banner 整块不渲染。
- **FR-132.2** 跳转：商品详情 / 领券 / 外链；原品类跳转落 Toko 首页；领券跳转点击即领、提示「已领取，可在我的券查看」/「已领取过」；目标失效（商品下架、券领完或过期）整张不展示。

**FR-133 商品浏览、详情、购物车、订单页重做**
- **FR-133.1** 品类筛选下线：首页无品类 chip、商品流只有「全部」；所有 `/shop?category=` 入口（banner、售罄看替代品、健康记录品类行、运营推送）落 Toko 首页不报错。
- **FR-133.2** 搜索维持线上；无结果时放推荐商品（有档案用「为宠物推荐」、无档案「你可能喜欢」取运营权重前 6）；第二句提示改「换个词试试」。
- **FR-133.3** 商品卡显示价格；有可用满减 / 免运券时左上角「Lagi promo」角标（红橙渐变 + 火焰图标，不写金额）。
- **FR-133.4** 首页「为宠物推荐」三列网格最多 6 个，推荐依据只在栏头说一次，卡片只放图、名称、价格、快速加购；体重未填时栏头提示补体重。
- **FR-133.5** 详情上部顺序：图片 → 标题 → 价格 → 可领的券 → 送达地区 → 运费 → 免运进度；底栏加入购物车 / 立即购买；加购后提示「已加入购物车」留在详情、角标更新；未登录先登录后回详情；SKU 缺包裹参数 → 「暂不可售」。
- **FR-133.6** 评价列表（受后台开关控制、默认关，关时维持均分 + 条数）：页内 3 条 +「查看全部」进全部评价页；无评价「暂无评价」；运营隐藏的评价不显示且均分条数重算。
- **FR-133.7** 已下架商品：专门「商品已下架」提示页 + 回 Toko 首页（后端区分下架与其他失败）。
- **FR-133.8** 购物车：保留勾选、改数量、删除撤销、失效分组清理；失效行不可勾选、不参与结算（后端不再默认勾选失效行）；空购物车放推荐商品；底部免运进度 + 预估应付（所选商品 + 本单最低运费 − 默认最优券 − 满额免运）+「已省 Rp X」；无地址 / 询价失败显示「选择地址后计算运费」、预估不含运费。
- **FR-133.9** 订单列表：「我的订单」选中「购物」后出现二级「全部 / 待付款 / 待发货 / 待收货 / 已完成 / 已取消」，待付款与待收货显示数量；「我的券」入口只在「购物」下；待付款可「去支付」。
- **FR-133.10** 订单详情：金额明细按计算顺序逐行至应付 + 支付方式区；部分取消的商品行标已取消并列退款金额；物流显示快递名称、运单号（可复制）、当前状态（待揽收 → 已揽收 → 运输中 → 派送中 → 已签收），手工运单只显示快递名、运单号与「去承运商官网查询」；确认收货不二次确认、提示「已确认收货」；状态显示按 PRD §3.0 订单状态表。
- **FR-133.11** 地址簿：表单与结算页内同一套；不在开通区域或无快递可达的地址标「暂不支持快递配送」。

**FR-134 资金闭环**
- **FR-134.1** 回调丢失兜底：「我已付款」立即查一次；待支付订单付款窗口内定时查单；关单前最后查一次；查询失败不关单。
- **FR-134.2** 取消后到账：记到原订单（不再丢弃），后台标「取消后到账」显示金额；客服优先重新下单（新单关联原单，已到账现金付 QRIS 部分，券与币正常扣，原券已被占用不能重下，券过期仍可用）→ 原订单显示「已为你重新下单」可跳新单、新单注明「由订单 #xxx 重新下单」、给用户发通知；不同意 / 无库存 → 客服触发退款（App「选择退款方式」，无补偿）。
- **FR-134.3** 异常取消必须退现金：整单全额退 PawCoin 与现金；部分取消退该行（满减按比例分摊，币 / 现金按原比例拆、币向下取整，不退运费保价；最后一行按整单退剩余）；整单按原因：平台原因全额退 + 补偿 + 退券，用户原因被退回扣实付运费与保价、无补偿、不退券，丢件破损按保价赔付 + 补偿 + 退券；减去已退金额。
- **FR-134.4** 现金去向由用户选：订单详情「选择退款方式」→ 退为 PawCoin（即时，平台原因给补偿，只对现金段）或线下打款（BCA 免费 / OVO / GoPay 各 Rp 2.500）；选定前款项不退出；线下打款显示「退款处理中，预计 3 个工作日内到账」→ 财务标记后「已退款」；打款失败提示「退款打款失败，请重新填写收款账户」可重填或改选 PawCoin；7 天未选提醒客服（不推送）。
- **FR-134.5** 成长值扣回：PawCoin 段与选退为 PawCoin 时自动扣回；线下打款在标记已打款时扣回；规则按 1.4.0 FR-143；等级下降不通知。

**FR-136 复购下线**
- **FR-136.1** 删除 Toko 首页补货卡与「去建档」引导条、Diary 复购卡；停发「粮快吃完了」推送；停止粮量预估日扫；历史数据保留；旧推送点开落 Toko 首页。
- **FR-136.2** 「为宠物推荐」接口与组件保留（与复购同包，勿连带删）；体重字段说明去掉粮量预估半句。

**FR-172 物流接入 Biteship**
- **FR-172.1** 能不能送 = 地址在开通区域内 **且** Biteship 至少一家报价；开通区域不再决定运费。
- **FR-172.2** 结算快递列表按运费升序（名称、服务类型、预计时效、运费），页面只显示已选一家，点开抽屉更换；默认最便宜；提交时锁定快递、运费、保价。
- **FR-172.3** 询价失败不可提交、不退回固定运费；无可用快递按不支持配送；换地址或改商品重新询价；询价按次计费须复用。
- **FR-172.4** 保价可选（默认不勾）：费 = 商品实付 × 0.5%，不受免运减免；快递不支持保价时不显示选项。
- **FR-172.5** SKU 须填包裹重量与长宽高，没填不可加购、不可立即购买。
- **FR-172.6** 运单（后台触发，本线做服务）：按用户所选快递向 Biteship 下单；运单创建后仍待发货；运营标记发货或 Biteship 回传已揽收 → 已发货；签收回传 → 已送达（手工标记兜底）；自动送达兜底 7 → 14 天；下单失败标运单失败返回运营（重下 / 取消 / 手工），重下次数快速上升告警。
- **FR-172.7** 物流异常：揽收失败、退回、停滞（3 天无更新自动标）、丢件破损；带标记订单不参与自动送达 / 完成，清除后重新计时；确认收货任何时候可点，确认后清标记并提醒运营；运营确认退回 / 丢件破损 → 用户侧「配送异常，请联系平台客服」+ 联系客服；揽收失败、停滞期间用户侧不提示。
- **FR-172.8** 快递原因重发在原订单新建运单，旧运单保留，详情显示最新。

**FR-AN 埋点**（PRD §4）：`toko_checkout_page_viewed.entry`；`toko_order_submitted` 加 `coupon_ids` / `coin_used` / `courier` / `shipping_fee` / `insurance`；banner `position` 取实际位次、`target_type` 加领券；新事件 `toko_payment_qr_saved`、`toko_coupon_claimed{coupon_id, source}`、`toko_delivery_area_selected{serviceable}`、`toko_newbie_coupon_popup_shown` / `_tapped`、`toko_coupon_expiry_push_sent`（服务端）/ `_opened`、`toko_delivery_exception_shown` / `_cs_tapped`；停用 `toko_repurchase_*`。

### NonFunctional Requirements

- **NFR1 资金正确**：金额计算单点（P-1）；券、币（提交时扣，S-3）、库存同事务；到账永不丢弃且仍计入 PAID 报表；关单前必查单、查询失败不关单（S-1）。
- **NFR2 成本**：Biteship 报价 30 分钟复用（D-9）。
- **NFR3 外部依赖**：Biteship / GemPay 调用不在持锁事务内；`BITESHIP_MODE=stub` 供 L0 / L1 / 云端；Webhook 不信任载荷（回查）。
- **NFR4 安全**：新标识一律 token；兑换码防暴力；收款账户加密；Webhook 路径密钥；日志不记地址、手机号、收款账户、key。
- **NFR5 护栏**：时间戳迁移；`ck_notifications_type` DROP + ADD 全集（本线是后合并方，基数取当前树最后一次重建）；无新依赖 / MQ / 缓存层；写库监听 `REQUIRES_NEW`。
- **NFR6 兼容**：旧版 App **仍可下单**（S-4 旧结算模式）；`/shop/banner`、`/me/checkout`（GET）、`/me/shop/repurchase-cards` 保留可用；接口只加字段；存量订单新列为空时 App 降级展示、退款按已有金额列计算、只能走手工运单。
- **NFR7 门店返佣不变**：`shop_order_lines.unit_price` 不分摊券；不改 `paid_at` / `referrer_merchant_id` 语义。
- **NFR8 测试**：计价 / 退款计算器表驱动单测覆盖 PRD 全部例子；券并发；查单幂等；守卫测试同 story 改；App 每屏 widget 测试；现有测试全绿；含 UI 的分支验收做还原度检测（≥95%）。

### Additional Requirements（架构）

- 11 个迁移按 P-8 拆分（排在 `V20261104_2058` 之后），版本号由 sprint-status 预分配。
- 新包：`shop/coupon`、`shop/order/pricing`、`shop/order/refund`、`shop/logistics`、`shared/logistics/biteship`、`shop/stag`（P-4）。
- 新 env：`BITESHIP_API_KEY`、`BITESHIP_MODE`、`BITESHIP_COURIERS`、`BITESHIP_WEBHOOK_SECRET`（`.env.example` 只放占位）。
- 外部待办（阻塞上线不阻塞开发）：Biteship 正式 key 与 Order API 开通、Webhook 地址、发货地信息；运营补齐存量 SKU 包裹参数与 `shipping_zones.postal_code`；评价开关打开前运营过一遍历史评价。

### UX Design Requirements

UI 定稿约 95 屏（逐屏说明即实现说明；数值照说明取代码出处，不量截图）：
- **A1–A9** Toko 首页与搜索 · **B1–B15** 商品详情 · **C1–C8** 购物车 · **D1–D18** 结算 · **E1–E7** 支付 · **F1–F25** 订单与退款 · **G1–G9** 我的券 · **H1–H5** 地址簿。

### FR Coverage Map

FR-172.5、FR-172.1 / .3（报价）→ 1.1 · FR-130.2 / .3、FR-131.1 / .2、FR-172.4（计价）→ 1.2
FR-130.6 / .7（账本）→ 2.1 · FR-130.4 / .5 / .8 / .9、FR-133.3（后端）→ 2.2 · FR-130.10、通知类型 → 2.3
FR-127、FR-128.1 ~ .4、FR-133.8 / .11（后端）→ 3.1 · FR-128.5 / .6、FR-131、FR-172.2 / .3 → 3.2 · FR-128.7 / .9、FR-134.1 → 3.3
FR-134.3 / .4 / .5 → 4.1 · FR-134.2 → 4.2
FR-172.6 / .8 → 5.1 · FR-172.7 → 5.2
FR-132（后端）、FR-133.6 / .7（后端）、FR-133.2（top 商品）→ 6.1 · FR-133.9 / .10（后端）、FR-136（后端）→ 6.2
FR-128.1 ~ .6、FR-130.3、FR-131、FR-172.2 / .4 → 7.1 · FR-128.7 ~ .9、FR-134.1（我已付款）→ 7.2
FR-132、FR-133.1 ~ .4、FR-130.4 / .5（首页部分）、FR-136.1（首页）→ 8.1 · FR-127、FR-133.5 ~ .7、FR-130.4（详情领券）→ 8.2 · FR-133.8 → 8.3
FR-133.9 / .10、FR-134.2（展示）、FR-172.7（展示）→ 9.1 · FR-134.4 → 9.2 · FR-130.8 / .9 / .10（App）→ 9.3 · FR-133.11、FR-136.1 / .2（Diary / 体重文案）→ 9.4
全部资金与物流链路 → 10.1 / 10.2（L1 / L2）· FR-AN → 各功能所在 story · NFR1 ~ 8 → 相关 story 通用验收

## Epic List

### Epic 1: 按真实包裹算运费
接入 Biteship 报价，SKU 有包裹参数才能卖，同一包裹报价复用；所有金额由一个计价器算出。
**FRs covered:** FR-172.1 / .3 / .4 / .5、FR-130.2 / .3、FR-131.1 / .2 · **迁移:** ① SKU 包裹参数、② 发货地 / 免运封顶 / 保价费率 / 区域邮编、⑧ `shipping_quotes`

### Epic 2: 优惠券
运营配好的券能被领取、兑换、新人自动获得，下单时占用、付款后使用、取消按规则退回，快过期提醒。
**FRs covered:** FR-130.4 ~ .10、FR-133.3（后端）· **迁移:** ⑦ 券四表、⑪ 通知类型约束

### Epic 3: 一页完成结算与付款（后端）
结算试算与提交支持立即购买、券、快递、保价、PawCoin 自选；付款窗口内主动查单，码失效后才关单。
**FRs covered:** FR-127、FR-128、FR-131、FR-133.8（估算）、FR-133.11（可配送）、FR-134.1 · **迁移:** ③ 订单头新列、⑩ 意图码有效期与 `paid_after_close`

### Epic 4: 钱一定退得回来
异常取消按原因退 PawCoin 与现金、用户选现金去向、线下打款可追踪、成长值扣回；超时后到账的款项能被找到并重新下单或退款。
**FRs covered:** FR-134.2 ~ .5 · **迁移:** ⑨ `shop_order_refunds`

### Epic 5: 物流自动跟踪
运单经 Biteship 下单，状态自动回传驱动发货 / 送达，物流异常可标记并暂停自动计时。
**FRs covered:** FR-172.6 ~ .8 · **迁移:** ④ 运单与物流异常列

### Epic 6: 商品、banner、订单列表与复购下线（后端）
banner 轮播与领券跳转、商品下架区分、评价列表、按状态的电商订单列表、订单详情新字段、复购下线。
**FRs covered:** FR-132、FR-133.2 / .6 / .7 / .9 / .10（后端）、FR-136（后端）· **迁移:** ⑤ banner、⑥ 评价隐藏

### Epic 7: 结算与付款（App）
一页结算（地址、快递、券、PawCoin、提交失败处理）、支付面板改造与支付结果。
**FRs covered:** FR-128、FR-131、FR-134.1（App）、FR-172.2 / .4

### Epic 8: 逛 Toko、看商品、购物车（App）
新首页（轮播、给你的券、新人弹窗、为宠物推荐、无品类）、搜索、商品详情（领券、送达地区、运费、评价、下架页）、购物车估算与空态推荐。
**FRs covered:** FR-127、FR-130.4 / .5（App）、FR-132、FR-133.1 ~ .8、FR-136.1（首页）

### Epic 9: 订单、退款、我的券、地址簿（App）
按状态分组的订单列表、新订单详情（物流、异常、部分取消、重下单）、选择退款方式、我的券与兑换、地址簿、复购清理收尾。
**FRs covered:** FR-130.8 ~ .10（App）、FR-133.9 ~ .11、FR-134.2 / .4（App）、FR-136、FR-172.7（App）

### Epic 10: 端到端联调
资金全链路（下单 → 付款 → 查单 → 取消 → 退款去向）与 Biteship 物流在真后端、模拟器、沙箱上跑通。
**FRs covered:** 全部（L1 / L2 验收）

**依赖：** 同一分支串行 E1 → E10。后端 E1 ~ E6 先于 App E7 ~ E9，最后 E10 联调。App 先做结算（7.1 替换 `/shop/checkout` 路由为新页，旧购物车「去结算」与旧详情「立即购买」都能进新结算页），再做支付面板（7.2，7.1 提交后先调用现有面板），之后首页、详情（8.2 立即购买直达 7.1 已支持的 `source=BUY_NOW`）、购物车——无前向依赖。App story 用 `BITESHIP_MODE=stub` 与 D-24 stag 入口造数。

---

## Epic 1: 按真实包裹算运费

### Story 1.1: SKU 包裹参数、发货地配置与 Biteship 报价（后端）

As a 平台,
I want 按真实包裹向 Biteship 询价并复用报价,
So that 运费准确、询价成本可控。

**范围：** 迁移 ①（`shop_skus.package_weight_g / package_length_cm / package_width_cm / package_height_cm`，D-10）、②（`shipping_settings.origin_postal_code / origin_contact_name / origin_contact_phone / origin_address / free_shipping_cap / insurance_rate_bp`、`shipping_zones.postal_code`）、⑧（`shipping_quotes`，D-9）；`BiteshipClient`（rates / createOrder / cancelOrder / getOrder，本 story 只需 rates 落地并测试，其余方法签名 + stub）；`ShippingQuoteService`（询价、复用、清理任务）；可配送判定；SKU 可售判定；env 占位。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 新列可空、存量数据不变；`shipping_zones.fee` 不再被任何代码读取（列保留）；`ddl-auto=validate` 通过〔L0 / L1〕
**Given** SKU 任一包裹参数为空 **Then** `ShopSku.isSellable()` 为 false；加购、立即购买、结算对该 SKU 返回 422 `sku-not-sellable`；购物车中该行标失效〔L1〕
**Given** 收货邮编 + 包裹明细 **When** 询价 **Then** 调 `POST /v1/rates/couriers`（`origin_postal_code` = 发货地邮编，`items` 按 SKU 逐项：名称、`value`、`quantity`、重量克、长宽高厘米，`couriers` 取 `BITESHIP_COURIERS`），结果落 `shipping_quotes`（含 `lowest_price`、到期 30 分钟）并返回按价格升序的快递列表（快递码、服务码、名称、服务名、时效文本、价格、是否支持保价）〔L1，Biteship 真调 L2〕
**Given** 30 分钟内同一（收货邮编、`items_hash`、发货邮编）再次询价 **Then** 直接复用已存报价、不调 Biteship（测试断言调用次数）〔L1〕
**Given** Biteship 超时 / 5xx / 网络错误 **Then** 抛 `QuoteFailedException`（映射 503 `quote-failed`），不落库、不回退固定运费；**Given** 返回 0 家快递 **Then** 判为不可配送〔L1〕
**And** 可配送 = 地址 Kecamatan 在 `shipping_zones.active` **且**报价 ≥ 1 家；区域未配 `postal_code` 时游客地区询价按不可配送〔L1〕
**And** `BITESHIP_MODE=stub` 返回固定两家报价（一家支持保价、一家不支持），用于 L0 / L1 / 云端；`sandbox` / `live` 用 key；key 不进日志〔L0 / L1〕
**And** 每日清理 2 天前且未被订单引用的报价〔L1〕
**And** Biteship 调用不在持锁事务内；日志不记收货地址与手机号（P-7）〔L0 评审〕

### Story 1.2: 订单计价器（后端）

As a 平台,
I want 所有应付、减免、保价、PawCoin 拆分由一个纯函数算出,
So that 结算、下单、订单详情、退款口径永远一致。

**范围：** `ShopPricingCalculator` / `PricingResult` / 券可用性原因（D-7，纯函数无 IO，输入用自有值对象 `PricingLine` / `PricingCoupon`，不依赖 2.1 的券实体）；`PaymentSplit.compute` 增 `useCoin`；表驱动单测。

**Acceptance Criteria:**

**Given** PRD FR-130 计算顺序 **Then** 应付 = 商品金额 − 满减 + 运费 − 运费减免 + 保价费；`payable`、`coinAmount`、`cashAmount`、`savedAmount`（= 满减 + 运费减免）、`freeShippingProgress{threshold, remaining}` 正确〔L0〕
**And** 满减门槛按券前适用范围内商品金额；抵扣 ≤ 适用商品金额；免运门槛与满额免运门槛按商品实付；免运券有范围时有一件不在范围即不可用（原因 `OUT_OF_SCOPE`）〔L0〕
**And** 运费减免 = min(所选快递运费, 本单最低报价, 封顶)（满额免运封顶取 `free_shipping_cap`，为空或 0 = 不设额外封顶；免运券取券额）；例：最低 15,000、上限 20,000、选 28,000 → 减 15,000、付 13,000〔L0〕
**And** 满额免运与免运券取减得多，相等用满额免运；未采用的免运券原因 `RULE_BETTER`、不扣〔L0〕
**And** 保价费 = ceil(商品实付 × `insurance_rate_bp` / 10000)，例：300,000 → 1,500；不受减免；未勾选为 0〔L0〕
**And** `useCoin=true` 时币 = min(应付, 余额, 上限)；`false` 时 0；足额时现金 0〔L0〕
**And** 默认最优组合：满减取抵扣最大（并列先到期），免运券取减免最大且优于满额免运者；无可用券时为空〔L0〕
**And** 不可用原因覆盖 `BELOW_MIN_SPEND`、`OUT_OF_SCOPE`、`EXPIRED`、`RULE_BETTER`；门槛 0 时 `freeShippingProgress` 为空〔L0〕
**And** 订单行单价不被改写（返佣基数不变，NFR7）〔L0〕

---

## Epic 2: 优惠券

### Story 2.1: 券数据与券账本（后端）

As a 平台,
I want 券的模板、用户券、兑换码有清晰的数据与唯一的状态入口,
So that 券不会被重复使用、取消时按规则退回。

**范围：** 迁移 ⑦（`shop_coupons`、`user_coupons`（含 `expiry_notified_at`）、`shop_coupon_codes`、`coupon_expiry_notify_log`，D-1 ~ D-4）；`CouponLedgerService`（lock / markUsed / releaseOnCancel(reason) / stopCoupon(voidUnused)）；运营侧建券 / 停券 service 方法（供后台线）；`@StagOnly` 建券入口 `POST /admin/stag/shop/coupons`（D-24）。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 四表与索引存在、整数列 INTEGER、金额 BIGINT、对外 `public_token`〔L0 / L1〕
**When** 建券（service）**Then** 校验字段组合（满减 `amount < min_spend` 或 `min_spend=0` 允许；免运券 `amount` 为封顶；发放期、有效期合法；`RELATIVE` 需 `valid_days`）；已存在另一张发放期重叠的 ACTIVE 新人券 → 拒绝（advisory lock 串行）〔L1〕
**Given** 用户券 AVAILABLE 且下单时刻在有效期 **When** `lock(orderId)` **Then** 置 LOCKED；**Given** 同一张券被两个并发提交锁 **Then** 只成功一个，另一个抛 `CouponInvalidException`〔L1〕
**When** 付款成功 **Then** LOCKED → USED；**When** 取消且原因应退（D-14 `refundCoupon`）**Then** 回 AVAILABLE、清订单字段；不应退 **Then** 不动〔L1〕
**Given** 运营停券并选作废 **Then** AVAILABLE → VOIDED、LOCKED 不动；该订单之后取消 **Then** 转 VOIDED 不退回；不选作废 **Then** 已领的照常可用〔L1〕
**And** 「已过期」不落库，由 `valid_until` 现算；有效性按下单时刻判断（付款前过期仍可完成本单）〔L1〕
**And** 禁止在 `CouponLedgerService` 之外更新 `user_coupons.status`：加源码扫描测试（除该类外出现 `user_coupons` 写语句或 `setStatus` 调用即失败）〔L0〕

### Story 2.2: 领券、兑换码、新人券与券查询（后端）

As a 用户,
I want 在首页、详情、banner 领券，输入兑换码，新人自动拿到券,
So that 下单能省钱。

**范围：** `CouponClaimService`、`CouponRedeemService`（防暴力）、`NewbieCouponService`、`CouponQueryService`；接口（D-5 / D-6）：`POST /me/shop/entry`、`GET /shop/coupons/newbie-campaign`、`GET /shop/coupons/for-you`、`GET /shop/products/{token}/coupons`、`POST /me/coupons/claim {couponToken, source: home|detail|banner}`、`POST /me/coupons/redeem`、`GET /me/coupons?tab=`；商品列表 / 搜索 / 推荐出参 `promo`。

**Acceptance Criteria:**

**When** 领券 **Then** 校验发放期、ACTIVE、每人限领、总量（条件 UPDATE），成功写 `user_coupons`（有效期按模板物化）；失败分别 `coupon-not-in-period` / `coupon-already-claimed` / `coupon-sold-out`；并发领最后一张只成功一个〔L1〕
**When** 兑换 **Then** 码规范化大写；失败与 PRD 文案一一对应：不存在 `coupon-not-found`、一次性已用 `coupon-code-used`、不在发放期或券已停用 `coupon-not-in-period`、达限领 `coupon-already-claimed`、领完 `coupon-sold-out`；未登录 401〔L1〕
**Given** 同账号 1 小时内「不存在 / 已被使用」累计 10 次 **Then** 锁定 1 小时，期间任何兑换 429 `coupon-redeem-locked`〔L1〕
**When** `POST /me/shop/entry` **Then** 用户注册 ≤ 30 天、无 `paid_at` 非空的电商订单、存在发放期内 ACTIVE 新人券且未领过 → 发放（`source=NEWBIE`），返回 `newbieCouponIssued`；重复调用幂等；超 30 天、已有付款订单、游客均不发；活动开始前进过 Toko 的下次调用补发〔L1〕
**When** `GET /shop/coupons/newbie-campaign`（游客可访问）**Then** 返回当前新人券面额、门槛、`campaignToken`；无活动 204〔L1〕
**When** `GET /shop/coupons/for-you` **Then** 返回发放期内可领的 CLAIM 券（带 `claimed`）+ 已到账可用的新人券（置顶、`newbie=true`）；无则空数组；需登录〔L1〕
**When** `GET /me/coupons?tab=available` **Then** 只列 AVAILABLE 且未过期（LOCKED、VOIDED、过期都不列），带 `expiringSoon`（≤ 48h）；`tab=used` 列 USED；分页〔L1〕
**And** 商品卡 `promo`：发放期内 ACTIVE 的 CLAIM 券覆盖该商品，或当前用户持有可用券覆盖该商品 → true；批量计算不逐商品查库〔L1〕
**And** 券 / 码出参不暴露自增 id〔L0 评审〕

### Story 2.3: 通知类型与券快过期推送（后端）

As a 持有快过期券的用户,
I want 到期前收到一条提醒,
So that 不浪费券。

**范围：** 迁移 ⑪（`ck_notifications_type` DROP + ADD = 当前树最后一次重建迁移的全集 + `COUPON_EXPIRING` + `SHOP_ORDER_REORDERED`）；`NotificationType` +2；`PushCategory` 映射（`COUPON_EXPIRING → PROMO`、`SHOP_ORDER_REORDERED → ORDER_ACCOUNT`）；改 `PushCategoryTest`、`MentionNotifyTest`；`CouponExpiryReminderJob`（D-22）；服务端埋点 `toko_coupon_expiry_push_sent`。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 约束值 = 当前树最后一条重建迁移（现为 `V20261104_2010`）的全集 + 2，不写死数量、迁移注释写明基数来源；两个守卫测试更新后通过〔L0 / L1〕
**Given** 每天 WIB 10:00 **Then** 找 `valid_until` 在 (now, now+48h] 的 AVAILABLE 用户券，每用户合并 1 条 `COUPON_EXPIRING`（`variant` = 张数），后端拼文案，深链 `/me/coupons`；同一用户同一 WIB 日只 1 条（`coupon_expiry_notify_log`）；每张券只被提醒一次（`user_coupons.expiry_notified_at`，连续两天落进 48 小时窗口不重复）〔L1〕
**Given** 用户关闭 PROMO 分类 **Then** 不推送（通知中心照常写入，沿用现有偏好逻辑）〔L1〕
**And** 推送走全局限速、失败不重推；`@Scheduled` + advisory lock；发送后上报 `toko_coupon_expiry_push_sent{coupon_count}`〔L1〕

---

## Epic 3: 一页完成结算与付款（后端）

### Story 3.1: 结算试算与运费估算（后端）

As a 买家,
I want 在详情、购物车、结算页都能看到能否送达、运费和优惠后的应付,
So that 下单前就知道要付多少。

**范围：** `POST /me/checkout/preview`（D-8，只读）；`GET /shop/products/{token}/shipping-estimate`；`GET /me/cart` 加 `estimate`；购物车失效行不再默认勾选；地址簿 `serviceable`（标准探测包裹，D-9）；删除 `CheckoutPreviewIntegrationTest`「不得出现券字段」断言。

**Acceptance Criteria:**

**When** `POST /me/checkout/preview` **Then** 返回地址（默认地址或指定）、`quoteToken`、快递列表（升序）、已选快递（指定且仍可选则保留，否则最便宜）、行、`PricingResult`、可用 / 不可用券分组（带原因）、币余额与 `coinCap`、退货政策文案键、不可提交原因（`NO_ADDRESS` / `NOT_SERVICEABLE` / `QUOTE_FAILED`）〔L1〕
**And** `couponMode=AUTO` 时券取最优组合；`MANUAL` 时用指定券（可为空 = 不用券）；`source=BUY_NOW` 只用 `buyNow` 的 SKU 与数量、不读购物车；`source=CART` 只用 `selected=true` 且有效的行〔L1〕
**When** `GET /shop/products/{token}/shipping-estimate?skuToken=&qty=&addressToken=|regionKecamatanId=` **Then** 返回 `serviceable`、`lowestFee`、`freeShippingProgress`；询价失败 `quoteFailed=true`；游客按地区代表邮编（`regionKecamatanId` 为区域参考数据，允许外露）〔L1〕
**When** `GET /me/cart` **Then** `estimate` = 所选商品 + 本单最低运费 − 默认最优券 − 满额免运，带 `saved`、`freeShippingProgress`；无地址且无地区 / 询价失败 → 运费为空并带 `reason`〔L1〕
**And** 失效行 `selected` 一律下发 false；勾选失效行的 PATCH 返回 422〔L1〕
**When** `GET /me/shipping-addresses` **Then** 每项带 `serviceable`：区域未开通 false；已开通按标准探测包裹询价（按邮编复用 24 小时）；询价失败 null〔L1〕
**And** 所有金额只来自计价器（P-1）；试算不占用券、不扣币、不锁库存〔L1〕

### Story 3.2: 提交订单（后端）

As a 买家,
I want 提交时锁定我选的快递、券和 PawCoin,
So that 付款金额就是我看到的金额。

**范围：** 迁移 ③（订单头新列，D-8 列表）；`POST /me/shop-orders` 重写（BUY_NOW、券占用、报价锁定与绑定校验、保价、`useCoin`、`agreeReturnPolicy`）；**PawCoin 提交时扣**（S-3，`ShopOrderPaidHandler` / `settlePureCoin` 不再扣币，所有取消路径退币，财务看板口径同步）；旧结算模式 `LegacyCheckoutAdapter`（S-4）；旧 `GET /me/checkout` 改按 Biteship 最低报价；纯币结算统一走 `markPaid`。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 新列不与 `goods_subtotal / shipping_fee / shipping_discount / total_amount / coin_amount / cash_amount` 重名，沿用已有列承载对应金额；存量订单不变；`ddl-auto=validate` 通过〔L0 / L1〕
**When** 提交 **Then** 服务端按 `quoteToken` 重新计价（不信任客户端金额），同一事务：扣库存、`CouponLedgerService.lock`、扣币（写流水）、建订单写快照列；Idempotency-Key 沿用〔L1〕
**Given** 报价收货邮编 ≠ 所选地址邮编，或 `items_hash` ≠ 本单商品 **Then** 409 `quote-expired`〔L1〕
**And** 失败映射：下架 / 库存不足 409 `unavailable-lines`（带行明细）；无地址 / 不可配送 / 无可结算商品 422 `not-placeable`；券失效 409 `coupon-invalid`；报价过期 409 `quote-expired`；`agreeReturnPolicy≠true` 422；失败时券、币、库存全部回滚〔L1〕
**Given** `source=BUY_NOW` **Then** 提交成功不改购物车任何行；**Given** `source=CART` **Then** 删除已下单行、未勾选的保留〔L1〕
**Given** 现金段为 0 **Then** 走 `markPaid(order, source=PURE_COIN)`：订单 PENDING_SHIPMENT、写 `paid_at`、券转 USED、发 `ShopOrderPaidEvent`、服务端 `shop_payment_paid`（补现状缺口）〔L1〕
**And** 付款到账时不再扣币；付款回调不会因币余额不足回滚（回归用例：提交后把币花光，再到账，订单正常转待发货）〔L1〕
**And** `ShopFinanceDashboardService` 币消耗统计按提交时扣的口径（取消退回的不计）〔L1〕
**Given** 请求体无 `quoteToken`（旧版 App）**Then** 旧结算模式：服务端询价选最便宜快递、不用券、`useCoin=true`、不保价，成功下单；旧 `GET /me/checkout` 返回旧结构、运费为 Biteship 最低报价〔L1〕
**And** 门店返佣相关列语义不变：行 `unit_price` 为原单价、`paid_at` 只在付款时写〔L1〕

### Story 3.3: 付款窗口、主动查单与关单后到账（后端）

As a 已付款的用户,
I want 回调丢了系统也能查到我的付款、码失效前订单不被关掉,
So that 付了钱的订单不会被错误取消。

**范围：** 迁移 ⑩（`payment_intents.provider_expires_at`、`paid_after_close`）；过期判据四条路径统一（S-1，架构 §1.2 第 21 条）；`queryCharge` 三态；`ShopPaymentReconcileJob`；`POST /me/shop-orders/{token}/payment-check`；出码 10 分钟限制；宽限期 `displayStatus`（S-7）；关单后到账转 PAID + 记到原订单（D-11）。

**Acceptance Criteria:**

**When** 出码 **Then** 电商意图 `provider_expires_at` = `expires_at` = 出码时刻 + 65 分钟；订单 `expires_at` 仍为建单 + 60 分钟〔L1〕
**And** 全局 `PaymentIntentExpiryScanner` 对电商意图按 `provider_expires_at` 判；`statusOf` 轮询与 `requireOwn()` 不再就地置过期或关单（只读）；`isPaymentExpiredAt` / `findOverduePendingPayment` 改为「订单已过期 **且** 该单所有意图码已失效」〔L1〕
**Given** 订单已过 `expires_at` 但码未失效 **Then** 不取消；**Given** 两者都过 **Then** 先查单：明确已付（含 `Expired => Success`）→ 按到账处理；明确 EXPIRED / FAILED → 取消（退币、按 `PAYMENT_TIMEOUT` 退券、回补库存）；返回空或错误 → 本轮跳过不取消；同一订单连续 10 次查询失败 → ERROR 日志 + 飞书告警〔L1〕
**Given** 订单创建超过 10 分钟仍无可用码 **Then** 不再出新码，返回明确错误（App 提示订单已无法支付）；已有可用码时「去支付」复用同一个码〔L1〕
**Given** PENDING 或关单后 24 小时内的 EXPIRED 电商意图（出码 ≥ 3 分钟）**Then** 定时任务每 2 分钟查单（单次 ≤ 100 笔、advisory lock、网关调用不在事务内），查到已付按到账处理；重复执行无副作用〔L1〕
**When** `POST /me/shop-orders/{token}/payment-check` **Then** 同步查单一次并返回 `{paid, status}`；同订单 10 秒内重复调用不再调网关〔L1〕
**Given** 待支付订单 `now > expires_at` **Then** 详情与列表 `displayStatus = CANCELLED`、归「已取消」组、`canPay=false`；宽限期内到账后订单转 PENDING_SHIPMENT〔L1〕
**Given** 订单已 CANCELLED **When** 回调或查单返回已付 **Then** 电商意图照常转 PAID、`paid_after_close=true`、发 `PaymentIntentPaidEvent`；`ShopOrderPaidHandler` 写 `late_paid_amount / late_paid_at / late_paid_intent_id`、INFO 日志、不改订单状态；财务报表按 PAID 可见〔L1〕
**And** 送礼、充值、问诊、解锁等其他业务线的回调与过期行为不变（回归覆盖 `GiftPaidHandler` 关单后到账路径与充值 60 分钟窗口）〔L1〕
**And** 回调、查单任务、我已付款、关单四入口共用 `applyResult`，幂等（P-3）〔L0 评审 / L1〕

---

## Epic 4: 钱一定退得回来

### Story 4.1: 取消原因与取消退款单（后端）

As a 被平台取消订单的用户,
I want 我付的 PawCoin 和现金都能退回、现金退到哪我自己选,
So that 不会因为平台缺货损失钱。

**范围：** 迁移 ⑨（`shop_order_refunds`，D-15）；`CancelReason`（D-14，属性由类型推出）；`ShopRefundCalculator`；`ShopOrderRefundService`（cancelWhole / cancelLine / chooseDestination / markPaidOut / markPayoutFailed）；`POST /me/shop-orders/{token}/refunds/{refundToken}/destination`；`AdminShopOrderExceptionService` 改调本服务；`RefundDestinationReminderJob`；成长值扣回事件；`@StagOnly` 入口（取消、标记已打款）。

**Acceptance Criteria:**

**Given** 部分取消（可取消一行中的部分件数）**Then** 行金额 = 单价 × 取消件数；分摊满减 = floor(满减 × 行金额 / 适用商品金额)；退款 = 行金额 − 分摊满减；不退运费与保价；币段 = floor(退款 × `coin_amount` / `total_amount`)，零头入现金；行 `refunded_qty` 累加（复用现有列）、订单 `refunded_total / refunded_coin` 累加、库存回补；券不退〔L0 / L1〕
**Given** 被取消的是剩余最后的件 **Then** 按整单处理，退剩余未退应付（含运费、保价）：剩余币 = `coin_amount` − `refunded_coin`，剩余现金同理〔L1〕
**Given** 整单取消，原因 `OUT_OF_STOCK / WAYBILL_FAILED / PICKUP_FAILED / COURIER_FAULT` **Then** 全额退剩余未退、券退回、平台原因；`USER_FAULT_RETURNED` **Then** 扣用户实付运费（运费 − 减免）与保价费、券不退、无补偿；`LOST_DAMAGED` **Then** 买了保价 = 商品实付，没买 = 运营输入协商额（≤ 商品实付）、券退回；均减去此前部分取消已退金额〔L0 / L1〕
**And** 币段立即退回 PawCoin；现金段 > 0 → 退款单 `AWAITING_DESTINATION`，订单详情 `refunds[]` 可选去向；现金段 0 → `COMPLETED`〔L1〕
**When** 选 `TO_PAWCOIN` **Then** 即时入账；原因平台责任时补偿 = 现金段 × `compensation_premium_rate`（封顶 `_cap`），只对现金段；→ `COMPLETED`〔L1〕
**When** 选 `TO_BANK`（BCA / OVO / GoPay + 账户）**Then** 扣渠道费（0 / 2,500 / 2,500）、账户 AES-GCM 加密存储 → `PAYOUT_PENDING`；`markPaidOut` → `COMPLETED`；`markPayoutFailed` → `PAYOUT_FAILED`，用户可重选（含改选 PawCoin）〔L1〕
**And** 成长值扣回：币段退回、现金选 PawCoin、标记已打款三处各发一次 `RefundSucceededEvent`（金额 = 本次退款额，不含补偿；`refundKey` = `SHOPREF:{退款单 token}:{COIN|CASH_PAWCOIN|CASH_BANK}`，同一退款单多段不被去重吞掉）〔L1〕
**And** 门店返佣扣回不在本线（S-5 遗留），但 `refunded_qty` / `refunded_total` 必须准确（单测断言）〔L1〕
**And** 库存与状态按原因：未付款取消 / 超时 → 回补库存、CANCELLED、币原路退回；已付未发货的平台原因 → 回补库存、REFUNDING → 全部退款单完成后 REFUNDED；已发货后 `LOST_DAMAGED` → 不回补、SHIPPED → REFUNDING → REFUNDED；部分取消不改订单状态〔L1〕
**Given** `USER_FAULT_RETURNED`（S-8）**When** 运营取消 **Then** 订单 REFUNDING、**不生成退款单**；**When** `confirmReturnInbound` **Then** 回补库存并生成退款单〔L1〕
**Given** 订单已签收 / 已完成后报破损（S-9）**Then** 订单状态不变，生成 `scope=COMPENSATION` 赔付退款单（金额规则同丢件破损），用户同样选去向〔L1〕
**And** 退款单 `AWAITING_DESTINATION` 超 7 天 → 每日任务记运营提醒（日志 + 订单备注），不推送用户〔L1〕
**And** 现有异常订单整单 / 部分取消改走本服务后，现金段不再只记审计（回归）〔L1〕
**And** 收款账户不进日志；退款单对外只用 token〔L0 评审〕

### Story 4.2: 迟到款重新下单与退款（后端）

As a 付款晚于订单时限的用户,
I want 客服帮我用已到账的钱重新下单，或者退给我,
So that 钱不会停在网关。

**范围：** `LatePaymentService.reorderFromLatePayment / refundLatePayment`（D-11）；`SHOP_ORDER_REORDERED` 通知（2.3 已加类型）；订单详情出参 `reorderedToOrderToken` / `reorderedFromOrderNo`；`@StagOnly` 入口。

**Acceptance Criteria:**

**Given** 原订单已取消且有迟到款 **When** 重新下单 **Then** 校验库存、原券未被其他订单占用（被占用 → 拒绝）、券已过期仍可用；新订单行 / 地址 / 快递 / 运费 / 保价同原单快照、`reorder_of_order_id` = 原单、不重新询价〔L1〕
**And** 多退少补（S-6）：到账额 ≥ 新单现金段 → 多出部分退为 PawCoin（无补偿、不发成长值扣回事件）；不足 → 差额扣用户 PawCoin，余额不够 → 拒绝（只能退款）〔L1〕
**And** 新单走 `markPaid(order, source=REORDER)`：写 `paid_at`、发 `ShopOrderPaidEvent`（飞书发货提醒、成长值入账）、服务端 `shop_payment_paid`；订单 PENDING_SHIPMENT；原单 `late_payment_resolution=REORDERED`〔L1〕
**And** 成功后给用户发 `SHOP_ORDER_REORDERED`（深链新订单，后端拼文案取 PRD §6 F22）；原单出参 `reorderedToOrderToken`，新单出参 `reorderedFromOrderNo`〔L1〕
**When** 退款 **Then** 建退款单 `reason=LATE_PAYMENT`，全额现金段、无补偿、无币段，后续同 4.1 选择去向〔L1〕
**And** 同一迟到款只能处理一次（`late_payment_resolution` 条件更新，重下单或退款二选一，并发安全）〔L1〕

---

## Epic 5: 物流自动跟踪

### Story 5.1: Biteship 运单、Webhook 与物流状态（后端）

As a 买家,
I want 订单详情自动显示快递与物流状态,
So that 不用自己去承运商官网查。

**范围：** 迁移 ④（`shipments` 新列、`carrier` 放宽；订单头 `waybill_failed_at / waybill_fail_reason / shipped_source`；`biteship_webhook_events` 收件表，D-12）；`BiteshipShipmentService.createWaybill / retry / cancelWaybill`（供后台线）；`POST /api/v1/webhooks/biteship/{secret}`；`BiteshipStatusMapper`；自动发货 / 送达；重试告警；`AUTO_DELIVER_AFTER` 7 → 14 天；`@StagOnly` 下运单入口。

**Acceptance Criteria:**

**When** `createWaybill(order)` **Then** 用订单锁定的快递与服务、发货地、收货地址、包裹明细、`courier_insurance`（勾保价时 = 商品实付）、`reference_id` = 订单号 + 序号调用 Biteship；成功写运单（`provider=BITESHIP`、`waybill_id`、`tracking_id`），订单仍 PENDING_SHIPMENT〔L1，sandbox L2〕
**Given** 下单失败 **Then** 订单写 `waybill_failed_at` 与原因；5 分钟内全局重试 ≥ 10 次 → ERROR 日志 + 飞书告警（沿用电商飞书通道）〔L1〕
**When** Webhook 到达 **Then** 路径密钥常量时间比较，错误 404；先写收件表 `biteship_webhook_events` 再回 200；异步按 `order_id` 调 `getOrder` 回查，以回查结果为准（伪造载荷不改状态）；处理失败留表、每 5 分钟重试（10 次后告警）；同一状态重复到达无副作用〔L1〕
**And** 每 6 小时对未签收的在途运单主动 `getOrder` 回查一次；接口请求日志对 `/webhooks/biteship/{secret}` 的密钥段脱敏〔L1〕
**And** 映射：`picked / in_transit / on_hold / dropping_off` 且订单 PENDING_SHIPMENT → SHIPPED（`shipped_source=BITESHIP_PICKED`）；`delivered` → DELIVERED（`DeliverySource.BITESHIP`）；`courier_not_found` → 标 `PICKUP_FAILED`；`return_in_transit / returned` → 标 `RETURNED`；15 个状态穷举单测〔L0 / L1〕
**And** 运营手工标记发货、手工运单（`provider=MANUAL`）保留；手工运单不出物流状态〔L1〕
**And** 自动送达兜底改为发货后 14 天；自动完成仍送达后 7 天〔L1〕
**And** 快递原因重发：原订单新建运单，旧运单写 `superseded_at` 保留；订单详情取最新运单〔L1〕
**And** 订单详情出参 `logistics{provider, courierName, waybillId, statusKey, exception}`，`statusKey ∈ awaiting_pickup | picked | in_transit | out_for_delivery | delivered`〔L1〕

### Story 5.2: 物流异常（后端）

As a 运营与买家,
I want 卡件、退回、丢件这些情况被标出来并暂停自动完成,
So that 有问题的订单不会被系统自动当成已收货。

**范围：** 迁移 ④ 中订单头 `logistics_exception / logistics_exception_at / logistics_exception_confirmed / claim_pending / timer_resumed_at`（D-13）；`LogisticsStallJob`；扫描排除；确认收货清标记；运营标记 / 确认 / 清除 service 方法（供后台线）。

**Acceptance Criteria:**

**Given** Biteship 运单 `provider_status_at` 超 3 天未更新且未签收 **Then** 每小时任务标 `STALLED`（用户侧不提示）〔L1〕
**Given** 订单带任一异常标记 **Then** 不参与自动送达与自动完成；清除标记后从 `timer_resumed_at` 重新计时〔L1〕
**When** 用户在任一异常下确认收货 **Then** 订单完成、标记清除、写运营提醒（日志 + 备注）〔L1〕
**Given** 运营确认 `RETURNED` 或 `LOST_DAMAGED` **Then** 订单详情 `logistics.exception = DELIVERY_ISSUE`（App 显示「配送异常，请联系平台客服」）；`PICKUP_FAILED`、`STALLED`、未确认 → 不显示异常、只显示最后物流状态〔L1〕
**And** `STALLED` 之后收到任何新物流状态 → 自动清除并从 `timer_resumed_at` 重新计时〔L1〕
**And** 揽收失败的三种处置（重下运单 / 手工运单 / 取消）执行前先调 `cancelWaybill` 取消旧运单〔L1〕
**And** `claim_pending` 由快递原因退回、丢件破损时置位（后台线展示「待索赔」）〔L1〕

---

## Epic 6: 商品、banner、订单列表与复购下线（后端）

### Story 6.1: banner 轮播与领券跳转、商品下架与评价（后端）

As a 用户,
I want 首页看到多张有效 banner、点进下架商品有明确提示、能看评价,
So that 不会点进空页。

**范围：** 迁移 ⑤（`shop_banners.start_at / end_at`，`target_type` 加 `COUPON`）、⑥（`shop_reviews.hidden_at`）；`GET /shop/banners`（D-16）；旧 `/shop/banner` 保留；商品详情 410（D-17）；`GET /shop/products/{token}/reviews`；评价开关；`hideReview` service；`GET /shop/products/top?limit=6`；`/me/shop/recommendations` 加 `profileSummary`（D-17）；banner 配置 service 方法（供后台线）。

**Acceptance Criteria:**

**When** `GET /shop/banners` **Then** 返回最多 6 张：active、在排期内、目标有效（商品在售；券在发放期、ACTIVE、未领完），按 `sort_weight DESC`；`CATEGORY` 目标下发 `TOKO_HOME`；无则空数组〔L1〕
**And** 旧 `GET /shop/banner` 行为不变（旧版 App）〔L1〕
**Given** 商品 `is_active=false` **Then** 详情返回 410 ProblemDetail `type=product-delisted`；不存在 404〔L1〕
**Given** 评价开关 `shop.review.display-enabled=false`（默认）**Then** 详情不带评价列表，只均分 + 条数；`true` → 详情带前 3 条，`/reviews` 分页返回全部〔L1〕
**And** `hidden_at` 非空的评价不返回、不计入均分与条数〔L1〕
**When** `GET /shop/products/top?limit=6` **Then** 在售商品按运营权重前 6，游客可用，带 `promo`〔L1〕
**When** `GET /me/shop/recommendations` **Then** 响应带 `profileSummary {petName, species, sizeClass, ageStage, weightMissing}`（取值沿用推荐理由词表，供栏头 A1 / A9）〔L1〕

### Story 6.2: 电商订单列表、订单详情扩展与复购下线（后端）

As a 买家,
I want 按状态看我的电商订单、订单详情信息完整,
So that 清楚每单到哪一步了、钱怎么付和怎么退。

**范围：** `GET /me/shop-orders?group=&cursor=`、`GET /me/shop-orders/counts`（D-18）；订单详情响应扩展（D-19）；确认收货接口不变；复购下线（D-20）。

**Acceptance Criteria:**

**When** `GET /me/shop-orders?group=` **Then** 按 `displayStatus` 分组（宽限期待支付归 `cancelled`），映射：`pending_payment`=PENDING_PAYMENT、`pending_shipment`=PENDING_SHIPMENT、`to_receive`=SHIPPED+DELIVERED、`completed`=COMPLETED、`cancelled`=CANCELLED+REFUNDING+REFUNDED、`all`；游标分页、按创建时间倒序〔L1〕
**When** `GET /me/shop-orders/counts` **Then** 返回 `pendingPayment`、`toReceive`〔L1〕
**And** 订单详情新增：金额明细列、`paymentMethods{coin, cash}`、`logistics`、`refunds[]`（token、状态、金额、`canChooseDestination`、`payoutFailed`）、`cancelledLines`、`reorderedToOrderToken / reorderedFromOrderNo`、`paidAt`、`cancelledAt`、`paymentDeadline`、`canPay`（`now < expires_at` 且有可用码或可出码）、`displayStatus`（宽限期待支付显示 CANCELLED，S-7）；存量订单新列为空时字段为 null〔L1〕
**And** 复购：日扫默认停用（`petgo.shop.repurchase.scan-enabled=false`）；`/me/shop/repurchase-cards` 返回空列表；不再产生 `REPURCHASE_FOOD_LOW`；`/me/shop/recommendations` 不受影响〔L1〕
**And** 通用 `/orders` 接口行为不变〔L1〕

---

## Epic 7: 结算与付款（App）

### Story 7.1: 一页结算（App，D1–D18）

As a 买家,
I want 在一个页面完成地址、快递、券、PawCoin 的选择并提交,
So that 提交后马上就能付款。

**范围：** `checkout_page_v3`（替换 v2，`/shop/checkout` 路由改指新页并支持 `?source=buy_now&sku=&qty=` 参数，供 8.2 立即购买；旧购物车「去结算」照常进入）；配送卡（地址抽屉 D4、页内新增地址 D5 复用地址表单组件、快递抽屉 D7、保价）；券选择抽屉（D10 / D11）；PawCoin 开关；提交失败处理；提交后调起支付面板（7.2 前先用现有面板）。

**Acceptance Criteria:**

**Given** 默认（D1）**Then** 配送卡（默认地址、最便宜快递、保价未勾）、商品折叠一行（缩略图 + 件数 + 金额，D18 点击展开）、优惠区（共省多少、满减一行、免运一行）、金额明细止于应付、支付方式区（PawCoin 开关 + 剩余 QRIS 金额 + 开封不退勾选默认已勾）、吸底栏（应付 +「已省」+ 提交）〔L0 / L2〕
**And** 每次进入默认地址；页内换地址只对本单；无地址（D2）地址区「新增收货地址」、不询价、提交不可用；不支持配送（D3）提示且不可提交〔L0〕
**And** 地址抽屉（D4）选择 / 页内新增（D5，存入地址簿）后重新试算；询价中（D6）骨架；询价失败（D8）提示 + 重试、不可提交〔L0〕
**And** 快递抽屉（D7）按价格升序（名称、服务、时效、运费）；所选快递不支持保价（D9）时隐藏保价选项〔L0〕
**And** 券（D10 / D11）：默认最优，可换、可不用；不可用券分组带原因；满额免运更优时（D12）免运行说明「满额免运」〔L0〕
**And** PawCoin：超上限（D13）显示套现提醒（数字取 `coinCap`）；足额（D14）吸底「全部用 PawCoin 支付」；不用（D15）全额 QRIS；取消勾选本次结算内保持〔L0〕
**And** 提交失败：部分商品（D16）居中弹窗逐行 →「Hapus & lanjut」移除后停在本页重算；券失效（D17）toast 并按最优重选停留；`not-placeable` / 其他 toast 文案按 PRD；`quote-expired` 静默重新试算〔L0〕
**And** 提交成功：纯币 → 支付成功页（上报 `toko_order_payment_succeeded`）；否则拉起支付面板；上报 `toko_order_submitted{coupon_ids, coin_used, courier, shipping_fee, insurance, ...}`；从立即购买进入时 `toko_checkout_page_viewed{entry: buy_now}`，返回详情不保存任何选择〔L0〕
**And** 所有金额只展示服务端 `PricingResult`，App 不自算（P-1）〔L0 评审〕
**And** 文案取 PRD §6 D 组；widget 测试覆盖 D1–D18；还原度 ≥ 95%〔L0 / L2〕

### Story 7.2: 支付面板与支付结果（App，E1–E7）

As a 买家,
I want 支付面板只显示我要扫码付的金额、付完能主动确认,
So that 付款不迷惑、不用干等。

**范围：** `qr_payment_sheet.dart` 加可选参数（P-11）；电商调用传入；「我已付款」接 `payment-check`；保存二维码埋点；支付成功页；超时与出码失败态；订单「去支付」拉起同一面板。

**Acceptance Criteria:**

**Given** 电商订单（E1）**Then** 面板显示倒计时、「需扫码支付」金额（只 QRIS 部分）、保存二维码、「我已付款」、「付款方法与支持的 App」折叠；无红色「取消支付」；高度自适应〔L0 / L2〕
**And** 保存二维码（E2）提示打开电子钱包或手机银行，上报 `toko_payment_qr_saved`〔L0〕
**When** 点「我已付款」（E3）**Then** 调 `payment-check` 显示查询中；已到账 → 支付成功页（E6：应付金额、「查看订单」「继续逛」，上报 `toko_order_payment_succeeded`）；未到账（E4）提示稍后〔L0〕
**And** 倒计时归零（E5）关闭面板、订单按已取消显示（S-7，`displayStatus`）；出码失败（E7）可重试，订单创建超 10 分钟服务端拒绝出码时提示订单已无法支付〔L0〕
**And** 送礼、AI 解锁、身份证高清、一次性解锁 / 月租调用方不传新参数，表现与改动前一致（现有测试全绿）〔L0〕
**And** 订单列表 / 详情「去支付」在 `canPay=true` 时拉起同一面板〔L0〕
**And** 文案取 PRD §6 E 组；widget 测试覆盖 E1–E7；还原度 ≥ 95%〔L0 / L2〕

---

## Epic 8: 逛 Toko、看商品、购物车（App）

### Story 8.1: Toko 首页与搜索（App，A1–A9）

As a 用户,
I want 打开 Toko 就看到活动 banner、给我的券和为宠物挑的商品,
So that 更快找到想买的东西。

**范围：** `toko_page_v3`（替换 v2）、搜索页无结果推荐；删除补货卡 / 引导条 / 品类 chip；`/shop?category=` 忽略参数；健康记录品类行、售罄「看替代品」改跳首页；新人弹窗；进入 Toko 调 `/me/shop/entry`；埋点。

**Acceptance Criteria:**

**Given** 有生效 banner（A1）**Then** 轮播（自动 + 手动 + 页码点，≤ 6 张）；点击按 `target_type` 跳商品详情 / 领券 / 外链（https）/ `TOKO_HOME` 留在首页；无 banner（A2）整块不渲染〔L0 / L2〕
**And** 领券 banner 点击 → 领取，成功 toast「已领取，可在我的券查看」（A5）、已领 toast「你已领取过这张券」〔L0〕
**Given** 已登录进入 Toko **Then** 调 `/me/shop/entry`（返回 `newbieCouponIssued=true` 时 App 上报 `toko_coupon_claimed{source: newbie}`）；「给你的券」横滑（A3）：新人券置顶「新人专享 / 已到账」，可领券「领取 / 已领取」，右上「我的券」入口；无券整块不出现；游客不显示〔L0 / L2〕
**Given** 游客进入且有新人券活动（A4）**Then** 弹窗（面额、门槛、「注册领取」进注册、关闭）；同设备每 `campaignToken` 只弹一次；上报 `toko_newbie_coupon_popup_shown / _tapped{action}`〔L0〕
**And** 「为 {宠物} 挑的」三列网格 ≤ 6 个，栏头推荐依据按 `profileSummary` 拼（A1），未填体重时栏头加提示与「填一下体重」引导（A9）；卡片只放图、名称、价格、快速加购〔L0 / L2〕
**And** 「全部商品」流无品类 chip；商品卡显示「Lagi promo」角标（`promo=true`）〔L0 / L2〕
**And** 搜索结果（A6）维持线上；无结果（A7）显示「换个词试试」+ 推荐商品（有档案用推荐、无档案用 top 6）〔L0〕
**And** 首页加载失败（A8）用通用重试态〔L0〕
**And** `/shop?category=xxx`、健康记录品类行、售罄「看替代品」、运营推送品类目标、旧复购推送 → 落 Toko 首页不报错〔L0〕
**And** 删除 `_RestockRow`、补货卡、引导条及 `toko_repurchase_*` 埋点；`ProfileRecoZoneV2` 等无引用复购组件随删；banner 埋点 `position` 取实际位次、`target_type` 含 `coupon`；`toko_coupon_claimed{source: home|banner}`〔L0〕
**And** 文案取 PRD §6 A 组；widget 测试覆盖 A1–A9；还原度 ≥ 95%〔L0 / L2〕

### Story 8.2: 商品详情（App，B1–B15）

As a 用户,
I want 在详情页看到能领的券、能不能送到、运费多少、评价,
So that 下单前心里有数。

**范围：** `product_detail_page_v3`、送达地区抽屉、领券抽屉、规格弹层（加购 / 立即购买）、全部评价页、商品已下架页；地区本地记忆；立即购买直达结算（不碰购物车）。

**Acceptance Criteria:**

**Given** 已登录有默认地址（B1）**Then** 上部顺序：图片 → 标题 → 价格 → 可领的券 → 「送至 {Kecamatan}」→「运费 Rp X 起」→ 免运进度〔L0 / L2〕
**Given** 未登录或无地址（B2）**Then** 「选择送达地区」→ 地区抽屉（B6，省 / 市 / 区，开通区域树），结果存本地（SharedPreferences）、不建地址；上报 `toko_delivery_area_selected{serviceable}`〔L0〕
**And** 不支持配送（B3）「该地区暂不支持快递配送」；已享减免（B4）；询价失败（B15）「运费暂时无法获取」点击重试；运费按当前所选 SKU 与数量询价〔L0〕
**Given** SKU 暂不可售（B5）**Then** 加购 / 立即购买按钮显示「暂不可售」不可点〔L0〕
**And** 领券抽屉（B7 / B8）：列出可领券，领取后变「已领取」并提示；未登录先登录〔L0〕
**And** 规格弹层：加购（B9）→ 提示「已加入购物车」留在详情、角标更新（B11）；立即购买（B10）→ 直达 7.1 结算页（`source=buy_now`），不写购物车；上报 `toko_coupon_claimed{source: detail}`（领券时）；未登录先登录后回详情〔L0〕
**And** 评价：开关开时页内 3 条 +「查看全部」进全部评价页（B12）；无评价「暂无评价」（B14）；开关关时维持均分 + 条数〔L0〕
**Given** 410（B13）**Then** 「商品已下架」页 + 回 Toko 首页；其他失败走通用重试〔L0〕
**And** 文案取 PRD §6 B 组；widget 测试覆盖 B1–B15；还原度 ≥ 95%〔L0 / L2〕

### Story 8.3: 购物车（App，C1–C8）

As a 买家,
I want 购物车里就能看到运费、优惠后的预估应付,
So that 决定买多少时心里有数。

**范围：** `cart_page_v3`；估算底栏；失效分组；空态推荐；删除撤销；去结算带勾选行。

**Acceptance Criteria:**

**Given** 有默认地址（C1）**Then** 底部免运进度 + 预估应付 +「已省 Rp X」（数值全取服务端 `estimate`）〔L0 / L2〕
**Given** 无地址或询价失败（C2）**Then** 「选择地址后计算运费」，预估不含运费〔L0〕
**And** 失效商品（C3）单独分组、不可勾选、可一键清理；未勾选（C4）去结算不可点；删除后可撤销（C5）〔L0〕
**And** 空购物车（C6）显示推荐商品（同首页卡片，可直接加购；有档案用推荐、无档案「你可能喜欢」top 6）；加载失败（C7）；未登录（C8）〔L0〕
**And** 「去结算」进结算页（`source=CART`），上报 `toko_checkout_page_viewed{entry: cart}`〔L0〕
**And** 文案取 PRD §6 C 组；widget 测试覆盖 C1–C8；还原度 ≥ 95%〔L0 / L2〕

---

## Epic 9: 订单、退款、我的券、地址簿（App）

### Story 9.1: 电商订单列表与订单详情（App，F1–F12、F17–F22）

As a 买家,
I want 按状态找订单，详情看清金额、物流、退款和重新下单的来龙去脉,
So that 不用问客服也知道订单情况。

**范围：** `order_list_page_v2` 购物二级分组 + 计数 + 我的券入口；`shop_order_detail_page_v3`；确认收货去二次确认；配送异常 + 联系客服；部分取消行；重下单跳转；`SHOP_ORDER_REORDERED` 通知深链与文案。

**Acceptance Criteria:**

**Given** 选中「购物」**Then** 出现「全部 / 待付款 / 待发货 / 待收货 / 已完成 / 已取消」，待付款与待收货显示数量；「我的券」入口只在购物下；待付款行（F1）可「去支付」；待收货（F2）；空态（F3）；加载失败（F18）〔L0 / L2〕
**And** 订单详情状态文案与操作按 PRD §3.0 订单状态表：待付款（F4，倒计时、去支付、取消订单 → 确认 F5）、待发货（F6）、待收货（F7，物流状态）、已签收（F9）、已完成（F11）、已取消（F12）〔L0〕
**And** 金额明细逐行至应付 + 支付方式区（PawCoin / QRIS）；存量订单字段为空时降级为线上展示〔L0〕
**And** 物流：快递名、运单号（可复制）、当前状态；手工运单只显示快递名、运单号、「去承运商官网查询」〔L0〕
**And** 配送异常（F10）「配送异常，请联系平台客服」+「联系客服」与「确认收货」并列；上报 `toko_delivery_exception_shown / _cs_tapped`〔L0〕
**And** 确认收货无二次确认，toast 用 PRD §6 定稿文案（与 UI 稿冲突时以 PRD §6 为准）〔L0〕
**And** 宽限期待支付订单（`displayStatus=CANCELLED`）显示已取消、无「去支付」（S-7）〔L0〕
**And** 部分商品被取消（F17）：行标已取消并列退款金额；原订单「已为你重新下单」可跳新单（F20）；新单注明「由订单 #xxx 重新下单」（F21）；重新下单通知（F22）深链新订单〔L0〕
**And** 文案取 PRD §6 F 组；widget 测试覆盖所列屏；还原度 ≥ 95%〔L0 / L2〕

### Story 9.2: 选择退款方式与线下打款（App，F13–F16、F19、F23、F25）

As a 被取消订单的买家,
I want 自己选退成 PawCoin 还是打到银行卡,
So that 按我方便的方式拿回钱。

**范围：** 恢复退款方式页路由（只从订单详情「选择退款方式」进入；退货申请仍为不可用页）；`refund_destination_page` 复用 `refund_method_page_v2` 组件；接 `refunds/{token}/destination`。

**Acceptance Criteria:**

**Given** 退款单待选（F13）**Then** 订单详情出现「选择退款方式」→ 退款方式页（F14）：退为 PawCoin（平台原因显示补偿额）/ 线下打款；用户原因与迟到款（F23）不显示补偿〔L0〕
**When** 选线下打款（F15）**Then** 选 BCA / OVO / GoPay（三渠道都开放，以 PRD 11-05 为准，UI 稿头部「只有 BCA」的说明作废）、填账户，显示手续费与实到金额；提交后详情「退款处理中，预计 3 个工作日内到账」（F16）〔L0〕
**And** 已退款（F19）；打款失败（F25）「退款打款失败，请重新填写收款账户」可重进退款方式页重填或改选 PawCoin〔L0〕
**And** 退货申请入口仍关闭（路由仍指向不可用页）〔L0〕
**And** 文案取 PRD §6 F 组；widget 测试覆盖所列屏；还原度 ≥ 95%〔L0 / L2〕

### Story 9.3: 我的券与兑换（App，G1–G9）

As a 用户,
I want 一个地方看我能用的券、输入兑换码,
So that 知道手里有什么券、快过期的先用掉。

**范围：** `/me/coupons` 我的券页；兑换码输入；`COUPON_EXPIRING` 推送深链与通知文案；推送点开埋点。

**Acceptance Criteria:**

**Given** 可用（G1）**Then** 券卡（面额、门槛、有效期、48h 内「即将过期」标签）；已使用（G2）；可用为空（G4）；加载失败（G5）；过期券不展示〔L0 / L2〕
**When** 兑换成功（G6）**Then** 提示并刷新列表、上报 `toko_coupon_claimed{source: code}`；失败（G7）按 `type` 显示对应文案；锁定（G8）「尝试次数过多，请稍后再试」；未登录先登录〔L0〕
**And** 券快过期推送（G9）点开进我的券，上报 `toko_coupon_expiry_push_opened{coupon_count}`；通知中心 `COUPON_EXPIRING` 文案与深链；推送设置无需新增分类（归 PROMO）〔L0〕
**And** 文案取 PRD §6 G 组；widget 测试覆盖 G1–G9；还原度 ≥ 95%〔L0 / L2〕

### Story 9.4: 地址簿与复购清理收尾（App，H1–H5）

As a 用户,
I want 地址簿里一眼看出哪个地址送不到,
So that 下单前就能换。

**范围：** 地址簿标注不支持配送；地址表单抽成可在结算页内嵌的组件（8.2 已复用，此处完成地址簿侧）；地区选择抽屉（H4）；删除确认（H5）；Diary 复购卡删除；`petProfileWeightHint` 文案。

**Acceptance Criteria:**

**Given** 地址簿（H1）**Then** 按接口 `serviceable=false` 标「暂不支持快递配送」（`null` 不标）；空态（H2）〔L0 / L2〕
**And** 新增 / 编辑（H3）与结算页内同一组件；地区选择（H4）；删除确认（H5）〔L0〕
**And** Diary 成长档案复购卡（`RepurchaseTriggerCardV2`）及其埋点删除；`repurchase_zones_v2.dart` 中仍被引用的推荐组件保留〔L0〕
**And** `petProfileWeightHint` 改为只说用于推荐合适的商品（PRD §6）〔L0〕
**And** 复购相关 arb key、repository 方法无引用后删除；`flutter analyze` 与全量测试绿〔L0〕
**And** 文案取 PRD §6 H 组；widget 测试覆盖 H1–H5；还原度 ≥ 95%〔L0 / L2〕

---

## Epic 10: 端到端联调

### Story 10.1: 下单、付款、取消、退款全链路联调（L1 / L2）

As a 研发与验收人,
I want 资金相关的整条链路在真实后端与真机上跑通,
So that 上线前确认钱的每一步都对得上。

**范围：** 不新增功能；按 CLAUDE.md「后端 → 前端 → 联调」补端到端验收。用 `BITESHIP_MODE=stub`（L1）/ `sandbox`（L2）、GemPay stag 通道（或改意图状态的 stag 付款模拟）、D-24 stag 入口造数；产出验收记录 `implementation-artifacts/v1.4.2/acceptance-v1.4.2-shop.md`。

**Acceptance Criteria:**

**Given** 真后端 + Android 模拟器 **Then** 依次跑通并记录截图与接口日志：领券 → 加购 → 结算（换地址、换快递、保价、换券、关 PawCoin）→ 提交 → 支付面板 →「我已付款」→ 支付成功 → 订单详情〔L2〕
**And** 立即购买路径：不影响购物车；返回详情不保存〔L2〕
**And** 纯 PawCoin 足额：提交即成功、币在提交时扣、财务看板口径一致〔L1 / L2〕
**And** 超时：60 分钟后列表显示已取消、码失效后服务端取消，币与券退回、库存回补；宽限期内付款 → 订单变待发货〔L1〕
**And** 关单后到账：迟到款记到原订单 → stag 入口重新下单（多退少补各一例）→ 用户收到通知、新旧订单互相跳转；另一例退款 → 选择退款方式〔L1 / L2〕
**And** 异常取消：部分取消一行（含满减分摊与币取整）、整单平台原因（选 PawCoin 得补偿）、用户原因退回（入库后才出退款单）、签收后破损赔付；线下打款 → 标记已打款 → 已退款；打款失败 → 重填；每一步核对成长值扣回与退款单金额〔L1 / L2〕
**And** 旧版 App（1.4.1 包）在新后端上仍可完成下单与付款〔L2〕
**And** 通知：券快过期推送、重新下单通知在真机收到且点开落对页面〔L2〕

### Story 10.2: Biteship 物流全链路联调（L2）

As a 研发与验收人,
I want 在 Biteship 沙箱里把运单与物流状态走一遍,
So that 上线后发货与签收能自动流转。

**范围：** `BITESHIP_MODE=sandbox`；沙箱控制台手动触发 Webhook；产出记录写入同一验收文件。

**Acceptance Criteria:**

**Given** 已付款订单 **When** stag 入口下运单 **Then** 拿到运单号、订单仍待发货；控制台依次触发 `allocated → picked → in_transit → dropping_off → delivered`，App 订单详情物流状态逐段变化，`picked` 后订单进待收货、`delivered` 后已签收〔L2〕
**And** 触发 `courier_not_found` → 揽收失败标记，用户侧不提示；触发 `returned` → 运营确认后用户侧「配送异常」+ 联系客服〔L2〕
**And** 伪造 Webhook（错误密钥 / 篡改载荷）不改变订单状态；请求日志中密钥已脱敏〔L1 / L2〕
**And** 询价次数核对：同一包裹 30 分钟内详情、购物车、结算三处只计 1 次 Biteship 询价〔L2〕
**And** 正式 key 开通、Webhook 地址、发货地信息、存量 SKU 包裹参数与区域邮编补齐情况记入验收文件的「上线前检查」〔L2〕

---

## 校验结论（2026-11-05，就绪度评审后回改）

- 需求 10 组共 63 条（含埋点）与 UI 95 屏全部映射到 story（见 FR Coverage Map）；后台页面按 S-0 不在本线，后台业务的 service 层分布在 2.1 / 4.1 / 4.2 / 5.1 / 5.2 / 6.1；后台线另需承接 FR-136 的两项后台改动（喂量警告、复购看板）。
- 迁移按首个需要的 story 建：①②⑧ → 1.1，⑦ → 2.1，⑪ → 2.3，③ → 3.2，⑩ → 3.3，⑨ → 4.1，④ → 5.1，⑤⑥ → 6.1。
- 无前向依赖：3.1 用 1.1 报价与 1.2 计价器；3.2 用 2.1 账本；App 先结算（7.1）后支付面板（7.2），再首页、详情（立即购买进 7.1 已有的新结算页）、购物车；9.1 的退款入口在 9.2 完成前隐藏。
- 就绪度评审（`readiness-v1.4.2-shop.md`）B1–B3、I1–I15、m1–m11 已回改，拍板 S-3 ~ S-9 见决策日志。
- 最重的 story：3.2（提交 + 扣币时机 + 旧结算模式）、3.3（四条过期路径）、7.1（结算页 18 屏）。云端上下文不足时 7.1 可拆为「结构与配送」+「券、PawCoin 与提交失败」。
