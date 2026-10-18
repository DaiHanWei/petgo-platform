# Story 3.1: 后台礼物目录与送礼 QRIS 最低金额

Status: review

> 版本 v1.4.0 · 主题 gift · 分支 `feat/1.4.0-gift`（从 `dev_1.4.0` 地基之后切出，与 ①② 并行）
> 口径优先级：`decision-log.md` ③ 节 > `送礼/1-4-0送礼prd.md`（§3.3 AB-35）> `architecture-v1.4.0-gift-delta.md`（AD-1 / AD-4 / AD-11、§5、§6）> `送礼/ui-admin-1-4-0-gift-integrated-v1.html`（L1 ~ L7）（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。跨分支契约以 `_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。
> 前置（同分支、按 sprint 顺序已完成）：**Story 1.1**（`V20261018_1700` 四表 + `V20261018_1701` `pricing_config.gift_qris_min_amount`、礼物目录 / 订单实体与仓储、`GET /api/v1/gifts/catalog`、送礼发起时读 QRIS 最低金额）、**Story 1.2 / 1.3 / 2.1 ~ 2.3**（本 story 不依赖其代码，但按顺序已合入）。
> 范围：**后端 + 后台模板（Thymeleaf + htmx）**。App 零改动。**无新迁移**（`gift_qris_min_amount` 已在 1.1 的 `V20261018_1701`；`config_change_logs.config_type = 'PRICING'` 已允许，不需放开 CHECK）。

## Story

As a 运营,
I want 在后台管理礼物目录（新建、编辑、上下线）和送礼 QRIS 最低金额,
so that 不发版也能上新、调价、下线礼物，并控制小额送礼只能用 PawCoin。

## Acceptance Criteria

**AC1 — 页面与导航** `[L0]` `[L1]`（PRD §3.3 页面位置 / AD-11 / UI L1）
1. 新页 `GET /admin/gift-catalog`，登记进 `AdminPageCatalog.PAGES`，组 `G_CONFIG`，**紧跟在 `algo-params` 之后**（`AdminPageCatalog.java:248-249` 与 `share-reward` :250-251 之间）；页 key `gift-catalog`，`activeKey = "gift-catalog"`，`navKey = "admin.nav.giftCatalog"`。UI 稿侧栏顺序是「算法参数 → 成长体系 → 礼物目录」，「成长体系」是 ① 的页，本分支没有，合并时见 Dev Notes「与 ① 合并」。
2. 页面形态照「用户标签」页（`user-tags.html`，模板 B `tpl-b-list`）：标题行 `.wb-title-row`（「礼物目录」+ 右侧「新建礼物」）→ 说明 → **送礼 QRIS 最低金额配置行**（AC8）→ 摘要条三格 → 筛选栏 → 表格；点行开 480 抽屉，行内无按钮。
3. 页面放 `<div id="confirm-host"></div>`（`user-tags.html` 现状**没有**，AC8 弹层要用）。

**AC2 — 权限** `[L0]`（PRD §3.3 权限 / AD-11）
1. `AdminPermissions` 新增两个常量（值小写点分，`AdminPermissionWiringTest` 只认 `<模块>.<动作>`）：
   - `GIFT_CATALOG_VIEW = "gift.catalog_view"`（显示名「查看礼物目录」）
   - `GIFT_CATALOG_EDIT = "gift.catalog_edit"`（显示名「编辑礼物目录」）
   放在 `OPS_NOTIFY_SEND`（:237）之后，单起一段注释「V1.4.0 ③ AB-35，不预授」。VIEW 进 `GROUPS` 的 `perm.group.view` 列表末尾（:331 `OPS_NOTIFY_VIEW` 之后）、EDIT 进 `perm.group.edit` 列表末尾（:346 `OPS_NOTIFY_SEND` 之后）；`ALL`（:352）由 `GROUPS` 派生，不另改。
2. `AdminPageCatalog`：`page("gift-catalog", G_CONFIG, "/admin/gift-catalog", List.of(GIFT_CATALOG_VIEW), List.of(GIFT_CATALOG_EDIT), List.of()).nav("admin.nav.giftCatalog", "gift-catalog", GIFT_CATALOG_VIEW, GIFT_CATALOG_EDIT)`。Controller 页面门 `VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + GIFT_CATALOG_VIEW + "') or hasAuthority('" + GIFT_CATALOG_EDIT + "')"`，与 `navCodes` **逐字同集**；所有写端点与「新建」抽屉门 `EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + GIFT_CATALOG_EDIT + "')"`。写法照 `AdminUserTagController.java:42-43`。
3. **不预授**任何预置角色：`AdminRole.OPS_MANAGER` 枚举不加、`admin_role_permissions` 不插；超管隐式拥有。
4. 守卫测试（**本分支基线**，①② 未合入）：`AdminPermissionsTest.listStableSize`（:131）与 `AdminPermissionMatrixStaticTest`（:136）`78 → 80`（各加一行记账注释「+2 V1.4.0 ③ gift.catalog_view / gift.catalog_edit」，方法名若含数字按新数字改）；`AdminRoleTest.newCodesRegisteredButNotGrantedToAnyPresetRole`（:49-59）补这两码的 `doesNotContain`；`AdminRoleTest`（:42）`OPS_MANAGER … hasSize(41)` **不变**。Story 3.2 再 +1（→ 81）。

**AC3 — 列表（L1）** `[L0]` `[L1]`（PRD §3.3 列表 / UI L1）
1. 表头 8 列：图标 / 名称（EN / ID）/ 价格 / 适用物种 / 类型 / 最低等级 / 排序 / 状态。
   - 图标：`<img>` 24 高、等比，裸图（同 `user-tags-list.html:59-60` 的写法，不加底色）。
   - 名称：EN 一行 + ID 一行灰色 12px。
   - 价格：`Rp{千分位}`（`#numbers.formatInteger(price, 1, 'COMMA')`，后台惯例「1,234」），右对齐等宽。
   - 适用物种：`for_cat / for_dog / for_other` 按「猫 · 狗 · 其他」顺序用「 · 」连接；三者全 false 显示「通用」。
   - 类型：徽章「普通」（`badge-muted`）/「专属」（`badge-info` 或现有最接近的信息色徽章）。
   - 最低等级：专属显示 `Lv{min_level}`，普通「—」。
   - 状态：圆点 + 徽章「在线」（`dot--ok` + `badge-ok`）/「已下线」（`dot--done` + `badge-muted`），已下线整行 `row-muted`（同 `user-tags-list.html:57`、`admin.css:759`）。
2. 排序：`status = 'OFFLINE'` 的排最后；其余先普通后专属；同组按 `price ASC, sort_order ASC, id ASC`（与 UI L1 示例一致；实现为一次 `findAll` 后服务层 Comparator，等价于 `ORDER BY (status = 'OFFLINE'), (kind = 'EXCLUSIVE'), price, sort_order, id`——T0 核对后改，见 Completion Notes）。
3. 摘要条三格（**全目录口径，不随筛选变**）：在线礼物（`status = ONLINE` 数）/ 其中专属（在线且 `EXCLUSIVE`）/ 已下线（`OFFLINE` 数）。三个数与表格、价格下拉出自同一次全表读取（目录百级），目录无时间维度。
4. 筛选栏（UI L1 新增，用户标签页没有）：`<form method="get" data-autosubmit>` 三个下拉——价格（「全部价格」+ 目录里出现过的全部价格去重升序）、类型（全部 / 普通 / 专属）、状态（全部 / 在线 / 已下线）。参数 `price`（Long）/ `kind`（`NORMAL|EXCLUSIVE`）/ `status`（`ONLINE|OFFLINE`），**宽松解析**（不认识的值当不限，照 `AdminPaymentController.parseEnum` :186-195）。htmx 请求只回 `gift-catalog-list :: rows(oob=true)`（摘要条随 oob 换），非 htmx 回整页。
5. 空态：目录为空「还没有任何礼物，点右上角「新建礼物」开始。」；筛选无结果「没有符合筛选条件的礼物」（`.empty`）。
6. 点行 = `data-drawer-url=/admin/gift-catalog/{id}/drawer`（同 `user-tags-list.html:55-56`）；`?open={id}` 深链兜底照 `user-tags.html:73-74`。
7. 抽屉内新建 / 编辑 / 上下线成功后发 `HX-Trigger: admin:gift-catalog-list-refresh`（`AdminHxEvents` 新增常量 `GIFT_CATALOG_LIST_REFRESH`），页面上的隐形刷新监听 `hx-get=/admin/gift-catalog` **必须 `hx-include` 筛选表单**（否则刷新会丢筛选；`user-tags.html:58-60` 的写法没有筛选，不能照抄成静态 URL）。

**AC4 — 新建礼物抽屉（L2）与校验** `[L0]` `[L1]`（PRD §3.3 新建 / AD-1 / UI L2）
1. 「新建礼物」= `data-drawer-open=/admin/gift-catalog/new/drawer`（`EDIT_AUTH`；非 htmx → `redirect:/admin/gift-catalog?create=1`，照 `AdminUserTagController.newDrawer` :124-133）。只读用户**不渲染**此按钮（`sec:authorize` 与 `EDIT_AUTH` 逐字一致，同 `user-tags.html:26-30`）。
2. 表单（`multipart/form-data`，`hx-post=/admin/gift-catalog`，`hx-target="#gift-drawer-err"`，错误槽放提交钮正上方，同 `drawer-user-tag.html:333-334` 的教训）字段自上而下：
   - 图标 *（`<input type="file" name="iconFile" accept="image/png,image/webp" required>`）+ 常驻规格说明（AC5.3）；
   - 名称 EN *（`nameEn`，placeholder「如 Bone」）、名称 ID *（`nameId`，placeholder「如 Tulang」）；
   - 价格 *（`price`，`Rp` 前缀输入框，`type=number min=100 step=1`；提示「整数，≥ Rp100。」）；
   - 适用物种（三个勾选框 `forCat / forDog / forOther`；提示「都不选 = 通用。送礼面板每个价格档内：适用于收礼宠物物种的排最前，通用其次，其余最后。」）；
   - 类型 *（单选 `kind` 普通 / 专属，默认普通）；
   - 最低等级（`minLevel`，`type=number`，**只在类型 = 专属时显示并必填**，前端用一小段脚本按单选切 `hidden`，无脚本时始终显示、由服务端判）；提示「送礼人等级达到才能看到、才能送。Lv1 ~ 当前最高级。」；
   - 排序（`sortOrder`，可空，提示「数字小的在前」）；
   - 「保存」。
