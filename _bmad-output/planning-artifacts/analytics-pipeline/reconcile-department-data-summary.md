# 团队数据需求 × PRD 对账

> 日期：2026-11-11 · 只读对账，未改 prd.md
> 输入：[department-data-summary.md](department-data-summary.md)（英文原文为准）、[department-data-summary.zh.md](department-data-summary.zh.md)、[prd.md](prd.md)、[decisions.md](decisions.md)
> 方法：逐条检查 §0 通用规则和 B、C 类指标，看所需事件 / 数据来源是否有 FR 覆盖、口径细节是否被保留；复核 A/B/C 分类；在 `petgo_app/lib` 里 grep 核实事件名。
> 判定：✅ 覆盖且口径保留 · ⚠️ 能力在，但口径细节没写进 PRD（容易在汇总实现时丢）· ❌ PRD 缺能力或数据源

---

## 1. 结论摘要

**最严重的 5 个缺口**（详见 §5）：

1. **升级用户会被误报为新安装**：FR-3 用「本地无安装标记」判定首次启动，但 V1.5.0 是覆盖升级，存量用户升级后第一次启动也没有标记 → 发版当周 S2 安装、S3 注册率、市场 D1/D3/D7、产品 #1 全部被污染。
2. **分类漏项、分错类**：内容 #1（新用户 vs 回访用户）在 A/B/C 三类里都没有，PRD 也没有覆盖，但它要用到 S1 日活，应归 C 类；电商 #9（售罄高相关商品）要用商品详情浏览事件，被错归 A 类；电商 #6 最后一步「已支付」要查业务库，被错归 B 类（应归 C）。
3. **「日活」有两个定义，PRD 没说清各用哪个**：D9 定日活以 GA4 为准，但团队 S1 定义是「`Application Opened` 去重人数」，S4 和产品 #3 都用 S1 做分母。GA4 的数字不在 Postgres 里，AI 查数没法拿它当分母。PRD 没写清楚 S1 报表和这两个比率的分母各用哪个日活。
4. **推送「全部」做不到**：内容运营要的是全部推送。但端上只有运营通知（`OPS_ANNOUNCEMENT`）和券快过期（`COUPON_EXPIRING`）两类推送有打开事件；点赞、评论、等级、订单等系统推送点开都没有埋点，而且 `toko_coupon_expiry_push_opened` 不带推送 id，没法按单条推送算打开率。FR-20 只写了 `ops_notification_campaigns`。
5. **大量具体口径没写进 PRD**：周一到周日的周、S1 的周统计和工作日 / 周末拆分、留存按安装组人数加权、D3/D7 只统计已满 N 天的安装组、W1 = 引导完成后第 7–13 天、漏斗「按顺序、7 天内」、加购后 7 天内支付。FR-18 只有一句「按团队定义计算」，交给下游很容易丢。

其余：购物车停留时长（电商 #7）缺数量 / 价格、缺「减数量」事件；`onboarding_completed` 在 PRD 中没有出现；新旧版本过渡期，只有日活对账说明了覆盖率，其他 B、C 类指标都没有覆盖率说明。

---

## 2. §0 通用规则

| 规则 | 团队口径 | PRD 对应 | 判定 | 说明 |
|---|---|---|---|---|
| 时区 | 一天 = WIB 自然日 | FR-18「WIB 凌晨算前一个 WIB 自然日」 | ✅ | 日没问题 |
| 一周 = 周一到周日 | 同上 | — | ⚠️ | PRD 全文没出现「周一到周日」；FR-18 只写「周指标按团队定义」。建议写进 FR-18 或口径文档的必填项 |
| 只算生产数据 | 排除 staging、模拟器、TestFlight、侧载、非生产服务端事件、内部员工、管理员、虚拟账号、测试账号 | FR-6 环境标记 + FR-13 生产标记 + §8 开放问题 1、3 | ⚠️ | 能力在。但「虚拟账号」（种子作者 id 1–20 等）和「管理员」账号的识别来源没写：FR-13 只说「后端维护的内部账号名单」，开放问题 3 也只问了员工名单。另外游客事件（无用户）只能靠环境标记排除，内部员工用游客身份测试排除不了，这一点应写成已知偏差 |
| 按人去重，日去重人数不跨天相加 | — | FR-18 | ✅ | 注意：S1 的「周合计 = 每日日活相加」是团队明确要的，和这条规则不冲突（团队自己定义成了相加），见 §3 S1 |
| 只算 PAID | — | FR-19（C 类只计 PAID） | ✅ | 退款不在 B、C 范围（S6 是 A 类） |