3. 服务端校验（`AdminGiftCatalogService`，全部抛 `AppException.validation(中文原文).code(文案码, args)`，与 `AdminConfigService.require`（:629）同形）——**任一失败 422，抽屉不关，错误落 `#gift-drawer-err`**：
   - 新建必须有图标（`admin.err.gift.iconRequired`，礼物版文案，见 AC5）；
   - `nameEn` / `nameId` trim 后非空且 ≤ 40 字（`VARCHAR(40)`，AD-1）（`admin.err.gift.nameRequired` / `.nameTooLong`）；
   - `price` 为整数且 `100 ≤ price ≤ 100,000,000`（下限 = DB CHECK，AD-1；上限复用 `AdminConfigService.MAX_TIER_AMOUNT`（:60），防多打几个 0 让 QRIS 单笔超限）（`admin.err.gift.priceRange`）；
   - `kind = EXCLUSIVE` 时 `minLevel` 必填且 `1 ≤ minLevel ≤ 当前最高级`（`admin.err.gift.minLevelRange`，参数带上限）；**本分支 ① 的等级表不存在**，上限取常量 `GiftCatalogRules.MAX_LEVEL_FALLBACK = 20`（PRD 等级曲线 Lv1–20），类注释写「4.1 对齐时改读 ① 等级表最高级」；`kind = NORMAL` 时忽略提交的 `minLevel`、落库 `NULL`（满足 AD-1 的 CHECK）；
   - `sortOrder` 为空 → 0；非空须为 `0 ~ 9999` 整数（`admin.err.gift.sortOrderRange`）。
4. 落库：`public_token` 由 1.1 新建的 `gift.service.GiftTokenGenerator`（32 位 base62，照 `KeepsakeTokenGenerator`）生成，`status = ONLINE`，`updated_by = adminId`。**礼物不设可编辑的编码**；抽屉头的灰色编码显示 `gift-{id}`（与用户标签 `ut-{id}` 同口径，只是展示，不落库、不对外）。
5. 成功：toast「礼物已新建」、关抽屉（`HX-Trigger` 带 `AdminHxEvents.DRAWER_CLOSE`（:9），`admin-drawer.js:96` 监听）、列表刷新（AC3.7）。保存后该礼物（在线、普通）**立即出现在 App 送礼面板**——`GET /api/v1/gifts/catalog?petId=` 每次读库（1.1；T0 核对无进程内缓存）。
6. 非 htmx 提交：PRG 回 `/admin/gift-catalog` + flash（照 `AdminUserTagController.createTag` :184-190）。

**AC5 — 图标校验（L4）** `[L0]`（PRD §3.3「复用 `AdminTagIconService` 的校验」/ UI L4）
1. 复用 `admin.tagicon.AdminTagIconService` 的校验骨架（格式白名单 :55 PNG / WebP、`MAX_BYTES` 512KB :52、字节量宽高 + 最小边 42 :94-100、OSS 公开对象上传 :102-115），**不另写第二套校验**。
2. 服务加**向后兼容的重载**（共享件，缺省行为逐字不变）：
   - 新增 `public enum IconPurpose { TAG, GIFT }`（放 `admin.tagicon` 包），每项带 OSS 子目录（TAG = 现 `FOLDER = "tag-icon"` :58；GIFT = `"gift-icon"`）与文案键前缀（TAG = `admin.tagicon.`；GIFT = `admin.gifticon.`）；
   - 新增 `uploadOrKeep(MultipartFile file, IconPurpose purpose)`、`specText(IconPurpose purpose, String context)`（键 = `<前缀>spec.<context>`，礼物用 `create` / `edit` 两段；T0 核对：单参 `specText(IconPurpose)` 选不出新建 / 编辑两段文案，改为双参）、`iconRequiredMessage(IconPurpose purpose)`；原 `uploadOrKeep(MultipartFile)`（:77）/ `specText(String context)`（:171）/ `iconRequiredMessage()`（:155）**签名与行为不变**（内部委托 TAG），用户标签 / 内容标签两处调用方一行不改。
3. 礼物版报错与规格说明只把「标签图标」改成「礼物图标」（UI L4 原话），键见 Dev Notes「文案键」：`admin.gifticon.noJpeg / heic / badType / readFailed / tooLarge / unmeasurable / tooSmall / required / uploadFailed / spec.create / spec.edit`。错误**行内**显示在 `#gift-drawer-err`，不走 toast（后台惯例）。
4. ⚠️ **不加「正方形」校验**：epics / 架构写了「正方形」，但 `AdminTagIconService` 自 2026-09-02 起已取消正方形与最大边长要求（:41-48 注释），UI L2 / L4 的规格说明也只写「推荐 112×112；最短边 ≥ 42」。按「复用该服务的校验」实现，Completion Notes 记一句（见「待确认」1）。

**AC6 — 礼物详情抽屉（L3 编辑）** `[L0]` `[L1]`（PRD §3.3 新建 / 编辑、改价 / UI L3）
1. `GET /admin/gift-catalog/{id}/drawer`（`VIEW_AUTH`；非 htmx → `redirect:/admin/gift-catalog?open={id}`；id 不存在 → 404 `admin.err.gift.notFound`）。
2. 抽屉头（同 `drawer-user-tag.html:22-34` 写法）：图标 + 名称 EN + 灰色 `gift-{id}` + 状态徽章（在线 / 已下线）+ 类型为专属时加「专属」徽章。
3. 编辑表单（有编辑权限时）：字段同 AC4.2，预填当前值；图标改为「更换图标」（不选文件 = 保留原图标，`uploadOrKeep` 返回 null 时服务层不改 `icon_url`，同 `AdminUserTagController.editTag` :213-215），规格说明用 `admin.gifticon.spec.edit`（「不选文件即保留原图标。推荐 112×112 像素……」）；价格下方提示「整数，≥ Rp100。改价只影响之后的新订单；已发起未付款的 QRIS 订单按原价。」。**未修改时保存钮置灰**（照 `drawer-user-tag.html:84-91` + :295-298 的脚本：`disabled` 由脚本加，不写死在 HTML）。`POST /admin/gift-catalog/{id}/edit`（`EDIT_AUTH`），校验同 AC4.3（编辑时图标可空）。
4. 底部操作条（`pl-actions sticky-footer`，同 `drawer-user-tag.html:97-112`）：左侧灰字「已收到 {n} 个 · 送出 {m} 次」（`n` = 该礼物 `DELIVERED` 订单的 `quantity` 之和，`m` = `DELIVERED` 订单数；一条 SQL，走 `gift_orders` 的 `gift_id` 条件——1.1 若没给 `gift_id` 建索引，礼物 ≤ 百级、订单千级，顺序扫可接受，不为此加迁移）；右侧「下线」/「重新上线」（AC7）。
5. 成功：toast「已保存」、抽屉体重渲染（`afterAction` 片段：抽屉体 oob + toast，同 `drawer-user-tag.html:349-357`）、列表刷新（AC3.7）。
6. **改价语义**（PRD / AD-2 快照）：只改 `gift_catalog.price`；**不碰任何 `gift_orders` 行**。已发起未付款的 QRIS 订单按订单行的 `unit_price` / `total` 与支付意图金额交付（1.1 的交付只读订单快照）；已收到的礼物墙按快照价位分组（AD-3），不受影响。同理改名 / 换图只影响新订单。
7. 类型由专属改普通：`min_level` 置 NULL；由普通改专属：须填最低等级。改类型 / 门槛只影响之后的新发起（交付时不再校验等级，AD-7），Dev Notes 记一句即可。

**AC7 — 下线 / 重新上线（L5）** `[L0]` `[L1]`（PRD §3.3 下线 / decision-log ③「下线用原生 confirm」/ UI L5）
1. 抽屉底部「下线」（红色 `btn-danger`）= `POST /admin/gift-catalog/{id}/status`（`EDIT_AUTH`，参数 `online=false`）；表单带 `data-confirm`（**浏览器原生 confirm**，`admin-core.js:41-66`），文案「确认下线「{nameEn}」？下线后不再出现在 App 送礼面板；已收到这个礼物的宠物，礼物墙照常展示。可以随时重新上线。」（UI L5 原话）。已下线时按钮变「重新上线」（普通样式、无确认），`online=true`。
2. 下线后：`GET /api/v1/gifts/catalog` 不再返回该礼物（1.1 只取 ONLINE）；对该礼物新发起的送礼 → 1.1 的「礼物须 ONLINE」校验拒绝；已收到的照常按订单快照展示（AC17，墙读 `gift_pet_wall` 快照列）。**已发起未付款的 QRIS 订单照常交付**（交付不校验 ONLINE，AD-7；与改价口径一致）。
3. **没有删除按钮、没有删除端点**（只能下线不能物理删除，AD-1）。
4. 状态未变（已是目标状态）→ 幂等返回成功、不写审计。
5. 成功：toast「已下线」/「已重新上线」、抽屉重渲染、列表刷新。

**AC8 — 送礼 QRIS 最低金额（L6）** `[L0]` `[L1]`（PRD §3.3 / AD-4 / decision-log ③「QRIS 最低金额用服务端确认弹窗」/ UI L1 / L6）
1. 页面顶部配置行（UI L1 `.cfgline`）：左「送礼 QRIS 最低金额」+ 灰字「总价低于此金额时只能用 PawCoin。只对送礼生效，月租等其他付费不受影响。」，右当前值 `Rp{千分位}`（读 `pricing_config.gift_qris_min_amount`：1.1 给 `PricingConfig` 加了字段 `giftQrisMinAmount` + getter、经 `PlatformConfigService.pricing()`（:72）读；**setter 由本 story 加**（1.1 AC2.2 明确留给 3.1））+「修改」按钮。
2. 「修改」= `details.pl-inline` 行内展开（同「新建充值档位」`config-card-pawcoin.html:40`）：`Rp` 前缀输入框 `amount`（`type=number min=100 step=1`，预填当前值）+「下一步」。提交 `hx-post=/admin/gift-catalog/qris-min/confirm`（`EDIT_AUTH`，htmx）：**只校验不写库**，回 `fragments/confirm-modal :: modal` 到 `#confirm-host`（`HX-Retarget #confirm-host` + `HX-Reswap innerHTML`，写法照 `AdminConfigController.confirmAppVersion` :295-312）。
   - 为什么新金额输入不在弹层里：`AdminConfirmModal`（`AdminConfirmModal.java:36-39`）除「输入值确认」外没有输入槽，弹层只复述服务端算好的新旧值；UI L6 弹层里的「新金额」按只读复述处理（见「待确认」2）。
3. 弹层内容（UI L6）：用现有 12 参构造器 `new AdminConfirmModal(title, danger, diffs, note, null, null, null, "/admin/gift-catalog/qris-min", hidden, confirmLabel, false, true)`——
   - 标题「修改送礼 QRIS 最低金额」；
   - **黄色**说明条（`danger` 文本 + `warnBanner = true`）：「只对送礼生效，月租、身份证高清图等其他付费不受影响。修改立即生效，写入操作审计。」；
   - diffs 一行：「送礼 QRIS 最低金额：Rp10,000 → Rp15,000」（旧值读**库里当前值**，不信任页面）；
   - hidden：`amount`；确认钮「确认修改」（品牌色，`dangerButton = false`）。
4. 校验（预检与保存**同一方法**）：整数且 `100 ≤ amount ≤ 100,000,000`（下限 = AD-4 DB CHECK；上限同 AC4.3）（`admin.err.gift.qrisMinRange`）；与当前值相同 → 422 `admin.err.gift.qrisMinNoChange`（不弹层）。预检 422 落在配置行自己的错误槽 `#gift-qris-err`。
5. 保存 `POST /admin/gift-catalog/qris-min`（`EDIT_AUTH`）：同一事务 ① 更新 `pricing_config.gift_qris_min_amount` ② 写一条 `config_change_logs`（`ConfigType.PRICING`、字段 `gift_qris_min_amount`、旧 → 新，写法照 `AdminConfigService.diff`（:613-620））③ 审计（AC10）。成功：`HX-Trigger-After-Swap: admin:confirm-close` 收弹层（`AdminHxEvents.CONFIRM_CLOSE`，照 `AdminConfigController.updateAppVersion` :398-403），配置行 fragment 原位刷新（`HX-Retarget #gift-qris-line`），toast「送礼 QRIS 最低金额已改为 Rp15,000」。422 / 403 回弹层内 `#confirm-modal-err`，弹层不消失。
6. **只对送礼生效**：值只被 1.1 的送礼发起校验读取（AD-4「只在送礼服务发起 QRIS 前校验，不进通用 `createIntent`」）。本 story **不改** `PaymentIntentService.createIntent` 及任何其他付费的金额校验；改为 Rp15,000 后 Rp10,000 的送礼 QRIS → 422 `gift-qris-below-min`，同额的其他 QRIS（如 keepsake / 问诊）照常可发起（L1 用例）。
7. 不写 `AdminConfigService`：本配置的读写全在送礼后台服务里（避免与 ② 在 `AdminConfigService` 加月租价格的改动撞车）；但校验写法、`config_change_logs` 口径与它一致。

**AC9 — 只有查看权限（L7）** `[L0]` `[L1]`（UI L7 / admin.css「不可用按钮留原地说明条件」）
1. 只有 `GIFT_CATALOG_VIEW`：页头「新建礼物」**隐藏**（用户标签现状）；QRIS 配置行「修改」按钮 `disabled` + 旁注「需要「编辑礼物目录」权限」（`#{admin.v130.err.forbidden(#{perm.gift.catalog_edit})}`，`messages_zh_CN.properties:2770`）；摘要条、筛选、表格照常。
2. 抽屉：编辑表单换成**只读字段**（名称「Golden ribbon / Pita emas」、价格、适用物种、类型「专属 · Lv5」、排序），下方「需要「编辑礼物目录」权限」；底部「下线」按钮 `disabled` + `title` 同文案（同 `drawer-user-tag.html:109-111`）；收送统计照常。
3. 直接 POST 任一写端点或 GET 新建抽屉 → 403（`@PreAuthorize(EDIT_AUTH)`，不靠前端）。
4. 抽屉 / 页面的 `canEdit` 判定用 `#authorization.expression(...)`，表达式与 `EDIT_AUTH` 逐字一致（同 `drawer-user-tag.html:18-19`）。

**AC10 — 审计** `[L0]`（PRD §3.3「修改写审计日志」/ AD-11）
1. `AuditActions` 末尾（:257 `SHOP_RETURN_PRECEDENT_ADDED` 之后）新增：`GIFT_CATALOG_CREATED`、`GIFT_CATALOG_UPDATED`、`GIFT_CATALOG_OFFLINED`、`GIFT_CATALOG_ONLINED`、`GIFT_QRIS_MIN_UPDATED`（UPPER_SNAKE 过去式）。
2. `AdminAuditService.record(adminId, action, targetType, targetId, summary)` 与业务写入**同一事务**；`targetType`：礼物 = `gift_catalog`（`targetId` = 礼物 id）、QRIS = `pricing_config`（`targetId` = `gift_qris_min_amount`）。
3. `summary` 写改前 → 改后（如 `price 25000→30000; for_dog false→true; kind NORMAL→EXCLUSIVE; min_level null→5`、`gift_qris_min_amount 10000→15000`；换图只记 `icon changed`，不记 URL）；无真变化 → 不写库、不写审计，toast 仍显示「已保存」。
4. 四份 messages 补 `admin.audit.action.<CODE>`（`AuditActionLabelsTest` 反射全部常量逐语言校验，`AuditActionLabelsTest.java:27-47`）。

**AC11 — 文案（四份 messages）** `[L0]`
页面上**所有**可见文字走 `#{…}`；新键同时加进 `messages_zh_CN.properties`、`messages_en.properties`、`messages_id.properties`、`messages.properties`（默认包与 en 同值）。键清单见 Dev Notes「文案键」。`bash scripts/ci/check-i18n-keys.sh` 与 `bash scripts/ci/check-admin-hardcoded-text.sh` 本地跑绿。

**AC12 — 回归** `[L0]` `[L1]`
1. `mvn -B clean package` 全绿（含 `AdminPageCatalogTest`、`AdminPermissionsTest`、`AdminPermissionWiringTest`、`AdminRoleTest`、`AdminPermissionMatrixStaticTest`、`AdminActiveKeyCatalogTest`、`AuditActionLabelsTest`、`AdminTagIconServiceTest`、`AdminTagEditKeepsIconTest`）。
2. `bash scripts/ci/check-admin-permission-consistency.sh` 本地跑绿（新增 GET 子路由均带 `{…}` 或以 `drawer` 结尾，脚本 :95-96 不会把它们当页面）。
3. L1：`AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate`、`AdminNavPermissionMatrixTest`、`AdminPageCatalogCoverageTest` 与本 story 新增集成测试全绿（云端跑不了 → Completion Notes 标「L1 待本地验收」）。

## Tasks / Subtasks

- [x] **T0 开工核对**（结论写进 Completion Notes；与本文件不符先改本文件再写码）
  - [x] 0.1 1.1 落地的礼物目录实体 / 仓储真实名字（1.1 story 定为 `gift.domain.GiftCatalogItem` / `GiftKind` + `gift.repository.*`）、列名与 1.1 AC1.2 一致（`for_cat/for_dog/for_other`、`kind`、`min_level`、`sort_order DEFAULT 0`、`status`、`updated_by`、表级 CHECK `ck_gift_catalog_exclusive_level`、索引 `ix_gift_catalog_online`）、`GiftTokenGenerator`
  - [x] 0.2 1.1 的 `PricingConfig.giftQrisMinAmount`（只有 getter，本 story 加 setter）与送礼发起读它的入口（`PlatformConfigService.pricing()`）；确认读口**每次查库无缓存**（`PlatformConfigService.java:23` 现状注释「无缓存」）
  - [x] 0.3 1.1 的 `GET /api/v1/gifts/catalog` 是否只取 `ONLINE`、是否每次查库；送礼发起是否校验 `ONLINE`；交付是否只读订单快照（AC6.6 / AC7.2 依赖）
  - [x] 0.4 `gift_orders.gift_id` 无专门索引（1.1 AC1.3 的四个索引不含它）：AC6.4 统计按礼物顺序扫可接受，不加迁移，记一句