---

## 3. B、C 类指标逐条

### 3.1 共用指标

| 编号 | 需要的数据 | 事件是否存在（代码核实） | PRD 覆盖 | 被丢掉 / 没写明的口径 | 判定 |
|---|---|---|---|---|---|
| **S1 日活** | `Application Opened` 去重人数（含游客） | 现由 PostHog SDK 自动上报，没有自埋（G1） | FR-3、FR-5、FR-13、FR-22 | ① 周合计 = 每日日活相加、周平均、最高 / 最低日、环比、**周一至周五 vs 周六周日平均**，PRD 都没写；② **S1 报表用自建还是 GA4**，D9 说以 GA4 为准，但 GA4 数字不进库，AI 查数只能算自建的 | ⚠️ |
| **S2 安装** | 首次 `Application Installed` 去重，分 Android / iOS | 同上，自动上报 | FR-3、FR-6（平台） | ❌ **升级误报**：V1.5.0 覆盖安装到旧版本上，旧版没有自建安装标记，第一次启动会报 `Application Installed`，存量用户全被算成新安装。PostHog 当年靠 SDK 自存的版本信息区分 Installed / Updated，自建要自己做（例如检查旧版本留下的 prefs / secure storage 键，或旧 PostHog 的本地存储；有就算升级，报 `Application Updated` 或不报）。② 「首次」按设备（匿名 id）算，卸载重装算新安装，FR-3 已注明 | ❌ |
| **S3 注册 / 注册率** | 首次 `signup_succeeded` 去重 ÷ S2 | `signup_succeeded` 存在（`analytics.dart:283`，唯一入口） | FR-18（B 类兜底） | 注册率分母是 S2，会受升级误报牵连；产品部门要按月看，月口径没写 | ⚠️ |
| **S4 电商交易占日活比** | 每日有已支付 Toko 订单的人数（业务库）÷ S1；按月取每日值的平均 | 分子在业务库，分母用 S1 | FR-19 | ① 分母用哪个日活（同 S1 第 ② 点）；② 「月 = 每日比率的平均」，不是月汇总再相除，PRD 没写；③ 支付人数按支付日（WIB）归日，没写 | ⚠️ |
| **S5 弹窗 / banner** | 每个运营位的曝光、点击、点击率；推广 Toko 的还要「点击后 7 天内已支付 Toko 订单」 | `ops_popup_shown/tapped`（带 `popup_id`）、`toko_banner_shown/tapped`（带 `banner_id`）、`toko_newbie_coupon_popup_shown/tapped` 存在；`growth_backfill_popup_shown` 只有曝光没有点击；没有找到首页 / 非 Toko banner 事件 | FR-15、FR-19 | 「7 天内」是从点击算起（每次点击各开一个窗口，还是按人算第一次点击），没写；归因去重（同一订单被多个运营位认领）没写 | ⚠️ |
| **S5 推送** | 期间发送条数；每条推送的接收人数、打开数、打开率；内容运营要全部推送 | `ops_notification_opened`（带 `campaign_id`，只有 `OPS_ANNOUNCEMENT`）；`toko_coupon_expiry_push_opened`（**不带推送 id**，只有 `coupon_count`）；`push_service.dart` 里其他系统推送（点赞、评论、`GROWTH_LEVEL_UP`、`SHOP_ORDER_REORDERED` 等）点开**没有任何埋点** | FR-20 | ❌ ① 「全部推送」做不到：只有两类推送有打开事件；② 券快过期推送不能按单条算打开率；③ 发送记录只写了 `ops_notification_campaigns`，生命周期推送（`V20260821_1646__init_lifecycle_push`）、券到期推送、互动通知的发送记录在哪里，没有列出来。建议在 `PushService` 点击回调里统一补一个「推送打开」事件，带 `type` 和后端下发的推送 id | ❌ |

### 3.2 电商