- [x] **T1 权限码 + 页面目录**（AC1、AC2）：`AdminPermissions` 两码 + `GROUPS`；`AdminPageCatalog` 插 `gift-catalog` 页；三个计数 / 不预授测试改数；`AdminPageCatalogTest` 的 active 列表（:103-112）加 `"gift-catalog"`
- [x] **T2 图标服务重载**（AC5）：`IconPurpose` + 三个重载；原签名委托 TAG；`AdminTagIconServiceTest` 补 GIFT 用例（OSS key 前缀 `public/gift-icon/`、礼物版文案）
- [x] **T3 后台服务 `admin.gift.service.AdminGiftCatalogService`**：`list(filter)` / `summary()` / `priceOptions()` / `detail(id)`（含收送统计）/ `create` / `update` / `setOnline` / `qrisMin()` / `previewQrisMin` / `updateQrisMin`；校验；审计；`config_change_logs`
- [x] **T4 Controller `admin.gift.web.AdminGiftCatalogController`**：端点见 Dev Notes「端点一览」
- [x] **T5 模板**：`templates/admin/gift-catalog.html`（页 + `#confirm-host` + 刷新监听 + 深链兜底）、`fragments/gift-catalog-list.html`（`sumbar(oob)` / `rows(oob)` / `row(g, oob)`）、`fragments/drawer-gift.html`（`drawer` / `createForm` / `afterAction` / `created`）、`fragments/gift-qris-line.html`（配置行 + 行内展开）；`AdminHxEvents.GIFT_CATALOG_LIST_REFRESH`
- [x] **T6 审计常量 + 四份文案**（AC10、AC11）
- [x] **T7 测试**（见 Dev Notes「测试」）：L0 全写全跑；L1 写好能编译

## Dev Notes

### 现状（改动前必读，dev_1.4.0 @ 688f9bd5 核实）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/admin/usertag/web/AdminUserTagController.java` | **范本**：`/admin/user-tags` 列表 :76-102（htmx 回 `user-tags-list :: rows(oob=true)`）；门控常量 :42-43（`VIEW` / `MANAGE` 拼 `AdminPermissions` 常量）；抽屉 :110-121（非 htmx 重定向 `?open=`）；新建抽屉 :124-133；`afterAction` :155-161（抽屉重渲染 + toast + `HX-Trigger` 列表刷新）；新建 :169-203（multipart，`icons.uploadOrKeep` 为 null 即缺图报错 :197-200）；编辑 :205-227（空文件 = 保留原图）；上下线 :229-248 | 照抄结构写 `AdminGiftCatalogController` | 本文件一行不改 |
| `…/resources/templates/admin/user-tags.html` | 模板 B 用法 :53-69（`tpl-b-list :: list(filters, summary, table, drawer)`）；「新建」按钮 `sec:authorize` :26-30；隐形刷新监听 :58-60（静态 hx-get，**无筛选**）；`?open=` 深链兜底 :73-74；**无 `#confirm-host`** | 照抄 | — |
| `…/templates/admin/fragments/user-tags-list.html` | `sumbar(oob)` :15-26、`rows(oob)` :28-50（空态 `.empty` :46-49）、`row(t, oob)` :55-74（`row-muted` 已下线 :57、裸图 :59-60、状态点 + 徽章 :69-73） | 照抄 | — |
| `…/templates/admin/fragments/drawer-user-tag.html` | 抽屉头 :22-34；`canManage` 判定 :18-19；编辑表单 multipart + `#utag-drawer-err` :52-83；保存钮脚本加 `disabled` :84-91 / :295-298；无权限提示 :93-94；操作条下线（`data-confirm`）:97-107、无权限禁用钮 :109-111；新建表单 `createForm` :305-338；`created` :341-345；`afterAction` :349-357 | 照抄 | — |
| `…/admin/tagicon/AdminTagIconService.java` | `MIN_SIDE = 42` :49；`MAX_BYTES = 512KB` :52；`ALLOWED` PNG / WebP :55；`FOLDER = "tag-icon"` :58；`uploadOrKeep(file)` :77-115（空文件返回 null :78-80；JPEG / HEIC / 其他 :130-148；测尺寸 :94-100；上传失败转 422 :104-114）；`iconRequiredMessage()` :155；`specText(context)` :171；**2026-09-02 起不要求正方形 / 无最大边**（:41-48） | 加 `IconPurpose` 与三个重载（AC5.2） | 原三个方法签名与行为逐字不变；两处调用方不改 |
| `…/admin/shared/AdminPageCatalog.java` | 分组常量 :93-100（`G_CONFIG` :100）；`GROUPS` :102-110；`payments` 页 :190-191；配置组 :240-256（`algo-params` :248-249、`share-reward` :250-251）；`nav()` Javadoc :63-68 要求 `navCodes` 与 Controller `@PreAuthorize` 逐字同集；`allCodes()` :273 被断言 == `ALL` 且无重复 | :249 与 :250 之间插 `gift-catalog` | 每个码恰好归属一页 |
| `…/admin/account/domain/AdminPermissions.java` | `PAYMENT_VIEW = "payment.view"` :247 的写法（常量 + Javadoc）；运营触达段 :231-237；`GROUPS` :318-346（view 组止于 :331 `OPS_NOTIFY_VIEW))`、edit 组止于 :346）；`ALL` :352 | 加两常量并入两组末尾 | 既有码值与顺序一字不动 |
| `…/admin/config/web/AdminConfigController.java` | **服务端确认弹层先例**：预检 `POST /admin/config/app-version/confirm` :295-312（回 `confirm-modal :: modal`，`HX-Retarget #confirm-host`、`HX-Reswap innerHTML`）→ 弹层内 `POST /admin/config/app-version` :388-410（成功 `HX-Trigger-After-Swap: admin:confirm-close`） | QRIS 最低金额照抄这一对 | — |
| `…/admin/shared/web/AdminConfirmModal.java` + `…/templates/admin/fragments/confirm-modal.html` | record :36-39（12 字段，含 `warnBanner` 黄条）；11 参构造器 :42-47；`DiffLine(label, oldValue, newValue)` :50；模板黄条 `cm-banner-warn` :24、diff 列表 :25-27、hidden :34、错误槽 `#confirm-modal-err` :35 | 用 12 参构造器，不改共享件 | 两个构造器签名不变 |
| `…/admin/config/service/AdminConfigService.java` | 金额下限常量 `MIN_UNLOCK_PRICE = 100` :110 + `updateKtpPricing` :123（≥100、≤ `MAX_TIER_AMOUNT` :60 = 100,000,000，422 文案码）；`diff` :613-620；`commit` :622-626（`config_change_logs` + `CONFIG_UPDATE_*` 审计）；`require` :629 | **不改**；只照搬校验写法与 `config_change_logs` 口径 | — |
| `…/config/domain/PricingConfig.java` | 单例 `SINGLETON_ID = 1L` :18；`giftQrisMinAmount` 字段 + getter 由 1.1 加（本 story 开工时应已存在） | 加 setter；只读写该字段 | 其余字段不动（② 也会加月租价字段） |
| `…/config/domain/ConfigChangeLog.java` | `ConfigType` 枚举 :19-27 含 `PRICING` | 用 `PRICING` | 不加枚举值 |
| `…/admin/payment/web/AdminPaymentController.java` | 宽松解析 `parseEnum` :186-195 | 筛选参数照此 | — |
| `…/admin/audit/service/AuditActions.java` | 末尾 :257 `SHOP_RETURN_PRECEDENT_ADDED`、私有构造器 :259 | 末尾追加 5 个 `GIFT_*` | — |
| `…/admin/shared/web/AdminHxEvents.java` | `USER_TAG_LIST_REFRESH` :41、`CONFIRM_CLOSE` :79、`DRAWER_OPEN` :100 | 加 `GIFT_CATALOG_LIST_REFRESH = "admin:gift-catalog-list-refresh"` | — |
| `…/templates/admin/fragments/config-card-pawcoin.html` | `details.pl-inline` 行内展开 :40 | QRIS「修改」照此 | — |
| `…/static/admin/admin-core.js` | `data-confirm` 原生确认 :41-66；`form[data-autosubmit]` 下拉即提交 :210-222 | 不改 | — |
| `…/static/admin/admin.css` | `.row-muted` :759；`.badge-warn` / `.badge-danger` :271-272 | 不改（如需礼物图标格样式，只追加一个类） | — |
| `petgo-backend/src/main/java/com/tailtopia/admin/gift/` | **不存在，需新建**（AD「后台 `admin.gift`」） | `web/AdminGiftCatalogController`、`service/AdminGiftCatalogService`、`service/GiftCatalogRules` | — |
| `…/resources/templates/admin/gift-catalog.html`、`fragments/gift-catalog-list.html`、`fragments/drawer-gift.html`、`fragments/gift-qris-line.html` | **不存在，需新建** | 见 T5 | — |

测试位置：`petgo-backend/src/test/java/com/tailtopia/admin/shared/AdminPageCatalogTest.java`（`ownedCodesCoverAllPermissionsExactlyOnce` :19、`everyPageAndGroupHasLabelsInAllLocales` :65——页名 `admin.page.gift-catalog` / `perm.<码>` 三语齐全才绿、`navPagesHaveRouteUniqueActiveKeyRegisteredCodesAndLabels` :87，active 列表 :103-112）；`…/admin/account/domain/AdminPermissionsTest.java`（:131 `hasSize(78)`）；`…/admin/shared/AdminPermissionMatrixStaticTest.java`（:136）；`…/admin/account/domain/AdminRoleTest.java`（:42、:49-59）；`…/admin/audit/web/AuditActionLabelsTest.java`；`…/admin/tagicon/AdminTagIconServiceTest.java`；`…/admin/usertag/AdminTagEditKeepsIconTest.java`、`AdminUserTagIntegrationTest.java`、`AdminUserTagDrawerIntegrationTest.java`（集成写法范本）；集成基类 `…/support/ApiIntegrationTest.java`；确认弹层集成范本 `…/admin/config/AdminAppVersionConfigIntegrationTest.java`。

### 端点一览

| 方法 | 路径 | 门 | 返回 |
|---|---|---|---|
| GET | `/admin/gift-catalog?price=&kind=&status=&open=&create=` | `VIEW_AUTH` | 整页 `admin/gift-catalog`；htmx → `gift-catalog-list :: rows(oob=true)` |
| GET | `/admin/gift-catalog/new/drawer` | `EDIT_AUTH` | `drawer-gift :: createForm`；非 htmx → `redirect:/admin/gift-catalog?create=1` |
| GET | `/admin/gift-catalog/{id}/drawer` | `VIEW_AUTH` | `drawer-gift :: drawer`；非 htmx → `redirect:/admin/gift-catalog?open={id}` |
| POST | `/admin/gift-catalog` | `EDIT_AUTH` | 成功 `drawer-gift :: created`（toast）+ `HX-Trigger` 列表刷新 + `admin:drawer-close`；422 → `#gift-drawer-err` |
| POST | `/admin/gift-catalog/{id}/edit` | `EDIT_AUTH` | `drawer-gift :: afterAction`；422 → `#gift-drawer-err` |
| POST | `/admin/gift-catalog/{id}/status`（`online=true|false`） | `EDIT_AUTH` | `drawer-gift :: afterAction` |
| POST | `/admin/gift-catalog/qris-min/confirm`（`amount`） | `EDIT_AUTH` | `confirm-modal :: modal` → `#confirm-host`；422 → `#gift-qris-err`；非 htmx → 重定向 |
| POST | `/admin/gift-catalog/qris-min`（`amount`） | `EDIT_AUTH` | `gift-qris-line :: line` + toast + `HX-Trigger-After-Swap: admin:confirm-close`；422 → `#confirm-modal-err` |

- 新增 GET 子路由都带 `{…}` 或以 `drawer` 结尾（`check-admin-permission-consistency.sh` :95-96）；**不要**新增不带变量的 GET 子路由。
- 所有 POST 表单 `hx-target` 指**带 id** 的元素（`drawer-users.html:12-13` 注释：htmx 只在目标带 id 时才发 `HX-Target`，`AdminBusinessExceptionAdvice` 按它 Retarget）。
- 非 htmx 提交一律 PRG 回 `/admin/gift-catalog` + flash。
- 后台按自增 `id` 寻址礼物，与用户标签 `/admin/user-tags/{id}` 同口径（后台内部、需登录、不对 App 暴露）；App 只见 `public_token`（1.1）。

### 架构约束

- **AD-1**：只下线不物理删除；`price ≥ 100`；`EXCLUSIVE` 必填 `min_level`（DB CHECK），校验在服务层先拦成人话 422，别让 CHECK 以 500 露出来。
- **AD-4**：`gift_qris_min_amount` 默认 10000、`CHECK ≥ 100`；**只在送礼服务发起 QRIS 前校验，不进通用 `createIntent`**。
- **AD-2 / AD-3 快照**：改价 / 改名 / 换图 / 下线都不碰 `gift_orders` 与 `gift_pet_wall`。
- **AD-11**：页在 `G_CONFIG`；下线原生 confirm、QRIS 最低金额服务端确认弹层；新码不预授；审计照惯例。
- **§5**：后台不改订单状态；对外标识（App）一律 `public_token`。
- **护栏**：不引 MQ / 缓存 / 新依赖；`ddl-auto=validate`；无新迁移；打包一律 `mvn -B clean package`；日志只记 id 与计数，不记昵称 / 邮箱 / 签名 URL。
- **共享约束不碰**：`ck_notifications_type` / `ck_payment_intents_purpose` 不改；地基抽屉页签头不改；`GrowthQueryService` 签名不改（本 story 不调用它）。

### 与 ① / ② 合并时会冲突的点（本 story 产生，合并方处理）

- `AdminPageCatalog`：① 4.1 在 `algo-params` 之后插 `growth`；本 story 在同一位置插 `gift-catalog`。解法：顺序 `algo-params → growth → gift-catalog → share-reward`（UI 稿侧栏顺序）。
- `AdminPermissions` 两组末尾 / 计数测试：① 4.1 + 4.2 +3（OPS_MANAGER +1）、② 4.3 +1（OPS_MANAGER +1）、本线 3.1 + 3.2 +3 → 合并后 `ALL` 应为 **85**、`OPS_MANAGER` **43**（以合并时实测为准），三处计数测试与记账注释按并集重写。
- `AuditActions` 末尾、四份 messages 末尾、`AdminHxEvents`：纯追加冲突，保留全部。
- `AdminTagIconService`：只有本线改，无冲突。

### 校验规则汇总

| 字段 | 规则 | 文案码 |
|---|---|---|
| 图标（新建） | 必填；PNG / WebP；≤ 512KB；最短边 ≥ 42；能测出尺寸 | `admin.err.gift.iconRequired`（文案与 `admin.gifticon.required` 同值；单独成键以满足 `AdminMessagesExternalizedTest` 的「后台异常挂 admin.err.* 码」）及 `admin.gifticon.*` |
| `nameEn` / `nameId` | trim 后非空、≤ 40 | `admin.err.gift.nameRequired` / `admin.err.gift.nameTooLong` |
| `price` | 整数，100 ~ 100,000,000 | `admin.err.gift.priceRange` |
| `kind` | `NORMAL` / `EXCLUSIVE`（缺省 NORMAL） | `admin.err.gift.kindInvalid` |
| `minLevel` | EXCLUSIVE 必填，1 ~ 当前最高级（本分支 20） | `admin.err.gift.minLevelRange` |
| `sortOrder` | 空 = 0；0 ~ 9999 | `admin.err.gift.sortOrderRange` |
| `amount`（QRIS） | 整数，100 ~ 100,000,000；≠ 当前值 | `admin.err.gift.qrisMinRange` / `admin.err.gift.qrisMinNoChange` |

### 文案键（四份都加；`messages.properties` = en）