| 编号 | 需要的数据 | 事件是否存在 | PRD 覆盖 | 被丢掉 / 没写明的口径 | 判定 |
|---|---|---|---|---|---|
| **#4 打开 Toko 的人数** | `$screen = toko_page` | 存在：`app_shell.dart:117` `Analytics.screen('${tab.analyticsName}_page')`，`bottom_tab_bar.dart:21` `shop('/shop','toko')`；深链入口 `app_router.dart:480/585` 也会报 | FR-4 | 自建以后事件名是 `$screen` 还是自定义名，需要在口径文档里对上 | ✅ |
| **#5 加购到支付转化** | 加购人数；其中 7 天内支付 Toko 订单的人数 | `toko_add_to_cart_tapped` 存在（`product_detail_page_v3.dart:1029`、`sku_picker_sheet.dart:69`） | FR-19 | ① 「7 天内」从加购时间算，没写进 PRD；② 加购事件在**点击时**上报，接口失败也会算一次（`sku_picker_sheet.dart:69` 先 capture 再 perform），应写进已知偏差；③ 7 天窗口跨期：期末加购的人观察期不足，要不要等满 7 天，没写 | ⚠️ |
| **#6 Toko 漏斗** | 7 步，按顺序、7 天内，最后一步「已支付」 | 前 6 步事件全部存在：`toko_page`、`toko_product_shown`、`toko_product_detail_viewed`、`toko_buy_now_tapped` / `toko_add_to_cart_tapped`、`toko_order_submitted`、`toko_order_pay_tapped` | FR-17、FR-18、UJ-1 | ① **「按顺序、7 天内」没写进 PRD**（UJ-1 只写了 7 步）；② 最后一步「已支付」要查业务库 → 应归 C 类（见 §4）；③ `toko_product_shown` 在搜索页 / 瀑布流也会报（`shop_search_page.dart`、`shop_product_masonry.dart`），第 2 步要不要限定在 Toko 页内的曝光，没定 | ⚠️ |
| **#7 购物车内容** | 期间每个加购商品：哪个商品、谁、何时；停留时长（到买下 / 删除 / 期末）；状态；加购人数、每车商品数、购物车总值（价格 × 数量） | 加购事件只带 `product_token`、`sku_token`，**不带数量和价格**；没有移除事件（G5，FR-16 补）；`shop_cart_items` 有 `qty`、`created_at`，同一 SKU 再加购只加数量、不新增一行，删除是物理删除 | FR-16、FR-19 | ① 价格 × 数量从哪里取，没写：事件里没有，业务表只有当前快照；② **减数量**（qty 改小但没删）不算移除，FR-16 没覆盖；③ 「买下」按同一用户、同一 SKU、加购之后的已支付订单行匹配，规则没写；④ 「期末仍在购物车」需要期末快照，业务表只有当前状态，回算历史期间做不到。建议加购和移除事件都带 `qty`，期末状态由事件流推算 | ⚠️ |
| **#8 高潜力 Top 3 商品** | 每个商品的详情浏览次数 + 加购次数 | 事件存在，都带 `product_token` | FR-18 | 「次数」不是人数，PRD 没区分；`toko_buy_now_tapped` 不计入，团队原文也只写了加购，要照原文执行 | ✅ |

### 3.3 市场

| 编号 | 需要的数据 | PRD 覆盖 | 被丢掉 / 没写明的口径 | 判定 |
|---|---|---|---|---|
| **#1 D1 留存** | 按首次 `Application Installed` 分日组，看第 X+1 天有没有 `Application Opened` | FR-3、FR-5、FR-14、UJ-2 | ① **按安装组人数加权**的周平均没写；② 天按 WIB 切分没写；③ 受 S2 升级误报污染；④ 按设备（匿名 id）计，PRD UJ-2 写的是「关联到注册后的用户」，团队定义按安装算，单位应是设备而非账号，需要说清 | ⚠️ |
| **#2 D3 留存** | 同上，第 X+3 天；**只统计安装满 3 天的用户** | 同上 | 「只统计满 N 天」没写 | ⚠️ |
| **#3 D7 留存** | 同上，第 X+7 天；只统计满 7 天的 | 同上 | 同上 | ⚠️ |

### 3.4 产品

| 编号 | 需要的数据 | 事件是否存在 | PRD 覆盖 | 被丢掉 / 没写明的口径 | 判定 |
|---|---|---|---|---|---|
| **#1 安装后建档率** | 当月 `pet_profile_create_submitted` 去重人数 ÷ 当月安装（S2） | 存在（`pet_profile_create_page.dart:117`） | FR-18 | ① 分子按用户、分母按设备，要靠 FR-14 身份关联换算，没写；② 多宠物线上第二只宠物建档也会报这个事件，按人去重可以接受，但分子不限「当月安装的人」，这是团队原文的口径，要原样写进口径文档；③ 受升级误报污染 | ⚠️ |
| **#2 Toko 支付成功率** | 已支付订单（业务库）÷ `toko_order_submitted` | 存在（`checkout_page_v3.dart:1239`） | FR-19 | 分子是订单数、分母是事件次数，单位不一致（同一订单重复提交、提交失败也报）；`toko_order_submitted` 带不带订单 token、能不能逐单关联，没核对 | ⚠️ |
| **#3 非电商付费率（占日活）** | 每日有非 Toko 付费解锁的人数 ÷ S1；按月取每日值的平均 | 分子在业务库 | FR-19 | 同 S4：分母用哪个日活、按月取平均；「其他非 Toko 功能」包括哪些，要和 S6 的类型清单对齐 | ⚠️ |
| **#5 新用户 W1 留存** | 完成引导的用户，**引导完成后第 7–13 天**任意一天有 `Application Opened` | `onboarding_completed` 存在（`pet_status_page.dart:29`，带 `pet_status`）；后端登录响应也有 `onboardingCompleted` 字段 | FR-18（兜底） | ① **PRD 全文没有出现 `onboarding_completed` 和 W1 定义**，「第 7–13 天」窗口、按引导完成日分组都没写；② 存量用户升级 V1.5.0 后不会再报 `onboarding_completed`（已完成），覆盖率要等新用户自然积累，需要写明 | ⚠️ |