| 键 | zh_CN | en | id |
|---|---|---|---|
| `admin.nav.giftCatalog` / `admin.page.gift-catalog` | 礼物目录 | Gift catalog | Katalog hadiah |
| `perm.gift.catalog_view` | 查看礼物目录 | View gift catalog | Lihat katalog hadiah |
| `perm.gift.catalog_edit` | 编辑礼物目录 | Edit gift catalog | Ubah katalog hadiah |
| `admin.v140.gift.title` | 礼物目录 | Gift catalog | Katalog hadiah |
| `admin.v140.gift.new` | 新建礼物 | New gift | Hadiah baru |
| `admin.v140.gift.subtitle` | App 送礼面板里的礼物。只能下线、不能删除；已收到的礼物按订单快照照常展示。改价只影响之后的新订单。 | Gifts shown in the app's gift panel. Gifts can only be taken offline, never deleted; gifts already received keep showing from the order snapshot. Price changes only affect new orders. | Hadiah di panel hadiah aplikasi. Hadiah hanya bisa dinonaktifkan, tidak bisa dihapus; hadiah yang sudah diterima tetap tampil dari snapshot pesanan. Perubahan harga hanya berlaku untuk pesanan baru. |
| `admin.v140.gift.qris.label` | 送礼 QRIS 最低金额 | Minimum QRIS amount for gifts | Nominal minimum QRIS untuk hadiah |
| `admin.v140.gift.qris.hint` | 总价低于此金额时只能用 PawCoin。只对送礼生效，月租等其他付费不受影响。 | Below this total, gifts can only be paid with PawCoin. Applies to gifts only; slot rental and other payments are not affected. | Di bawah total ini, hadiah hanya bisa dibayar dengan PawCoin. Hanya berlaku untuk hadiah; sewa slot dan pembayaran lain tidak terpengaruh. |
| `admin.v140.gift.qris.edit` / `.next` | 修改 / 下一步 | Change / Next | Ubah / Lanjut |
| `admin.v140.gift.qris.newAmount` | 新金额 | New amount | Nominal baru |
| `admin.v140.gift.qris.confirm.title` | 修改送礼 QRIS 最低金额 | Change minimum QRIS amount for gifts | Ubah nominal minimum QRIS untuk hadiah |
| `admin.v140.gift.qris.confirm.banner` | 只对送礼生效，月租、身份证高清图等其他付费不受影响。修改立即生效，写入操作审计。 | Applies to gifts only; slot rental, HD ID card and other payments are not affected. Takes effect immediately and is written to the audit log. | Hanya berlaku untuk hadiah; sewa slot, KTP HD, dan pembayaran lain tidak terpengaruh. Langsung berlaku dan dicatat di log audit. |
| `admin.v140.gift.qris.confirm.diff` | 送礼 QRIS 最低金额 | Minimum QRIS amount for gifts | Nominal minimum QRIS untuk hadiah |
| `admin.v140.gift.qris.confirm.btn` | 确认修改 | Confirm change | Konfirmasi perubahan |
| `admin.v140.gift.summary.online` / `.exclusive` / `.offline` | 在线礼物 / 其中专属 / 已下线 | Online gifts / Exclusive / Offline | Hadiah aktif / Eksklusif / Nonaktif |
| `admin.v140.gift.filter.allPrices` / `.allKinds` / `.allStatuses` | 全部价格 / 全部类型 / 全部状态 | All prices / All types / All statuses | Semua harga / Semua jenis / Semua status |
| `admin.v140.gift.col.icon` / `.name` / `.price` / `.species` / `.kind` / `.minLevel` / `.sortOrder` / `.status` | 图标 / 名称（EN / ID）/ 价格 / 适用物种 / 类型 / 最低等级 / 排序 / 状态 | Icon / Name (EN / ID) / Price / Species / Type / Min. level / Order / Status | Ikon / Nama (EN / ID) / Harga / Spesies / Jenis / Level min. / Urutan / Status |
| `admin.v140.gift.species.cat` / `.dog` / `.other` / `.all` | 猫 / 狗 / 其他 / 通用 | Cat / Dog / Other / All | Kucing / Anjing / Lainnya / Semua |
| `admin.v140.gift.kind.NORMAL` / `.EXCLUSIVE` | 普通 / 专属 | Standard / Exclusive | Biasa / Eksklusif |
| `admin.v140.gift.status.ONLINE` / `.OFFLINE` | 在线 / 已下线 | Online / Offline | Aktif / Nonaktif |
| `admin.v140.gift.empty` | 还没有任何礼物，点右上角「新建礼物」开始。 | No gifts yet. Click "New gift" to add one. | Belum ada hadiah. Klik "Hadiah baru" untuk menambah. |
| `admin.v140.gift.emptyFiltered` | 没有符合筛选条件的礼物 | No gifts match the filters | Tidak ada hadiah yang cocok dengan filter |
| `admin.v140.gift.drawer.newTitle` / `.detailTitle` | 新建礼物 / 礼物详情 | New gift / Gift details | Hadiah baru / Detail hadiah |
| `admin.v140.gift.form.icon` / `.iconChange` | 图标 / 更换图标 | Icon / Change icon | Ikon / Ganti ikon |
| `admin.v140.gift.form.nameEn` / `.nameId` | 名称 EN / 名称 ID | Name (EN) / Name (ID) | Nama (EN) / Nama (ID) |
| `admin.v140.gift.form.nameEnPh` / `.nameIdPh` | 如 Bone / 如 Tulang | e.g. Bone / e.g. Tulang | mis. Bone / mis. Tulang |
| `admin.v140.gift.form.price` / `.priceHint` / `.priceHintEdit` | 价格 / 整数，≥ Rp100。 / 整数，≥ Rp100。改价只影响之后的新订单；已发起未付款的 QRIS 订单按原价。 | Price / Whole number, at least Rp100. / Whole number, at least Rp100. Price changes only affect new orders; unpaid QRIS orders already started keep the old price. | Harga / Bilangan bulat, minimal Rp100. / Bilangan bulat, minimal Rp100. Perubahan harga hanya berlaku untuk pesanan baru; pesanan QRIS yang sudah dibuat dan belum dibayar tetap memakai harga lama. |
| `admin.v140.gift.form.species` / `.speciesHint` | 适用物种 / 都不选 = 通用。送礼面板每个价格档内：适用于收礼宠物物种的排最前，通用其次，其余最后。 | Species / Leave all unchecked = all species. Within each price tier of the gift panel: gifts for the recipient pet's species come first, then all-species gifts, then the rest. | Spesies / Tidak dicentang = semua spesies. Di setiap tingkat harga panel hadiah: hadiah untuk spesies hewan penerima tampil pertama, lalu hadiah semua spesies, lalu sisanya. |
| `admin.v140.gift.form.kind` | 类型 | Type | Jenis |
| `admin.v140.gift.form.minLevel` / `.minLevelHint` | 最低等级 / 送礼人等级达到才能看到、才能送。Lv1 ~ 当前最高级。 | Min. level / Senders see and can send it only at this level or above. Lv1 to the current top level. | Level min. / Pengirim hanya bisa melihat dan mengirim jika levelnya mencapai ini. Lv1 sampai level tertinggi saat ini. |
| `admin.v140.gift.form.sortOrder` / `.sortOrderHint` | 排序 / 数字小的在前 | Order / Smaller numbers come first | Urutan / Angka kecil tampil lebih dulu |
| `admin.v140.gift.save` | 保存 | Save | Simpan |
| `admin.v140.gift.stats` | 已收到 {0} 个 · 送出 {1} 次 | {0} received · sent {1} times | {0} diterima · dikirim {1} kali |
| `admin.v140.gift.offline` / `.online` | 下线 / 重新上线 | Take offline / Bring back online | Nonaktifkan / Aktifkan lagi |
| `admin.v140.gift.offlineConfirm` | 确认下线「{0}」？下线后不再出现在 App 送礼面板；已收到这个礼物的宠物，礼物墙照常展示。可以随时重新上线。 | Take "{0}" offline? It will no longer appear in the app's gift panel; pets that already received it keep showing it on their gift wall. You can bring it back online anytime. | Nonaktifkan "{0}"? Hadiah ini tidak akan muncul lagi di panel hadiah aplikasi; hewan yang sudah menerimanya tetap menampilkannya di dinding hadiah. Bisa diaktifkan lagi kapan saja. |
| `admin.v140.gift.readonly.kindLevel` | {0} · Lv{1} | {0} · Lv{1} | {0} · Lv{1} |
| `admin.flash.gift.created` / `.saved` / `.offlined` / `.onlined` | 礼物已新建 / 已保存 / 已下线 / 已重新上线 | Gift created / Saved / Taken offline / Back online | Hadiah dibuat / Tersimpan / Dinonaktifkan / Aktif lagi |
| `admin.flash.gift.qrisMinSaved` | 送礼 QRIS 最低金额已改为 Rp{0} | Minimum QRIS amount for gifts changed to Rp{0} | Nominal minimum QRIS untuk hadiah diubah menjadi Rp{0} |
| `admin.err.gift.notFound` | 礼物不存在 | Gift not found | Hadiah tidak ditemukan |
| `admin.err.gift.nameRequired` / `.nameTooLong` | 请填写 EN 与 ID 名称 / 名称最多 40 个字 | Enter both EN and ID names / Names can be at most 40 characters | Isi nama EN dan ID / Nama maksimal 40 karakter |
| `admin.err.gift.priceRange` | 价格须为 100 ~ 100,000,000 的整数（Rp） | Price must be a whole number from 100 to 100,000,000 (Rp) | Harga harus bilangan bulat 100 sampai 100.000.000 (Rp) |
| `admin.err.gift.kindInvalid` | 类型取值无效 | Invalid type | Jenis tidak valid |
| `admin.err.gift.minLevelRange` | 专属礼物须填写最低等级（Lv1 ~ Lv{0}） | Exclusive gifts need a minimum level (Lv1 to Lv{0}) | Hadiah eksklusif wajib punya level minimum (Lv1 sampai Lv{0}) |
| `admin.err.gift.sortOrderRange` | 排序须为 0 ~ 9999 的整数 | Order must be a whole number from 0 to 9999 | Urutan harus bilangan bulat 0 sampai 9999 |
| `admin.err.gift.qrisMinRange` | 金额须为 100 ~ 100,000,000 的整数（Rp） | Amount must be a whole number from 100 to 100,000,000 (Rp) | Nominal harus bilangan bulat 100 sampai 100.000.000 (Rp) |
| `admin.err.gift.qrisMinNoChange` | 金额没有改动 | The amount hasn't changed | Nominal tidak berubah |
| `admin.gifticon.noJpeg` | 不支持 JPG/JPEG —— 礼物图标需要透明底，而 JPEG 不支持透明。请导出为 PNG（透明背景）后再上传。 | JPG/JPEG is not supported — gift icons need a transparent background, which JPEG cannot store. Export as PNG with transparency and upload again. | JPG/JPEG tidak didukung — ikon hadiah butuh latar transparan, yang tidak bisa disimpan JPEG. Ekspor sebagai PNG transparan lalu unggah lagi. |
| `admin.gifticon.heic` / `.badType` / `.readFailed` / `.tooLarge` / `.unmeasurable` / `.uploadFailed` | 与 `admin.tagicon.*` 同义，「标签图标」→「礼物图标」（如 badType「礼物图标只支持 PNG / WebP（需要透明底）。」） | 同义，「Tag icons」→「Gift icons」 | 同义，「Ikon label」→「Ikon hadiah」 |
| `admin.gifticon.tooSmall` | 图标尺寸 {0}×{1} 太小，最短边至少 {2} 像素。 | The icon is {0}x{1}, too small — the shorter side must be at least {2} px. | Ukuran ikon {0}x{1} terlalu kecil — sisi terpendek minimal {2} px. |
| `admin.gifticon.required` | 新建礼物必须上传图标。 | A new gift must have an icon. | Hadiah baru wajib punya ikon. |
| `admin.gifticon.spec.create` | 推荐 112×112 像素；PNG 或 WebP，透明底；≤ {1} KB，最短边 ≥ {0} 像素。 | Recommended 112x112 px; PNG or WebP with a transparent background; up to {1} KB, shorter side at least {0} px. | Disarankan 112x112 px; PNG atau WebP dengan latar transparan; maksimal {1} KB, sisi terpendek minimal {0} px. |
| `admin.gifticon.spec.edit` | 不选文件即保留原图标。推荐 112×112 像素；PNG 或 WebP，透明底；≤ {1} KB，最短边 ≥ {0} 像素。 | Leave empty to keep the current icon. Recommended 112x112 px; PNG or WebP with a transparent background; up to {1} KB, shorter side at least {0} px. | Kosongkan untuk mempertahankan ikon lama. Disarankan 112x112 px; PNG atau WebP dengan latar transparan; maksimal {1} KB, sisi terpendek minimal {0} px. |
| `admin.audit.action.GIFT_CATALOG_CREATED` / `_UPDATED` / `_OFFLINED` / `_ONLINED` | 新建礼物 / 编辑礼物 / 下线礼物 / 重新上线礼物 | Gift created / Gift edited / Gift taken offline / Gift back online | Hadiah dibuat / Hadiah diubah / Hadiah dinonaktifkan / Hadiah diaktifkan lagi |
| `admin.audit.action.GIFT_QRIS_MIN_UPDATED` | 修改送礼 QRIS 最低金额 | Gift QRIS minimum changed | Minimum QRIS hadiah diubah |

只读提示复用 `admin.v130.err.forbidden`（`{0}` 填 `#{perm.gift.catalog_edit}`）；通用「取消」复用 `admin.confirm.cancel`。文案中的金额在后台一律千分位逗号（后台惯例），App 侧的点号千分位与本 story 无关。

### 本 story 不做

- 全站送礼记录页、抽屉「收送礼记录」、`gift.order_view`（Story 3.2）。
- 礼物图标资产与首批 40 个礼物录入（运营在后台录，按 UI 稿 I3 出图规范，非代码交付）。
- 最低等级上限改读 ① 等级表（Story 4.1 对齐）。
- 不改 App、不改送礼发起 / 交付 / 退回逻辑（1.1 / 1.2），不新增迁移，不改 `AdminConfigService` / `/admin/config`。

### 测试

- **L0（云端必须全绿）**
  - `admin/gift/service/AdminGiftCatalogServiceTest`（mock 仓储 / 审计 / 图标服务）：价格 99 / 100 / 100,000,001；名称空白 / 41 字；EXCLUSIVE 缺 `minLevel`、`minLevel = 0 / 21`；NORMAL 带 `minLevel` → 落 null；`sortOrder` 空 → 0；新建缺图标 → 422；编辑不传图 → 保留原 `icon_url`；无变化不写审计；摘要含改前改后；下线幂等；QRIS 预检不写库、99 / 当前值相同 → 422、保存写 `config_change_logs(PRICING, gift_qris_min_amount)` + 审计 1 条。
  - `admin/tagicon/AdminTagIconServiceTest`（补）：`IconPurpose.GIFT` 上传 key 前缀 `public/gift-icon/`、JPEG 报礼物版文案；原单参方法行为不变（既有用例原样绿）。
  - 既有守卫：`AdminPageCatalogTest`、`AdminPermissionsTest`（80）、`AdminPermissionMatrixStaticTest`（80）、`AdminRoleTest`、`AdminPermissionWiringTest`、`AdminActiveKeyCatalogTest`、`AuditActionLabelsTest`。
- **L1（写好能编译；云端不跑，标「L1 待本地验收」）**
  - `admin/gift/AdminGiftCatalogIntegrationTest`（`extends ApiIntegrationTest`，真实端点 + htmx 头；OSS 用既有测试桩，**缺凭证打桩、勿配假 AK**）：EDIT 用户新建礼物（PNG 桩）→ 库里一行 ONLINE、审计 1 条、`GET /api/v1/gifts/catalog?petId=` 返回它；JPG → 422 且文案含「礼物图标」；EXCLUSIVE 缺等级 → 422；下线 → 目录接口不再返回、已有 `gift_pet_wall` 行不变；改价 → 先建一笔 PENDING 的 QRIS 订单（Rp5,000），改价为 8,000 后模拟 `GiftPaidHandler` 交付 → 订单 `total` 仍 5,000、墙 `unit_price` 5,000；QRIS 最低金额预检返回弹层（含 `Rp10,000 → Rp15,000`）、确认后库里 15,000、`config_change_logs` 1 行、Rp10,000 的送礼 QRIS → 422 `gift-qris-below-min`、同额 keepsake QRIS 照常发起；VIEW 用户：页面无「新建礼物」、「修改」禁用、所有 POST 403；未授权账号 GET 页 403。
  - `AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate`、`AdminNavPermissionMatrixTest`、`AdminPageCatalogCoverageTest` 既有用例覆盖新页。
  - 跑法：`DB_NAME=<新建空库> ./mvnw -B clean package`（先 flush Redis DB0，不 FLUSH 共享库）。
- **L2**：本地后台人工过一遍 L1 ~ L7（含中 / 英 / 印尼三语切换）。

#### 验证层级

| 层级 | 内容 | 环境 |
|---|---|---|
| L0 | `mvn -B clean package -DskipTests` + `AdminGiftCatalogServiceTest`、`AdminTagIconServiceTest`、`AdminPageCatalogTest`、`AdminPermissionsTest`、`AdminPermissionMatrixStaticTest`、`AdminRoleTest`、`AdminPermissionWiringTest`、`AdminActiveKeyCatalogTest`、`AuditActionLabelsTest`；`check-i18n-keys.sh`、`check-admin-hardcoded-text.sh`、`check-admin-permission-consistency.sh` | 云端可跑 |
| L1 | `AdminGiftCatalogIntegrationTest`、`AdminPagesRenderSmokeTest`、`AdminNavPermissionMatrixTest`、`AdminPageCatalogCoverageTest`；全量 `mvn -B clean package` | 本地 Docker postgres + redis |
| L2 | 后台 L1 ~ L7 人工验收、三语 | 本地浏览器 |

### 待确认（按推荐实现，Completion Notes 记一句）