---

## 4. A/B/C 分类复核

| 指标 | decisions.md 归类 | 应归 | 理由 |
|---|---|---|---|
| **内容 #1 新用户 vs 回访用户** | **未归类**（A 写的是「内容 #2–#8」，B、C 都没有） | **C** | 新用户 = 当天注册（业务库 `users` 或 `signup_succeeded`），回访用户 = S1 日活 − 新用户，必须用埋点日活。目前 PRD 和 decisions 都没覆盖，属于漏项 |
| **电商 #9 售罄高相关商品** | A | **C** | 排序 = 近 30 天实际销量（业务库）**+ 商品详情浏览次数（`toko_product_detail_viewed`，埋点）**。只用业务库算不出来 |
| **电商 #6 Toko 漏斗** | B | **C** | 最后一步「已支付」要用业务库订单状态 |
| **S5 推送部分** | 没明确归类（B 只写了 S5 曝光 / 点击，C 只写了 7 天内订单） | **C** | 发送 / 接收取后端发送记录，打开取端上事件；FR-20 已按 C 处理，decisions 对照表应补上 |
| 产品 #2 Toko 支付成功率 | C | C（可讨论） | 分母理论上可以改用业务库「已创建订单」变成 A 类，口径更稳；但团队原文指定用 `toko_order_submitted`，照原文就是 C |
| S3 注册 | B | B（可讨论） | 注册数也可以用业务库 `users.created_at`；团队原文用 `signup_succeeded`，照原文是 B。注册率分母是安装，所以仍需要埋点 |
| 其他 A 类（S6、电商 #1 #2 #3 #10、内容 #2–#8、产品 #4） | A | A | 核对无误。内容 #8 Top 3 帖子的点赞、评论在业务库；内容 #3、#4、#5 覆盖率的分母是注册用户，也在业务库 |

---

## 5. 缺口清单（按严重度）

| # | 严重度 | 缺口 | 影响指标 | 建议 |
|---|---|---|---|---|
| R1 | 高 | FR-3 用安装标记判定首次启动，没区分「升级」和「新装」，V1.5.0 升级用户会全被算成新安装 | S2、S3 注册率、市场 #1–#3、产品 #1 | FR-3 增加验收条件：已有旧版本痕迹（本地已有登录态 / prefs 键）时视为升级，不报 `Application Installed`（可以报 `Application Updated`）；口径文档注明发版当周的处理方式 |
| R2 | 高 | 内容 #1 漏项；电商 #9 应归 C 类却被归为 A 类；电商 #6、S5 推送分类不准 | 内容 #1、电商 #9、电商 #6 | 修订 decisions.md 对照表；PRD §5「A 类」删掉电商 #9；FR-19 的 C 类清单补上内容 #1、电商 #6、电商 #9 |
| R3 | 高 | 日活定义二义：D9 以 GA4 为准，团队 S1 是 `Application Opened` 去重人数，GA4 数字不在库里 | S1、S4、产品 #3、内容 #1 | PRD 明确：对账期内 S1 报表给出哪个数（建议报自建数并同时附上 GA4 数），比率分母一律用自建日活，口径文档注明覆盖率偏差 |
| R4 | 高 | 推送「全部」无法支撑：只有两类推送有打开事件，券到期推送不带推送 id，系统推送的发送记录来源没有列出 | S5 推送 | FR-20 补：端上统一的「推送打开」事件（带 `type` 和推送 id）；列出每类推送的发送记录表；做不到的类型写进已知偏差 |
| R5 | 中 | 具体口径没写进 PRD：周一到周日、S1 的周统计和工作日 / 周末拆分、留存按组加权、D3/D7 只统计满 N 天的安装组、W1 第 7–13 天、漏斗按顺序 7 天内、加购后 7 天、月比率取每日平均 | S1、S4、电商 #5 #6、市场 #1–#3、产品 #3 #5 | FR-21 口径文档加一条验收：团队原文的每个窗口 / 加权 / 分组规则逐字落进口径条目；或在 FR-18 列出这些规则 |
| R6 | 中 | 电商 #7：加购 / 移除事件不带数量，减数量没有事件，期末快照算不出来，价格来源没写 | 电商 #7 | FR-16 扩展：加购 / 移除 / 改数量都带 `sku_token` 和 `qty`；价格按事件发生时的 SKU 价格取（从业务库复制进埋点库） |
| R7 | 中 | `onboarding_completed` 在 PRD 中没有出现；FR-17 只核对 Toko 漏斗事件 | 产品 #5 | FR-17 扩大为「B、C 类指标涉及的全部事件」核对（加上 `onboarding_completed`、`signup_succeeded`、`pet_profile_create_submitted`、运营位事件） |
| R8 | 低 | 过渡期覆盖率只在日活对账里说明，其他 B、C 类指标在用户升级期间都偏低 | 全部 B、C 类 | FR-18 / FR-21：汇总结果附「新版覆盖率」，口径文档注明过渡期起止 |
| R9 | 低 | 「虚拟账号 / 管理员」的识别来源没写；内部人员以游客身份测试无法排除 | §0 生产数据 | 开放问题 3 扩大为「内部 / 虚拟 / 管理员 / 测试账号名单」；游客侧写进已知偏差 |
| R10 | 低 | 加购事件在点击时上报，`toko_order_submitted` 在提交时上报，都不等于接口成功 | 电商 #5 #6 #7、产品 #2 | 写进已知偏差，或改到接口成功后再上报（需要和团队确认口径） |

---

## 6. 事件名核实（`petgo_app/lib`）

| 事件 | 是否存在 | 位置 | 备注 |
|---|---|---|---|
| `Application Opened` / `Application Installed` | 否（PostHog SDK 自动） | `analytics.dart` | G1，FR-3 自实现 |
| `toko_page`（页面浏览） | 是（拼接生成） | `app_shell.dart:117`、`app_router.dart:480/585` | `'${tab.analyticsName}_page'`，Toko Tab 的 `analyticsName = 'toko'` |
| `signup_succeeded` | 是 | `analytics.dart:283` | 唯一上报入口 |
| `onboarding_completed` | 是 | `pet_status_page.dart:29` | PRD 中没有出现 |
| `pet_profile_create_submitted` | 是 | `pet_profile_create_page.dart:117` | 带 `species` |
| `toko_product_shown` | 是 | `shop_product_card.dart:133`、`shop_product_masonry.dart:104` | 带 `product_token`、`zone`；搜索页也会报 |
| `toko_product_detail_viewed` | 是 | `product_detail_page_v3.dart:118` | 带 `product_token` |
| `toko_buy_now_tapped` / `toko_add_to_cart_tapped` | 是 | `product_detail_page_v3.dart:1024/1029`、`sku_picker_sheet.dart:69` | 带 `product_token`、`sku_token`，**不带数量** |
| `toko_order_submitted` | 是 | `checkout_page_v3.dart:1239` | |
| `toko_order_pay_tapped` | 是 | `shop_payment_launcher.dart:118` | |
| `ops_popup_shown` / `ops_popup_tapped` | 是 | `ops_popup_coordinator.dart:275/263` | 带 `popup_id` |
| `toko_banner_shown` / `toko_banner_tapped` | 是 | `shop_banner_carousel.dart:182`、`toko_page_v3.dart:153` | |
| `toko_newbie_coupon_popup_shown` / `_tapped` | 是 | `newbie_popup.dart:22` | |
| `growth_backfill_popup_shown` | 是（**没有点击事件**） | `backfill_popup.dart:32` | 如果算运营位，要补点击事件 |
| `ops_notification_opened` | 是 | `push_service.dart:55`、`notification_center_page.dart:335` | 只有 `OPS_ANNOUNCEMENT` |
| `toko_coupon_expiry_push_opened` | 是 | `push_service.dart:68` | **不带推送 id** |
| 其他系统推送的打开事件 | **否** | `push_service.dart` | 点赞、评论、等级、订单等推送都没有 |
| 移出购物车 | **否** | — | 只有 `toko_cart_clear_invalid_tapped`（按钮级，不按商品），FR-16 补 |