1. **图标「正方形」**：epics / 架构写「正方形」，复用的 `AdminTagIconService` 2026-09-02 已取消正方形要求、UI 稿也未要求 → 推荐不校验正方形（运营按 I3 出 112×112 即可）。
2. **QRIS 新金额的输入位置**：UI L6 把「新金额」画在弹层里；共享件 `AdminConfirmModal` 没有通用输入槽 → 推荐输入放配置行的行内展开，弹层复述新旧值（与等级表 / 版本配置的服务端确认同一形态），不改共享件。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-gift.md#Story 3.1]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-gift-delta.md#AD-1 / AD-4 / AD-11 / §5 / §6]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/1-4-0送礼prd.md#3.3 【后台】礼物目录配置（AB-35）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#③ 送礼 · 第二步「后台小问题」]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/ui-admin-1-4-0-gift-integrated-v1.html#L1 ~ L7]
- [Source: _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md#2026-10-18 V1.4.0 四条线共用地基]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/4-1-成长体系配置页.md（后台 story 范本：权限码 / 目录 / 审计 / 服务端确认弹层）]

## Dev Agent Record

### Agent Model Used

Claude (cloud dev agent)

### Debug Log References

- `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./mvnw -B clean package -DskipTests` → BUILD SUCCESS（含测试编译，L1 新类能编译）。
- `LANG=C.UTF-8 LC_ALL=C.UTF-8 ./mvnw -B test -Dtest='AdminGiftCatalogServiceTest,AdminTagIconServiceTest,AdminTagEditKeepsIconTest,AdminPageCatalogTest,AdminPageCatalogAuthorityTest,AdminPermissionsTest,AdminPermissionMatrixStaticTest,AdminRoleTest,AdminPermissionWiringTest,AdminActiveKeyCatalogTest,AuditActionLabelsTest,AdminMessagesParityTest,AdminMessagesExternalizedTest,AdminNavModelTest,AdminRetiredRoutesStaticTest,AdminFragmentResponsesTest,AdminConfigServiceTest,RolePermissionResolverTest,AdminRoleServiceTest,AdminAccountRoleServiceTest,ConsultShopBoundaryTest,GiftModuleBoundaryGuardTest,GiftCatalogServiceTest,GiftSendServiceTest,GiftDeliveryServiceTest,GiftPaidHandlerTest,GiftPaymentFailedHandlerTest,GiftRefundServiceTest,GiftRefundRetryJobTest,GiftNotifyListenerTest,KeepsakePurchaseServiceTest,TailsonalityUpgradePricingTest,AiUnlockServiceTest,FreeQuotaServiceTest' -Dsurefire.failIfNoSpecifiedTests=false` → 309 tests，0 失败。
- 首轮 `AdminActiveKeyCatalogTest` 红：Controller 里 `active` 用了常量 `ACTIVE`，守卫要求字面量 → 改为 `"gift-catalog"` 字面量后绿。
- `bash scripts/ci/check-i18n-keys.sh`（3783 key 四包相等）、`check-admin-hardcoded-text.sh`（裸文案 0）、`check-admin-permission-consistency.sh`（权限册 80 · 零差异）、`check-flyway-versions.sh origin/main`（OK，本 story 无新迁移）全绿。
- 模板渲染：开发期用临时 SpringTemplateEngine + WebContext 脚本直渲 `gift-catalog-list :: rows(true)`、`drawer-gift`（可编辑 / 只读两态）、`createForm`、`gift-qris-line`（两态）、`confirm-modal`，无 Thymeleaf 解析错误；脚本已删除，未入库（整页渲染由 L1 `AdminPagesRenderSmokeTest` 覆盖）。

### Completion Notes List

**T0 开工核对结论**
- 0.1 1.1 实体 / 仓储：`gift.domain.GiftCatalogItem`（`create(...)` 工厂，原只读）/ `GiftKind` / `GiftCatalogStatus`，`gift.repository.GiftCatalogRepository`（`findByStatus` / `findByPublicToken`）、`GiftOrderRepository`；列名与 `V20261018_1700` 一致（`for_cat/for_dog/for_other`、`kind`、`min_level`、`sort_order DEFAULT 0`、`status`、`updated_by`、`ck_gift_catalog_exclusive_level`、`ix_gift_catalog_online`）；`gift.service.GiftTokenGenerator` 存在。本 story 给 `GiftCatalogItem` **追加**写方法 `edit(...)` / `setStatus(...)` / `setUpdatedBy(...)`（1.1 类注释明确「后台录入与改值在 3.1 再加写方法」），既有方法与 App 侧契约不变。
- 0.2 `PricingConfig.giftQrisMinAmount` 只有 getter（1.1）→ 本 story 加 `setGiftQrisMinAmount`；送礼发起读 `PlatformConfigService.pricing().getGiftQrisMinAmount()`，类注释「无缓存」、单行直读 → 改值立即生效。
- 0.3 `GiftCatalogService.catalog` 只取 `findByStatus(ONLINE)` 且每次查库；`GiftSendService` 发起校验 `isOnline`（④）与 QRIS 最低金额（⑧）；交付读订单快照（`GiftDeliveryService` / `GiftPaidHandler`）→ AC6.6 / AC7.2 成立。
- 0.4 `gift_orders.gift_id` 无专门索引：收送统计（`GiftOrderRepository.deliveredTotalsOfGift`，一条原生 SQL）顺序扫可接受，不加迁移。
- **story 文字修订（T0 核对后先改本文件）**：① AC5.2 `specText(IconPurpose)` → `specText(IconPurpose, String context)`（单参选不出新建 / 编辑两段规格文案）；② AC3.2 / 3.3 「SQL ORDER BY / 一次 SQL 聚合」→ 一次 `findAll` + 服务层排序 / 计数（目录百级；摘要、价格下拉、表格出自同一次读取，避免可空参数在原生 SQL 里的类型推断问题）；③ 校验表 `admin.err.gift.iconRequired` 单独成键（与 `admin.gifticon.required` 同文案），因 `AdminMessagesExternalizedTest` 要求后台异常挂 `admin.err.*` 码且死键检查要求该键在 Java 里被引用。Dev Notes 表中的 file:line 以本分支实际为准（`AdminPermissions` / `AdminPageCatalog` 行号有偏移，常量与位置语义一致）。

**实现要点**
- 权限：`GIFT_CATALOG_VIEW = "gift.catalog_view"` / `GIFT_CATALOG_EDIT = "gift.catalog_edit"` 加在 `OPS_NOTIFY_SEND` 之后，分别进 view / edit 组末尾；`ALL` 78 → 80；不预授任何预置角色（`AdminRole` / `admin_role_permissions` 未动，`OPS_MANAGER` 仍 41）。
- 页面目录：`gift-catalog` 插在 `algo-params` 与 `share-reward` 之间，`navCodes = VIEW, EDIT` 与 Controller `VIEW_AUTH` 逐字同集。
- 图标：`IconPurpose { TAG("tag-icon","admin.tagicon."), GIFT("gift-icon","admin.gifticon.") }`；三个重载；原单参方法签名与行为不变（内部委托 TAG），用户标签 / 内容标签调用方未改；**不加正方形校验**（待确认 1）。
- 后台服务 `AdminGiftCatalogService`：校验先于上传（不合规不留孤儿图标）；新建 `public_token` 经 `GiftTokenGenerator`、`status = ONLINE`、`updated_by = adminId`；编辑无真变化不写库不写审计、换图只记 `icon changed`；上下线幂等；QRIS 预检与保存同一校验方法，保存同事务写 `pricing_config` + `config_change_logs(PRICING, gift_qris_min_amount)` + 审计 `GIFT_QRIS_MIN_UPDATED`（`pricing_config` / `gift_qris_min_amount`）。未改 `AdminConfigService`、`PaymentIntentService.createIntent`、送礼发起 / 交付 / 退回逻辑；`PawCoinWalletService` 零改动；无新迁移、未碰任何 CHECK。
- 类型改专属 / 普通只影响之后的新发起（交付不再校验等级，AD-7）。
- 模板：页 `gift-catalog.html`（`#confirm-host`、刷新监听 `hx-include="closest form"` 保留筛选、`?open=` 深链兜底、`?create=1`）；`gift-catalog-list`（sumbar / rows / row）；`drawer-gift`（drawer / createForm / created / afterAction + 类型切换与「未改不可保存」脚本，`disabled` 由脚本加）；`gift-qris-line`（line / saved）。admin.css 只追加 `.gift-table .num` / `.gift-cfgline*` / `.gift-kv` 几条。
- 文案：95 个新键四份 messages 同步（`messages.properties` = en）。

**L1/L2 待本地验收**（云端无 Docker，未跑）
- L1：`AdminGiftCatalogIntegrationTest`（新建 → App 目录可见 → 下线不可见 + 审计幂等；JPG 422 含「礼物图标」、专属缺等级 422；改价后 PENDING QRIS 订单交付仍按 Rp5,000 快照；QRIS 预检弹层含 `Rp10,000 → Rp15,000`、保存后 15,000 + `config_change_logs` +1、Rp10,000 送礼 QRIS → `gift-qris-below-min`、同额 PAWCOIN_TOPUP QRIS 照常发起、同值 422；VIEW 用户页面无「新建礼物」、配置行只读、抽屉只读、所有写端点与新建抽屉 403；无权限 403）。
- L1：`AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate`、`AdminNavPermissionMatrixTest`、`AdminPageCatalogCoverageTest`、`AdminRoleSeedParityTest`（已补两码不预授断言）、`AdminUserTagIntegrationTest` / `AdminUserTagDrawerIntegrationTest`（图标服务重载回归）；全量 `mvn -B clean package`（`DB_NAME=<新建空库>`，先 flush Redis DB0）。
- L2：本地后台人工过 L1 ~ L7（新建 / 编辑 / 换图 / 下线原生 confirm / 重新上线 / QRIS 行内展开 → 弹层 → 保存 toast / 只读账号），中 / 英 / 印尼三语切换；类型单选切换最低等级显隐；未修改时保存钮置灰。
- L1 测试数据注意：`AdminGiftCatalogIntegrationTest` 每个用例前后把 `pricing_config.gift_qris_min_amount` 复位为 10000（全库单行，避免影响其他送礼用例）。

**待确认**
1. 图标「正方形」：epics / 架构写「正方形」，复用的 `AdminTagIconService` 2026-09-02 已取消正方形要求、UI 稿也未要求 → 按推荐**不校验正方形**（只保留 PNG / WebP、≤512KB、最短边 ≥42）。
2. QRIS 新金额输入位置：UI L6 画在弹层里；共享件 `AdminConfirmModal` 无通用输入槽 → 按推荐输入放配置行行内展开，弹层只读复述服务端算好的新旧值，未改共享件。
3. 最低等级上限：本分支无 ① 等级表，用 `GiftCatalogRules.MAX_LEVEL_FALLBACK = 20`（类注释已写「4.1 对齐时改读 ① 等级表最高级」）。
4. 收送统计 `{0}` / `{1}` 经 MessageFormat 按界面语言格式化数字（印尼语界面千分位为点号）；金额（Rp）一律千分位逗号（后台惯例）。如需统计数字也固定逗号，另行确认。
5. `scripts/ci/admin-write-ops-allowlist.txt`（V1.3.0 基线 diff 白名单）未登记新端点：该白名单自 V1.3.4 起未随新页维护（ops-popups / ops-notifications 均未登记），本 story 未擅自补；若 CI 仍以 V1.3.0 基线跑 diff，需编排者决定是否登记。

**与 ① / ② 合并**：见 Dev Notes「与 ① / ② 合并时会冲突的点」——`AdminPageCatalog` 顺序 `algo-params → growth → gift-catalog`；权限计数测试与记账注释按并集重写；`AuditActions` / 四份 messages / `AdminHxEvents` / `admin.css` 末尾纯追加。

### File List

新增
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/service/AdminGiftCatalogService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/service/GiftCatalogRules.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/web/AdminGiftCatalogController.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/dto/GiftCatalogRow.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/dto/GiftCatalogSummary.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/gift/dto/GiftCatalogForm.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/tagicon/IconPurpose.java`
- `petgo-backend/src/main/resources/templates/admin/gift-catalog.html`
- `petgo-backend/src/main/resources/templates/admin/fragments/gift-catalog-list.html`
- `petgo-backend/src/main/resources/templates/admin/fragments/drawer-gift.html`
- `petgo-backend/src/main/resources/templates/admin/fragments/gift-qris-line.html`
- `petgo-backend/src/test/java/com/tailtopia/admin/gift/service/AdminGiftCatalogServiceTest.java`（L0）
- `petgo-backend/src/test/java/com/tailtopia/admin/gift/AdminGiftCatalogIntegrationTest.java`（L1）

修改
- `petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shared/AdminPageCatalog.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/audit/service/AuditActions.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/shared/web/AdminHxEvents.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/tagicon/AdminTagIconService.java`
- `petgo-backend/src/main/java/com/tailtopia/config/domain/PricingConfig.java`
- `petgo-backend/src/main/java/com/tailtopia/gift/domain/GiftCatalogItem.java`
- `petgo-backend/src/main/java/com/tailtopia/gift/repository/GiftOrderRepository.java`
- `petgo-backend/src/main/resources/i18n/messages.properties` / `messages_en.properties` / `messages_id.properties` / `messages_zh_CN.properties`
- `petgo-backend/src/main/resources/static/admin/admin.css`
- `petgo-backend/src/test/java/com/tailtopia/admin/account/domain/AdminPermissionsTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/account/domain/AdminRoleTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/roles/AdminRoleSeedParityTest.java`（L1）
- `petgo-backend/src/test/java/com/tailtopia/admin/shared/AdminPageCatalogTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/shared/AdminPermissionMatrixStaticTest.java`
- `petgo-backend/src/test/java/com/tailtopia/admin/tagicon/AdminTagIconServiceTest.java`
- `_bmad-output/implementation-artifacts/v1.4.0/3-1-后台礼物目录与qris最低金额.md`
- `_bmad-output/implementation-artifacts/v1.4.0/sprint-status-v1.4.0-gift.yaml`

### Change Log

- 2026-10-18：实现 Story 3.1（后台礼物目录 + 送礼 QRIS 最低金额）：权限两码（78 → 80，不预授）、页面目录、图标服务 `IconPurpose` 重载、后台服务 / Controller / 四个模板、审计 5 常量、95 个四语文案；L0 309 tests 全绿，CI 文案 / 权限 / flyway 脚本全绿；L1 / L2 待本地验收。T0 核对后修订本文件 AC3.2 / 3.3 / AC5.2 / 校验表三处。
- 2026-10-18：bmad-code-review 对抗式复审（权限 / CSRF / 审计 / 服务端校验与 DB CHECK / 图标校验与 OSS 路径 / 下线与改价快照 / QRIS 预检-确认 / 模板转义 / 回归）：无 CONFIRMED 缺陷，未改代码；PLAUSIBLE 项（`pricing_config` 无行锁 / 无 `@Version` 的并发改值、模板 B 筛选栏在摘要条之上与 UI L1 顺序不同、QRIS 预检 422 后错误槽残留）留待本地 L2 判断。L0 重跑全绿。
