# V1.4.1 收尾执行计划（2026-10-30）

> 用户离开前确认的完整规划。执行中每完成一步就在这里打勾、补提交号与结论。
> **上下文被压缩后，先读这份文件再继续。** 中途非重大问题不打断用户。

## 授权与边界

- ✅ 可做：在 `dev_1.4.1` 上切分支、提交、合回 dev、push；在主工作区**真实 stag 分支**合并 → push → `SKIP_BUILD=1 ./scripts/deploy-backend-stag.sh`（先 `mvn -B clean package`）；打 stag 包（debug key、连 api-stag）装 Android 模拟器。
- ✅ stag 造数：可直接写 `petgo_stag` 库、在服务器上给测试号签 JWT、用 stag 支付模拟。**最后必须给用户一份造数清单。**
- ⛔ 不做：推 main、碰生产（容器 / env / 库，只读也不碰）、出提审包、改 pubspec version、把 Bug 结论写回 Bug 表。
- 测试账号：模拟器上现有的 Google 号。要测「未绑定门店」时，在 stag 库里删掉它的绑定记录。
- 人群筛选：**只核对后台算出的人数，不真发通知、不上线弹窗。**
- 全员推送：跳过。
- 新 bug：能修的都修，并认领（认领 = 写 Bug 表「开发人员」字段，走 restricted_update）。需要产品拍板或改动大的，只出诊断。结论一律不写回。
- 本地习惯：git 写操作走 python subprocess；不整文件 dart format；8080 端口不碰；`petgo_app/pubspec.lock` 被会话启动的 hook 改动后不提交；L1 用独立库 + 独立 REDIS_DB。

## 步骤清单

### 0. Bug 复查
- [ ] `/bug-pull`：10-30 拉到 3 条（599 / 600 / 604），都已修、都在 dai 名下。**合 stag 前再拉一次**，有新报的按上面的规则处理。

### 1. 视频从 1.4.1 回滚（分支 `fix/1.4.1-revert-video`）
- [ ] `git revert 21ec584f`（收藏补视频帖口径）
- [ ] `git revert -m 1 17cb409e`（视频线合并）。10-30 已在临时工作区试过：无冲突、mvn test-compile 通过、flutter analyze 零问题。
- [ ] 3 条视频迁移（V20261016_1550 / 1657 / 1930）一并撤掉。stag 已应用过，但 stag env 配了 `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:missing`，启动不受影响。以后视频上线时把同样的迁移文件原样加回。
- [ ] stag 上已有的视频帖不处理，当作兼容性测试：回退后应显示成以封面为图的普通帖。

### 2. Diary 页头 5 处（分支 `feat/1.4.1-diary-header`）
设计稿：`~/Downloads/ui-1-4-1-diary-header-restore-v1.html`（执行说明在稿内 doc-header，照做）。
- [ ] ① `diary_header.dart _entryGrid`：两个入口合成一张白卡、上下两行。图标块 32×32 mintTint r9；图标 check_circle_outline / auto_awesome_outlined；右侧 chevron；行间 1px 分隔线左缩进 54；key 和 insightsEntryAnchor 沿用。
- [ ] ② `_milestoneBarCard` 恢复 988c25e5 父提交的样式：无描边、加阴影、r12、padding 15/11、字号 12、计数紫色、进度槽 5 高 cream2 底。
- [ ] ③ `_viewToggleRow / _toggleBtn` 恢复：两枚按钮 Expanded 等分、间距 7、r10、竖向 padding 9。
- [ ] ④ `_ArchiveBody / _SwitchingBody` 的 ListView 顶部 padding 改为 `MediaQuery.paddingOf(context).top + 8`。
- [ ] ⑤ 作者态且有切换器时去掉「Diary {name}」标题行：第一行改为「Expanded(切换器) + 分享 + 8 + 编辑」，下面到宠物卡间距 12。`_SwitchingBody` 同步改，删掉 diarySwitchingTitle。PetSwitcher 的 padding 去掉 bottom 4。游客态、访客态不变。
- [ ] 测试：按稿里的说明改 diary_guest_state_test 等，`flutter test test/profile` 全绿。

### 3. QRIS 支付页（分支 `feat/1.4.1-qr-payment`）
设计稿：`~/Downloads/ui-1-4-1-qr-payment-integrated-v1.html`；logo 在 `~/Downloads/assets/pay-logos/`（9 张，App 资源目录建议用 `assets/payment/`，并在 pubspec 登记）。
- 后端
  - [ ] `pay/dto/PaymentIntentResponse` 加 `Instant expiresAt`（`of()` 里取 `p.getExpiresAt()`）。送礼、纪念品、名额、身份证高清、AI 解锁、Passport、问诊会自动带上。
  - [ ] `TopupResponse` 补 `displayNo`（充值订单号）。
  - [ ] 顺手把 PaymentIntent 实体上「expires_at 仅充值填」的过时注释改掉（只改代码注释，不动已应用的迁移）。
- App
  - [ ] `showQrPaymentSheet` 新增可选参数 `amountIdr`、`expiresAt`，返回类型不变。抽屉固定 60% 屏高、内部滚动、按钮吸底并带 SafeArea。
    - 首屏：金额 + 倒计时一行 → 二维码 → 订单号（带复制图标，复制后提示 commonCopied）→ 等待中。
    - 往下：9 个 logo +「+ lainnya」→ 5 条付款步骤。
    - 吸底：「Simpan Kode QR」主按钮 + 红色文字按钮「Batalkan Pembayaran」。
  - [ ] 保存二维码：按 S1 规格渲染（TailTopia 品牌名 + 二维码 + 金额 + 订单号 + `Berlaku sampai d MMM yyyy, HH:mm`），宽 1080，复用 card_render；成功提示 cardSavedToGallery；权限被拒时弹对话框（标题和按钮复用 mediaPermissionDeniedTitle / commonCancel / mediaOpenSettings，只换正文）。
  - [ ] A4 过期态：倒计时归零 → 倒计时变 00:00 灰色、二维码蒙白并提示过期、等待中消失、按钮只剩「Tutup」，同时停止轮询；原有的过期 Toast 照旧。
  - [ ] 到期时刻格式：当天显示 `HH:mm`；跨天显示 `d MMM, HH:mm`；按手机本地时区，不带时区后缀。倒计时 = expiresAt − 当前时间。
  - [ ] 5 个调用方传入金额和 expiresAt：
    - 商城：订单号改传 displayNo；expiresAt 取订单详情。
    - AI 解锁 / 送礼 / 身份证高清 / keepsake（共 5 个使用方）：解析 payment.expiresAt 和 amount。
  - [ ] 充值页 B1（`_payingView`）：加金额卡（含「= n koin」）、订单号、logo、步骤、吸底保存按钮；不加取消按钮；B6 无 payload 时显示占位框，保存按钮置灰。B0 / B4 / B5 不改。
  - [ ] 问诊 C1（`_buildAwaitingCash`）：同 A1，倒计时用 payDeadlineAt、红色强调；C3 过期保持现状（Toast 后离开）。
  - [ ] 新文案加进 app_id.arb 和 app_en.arb（见稿内表格），原「Pindai QR untuk membayar」提示并入步骤 2。
  - [ ] 测试：qr_payment_sheet_test、recharge_page_test、vet_timed_pay_page_test、keepsake_pay_flow_test、shop_order_detail_page_v2_test、ktp_unlock_analytics_test 等全绿，新增功能补测试。

### 4. Toko banner 曝光 + 单张可点击（分支 `feat/1.4.1-banner-tracking`）
- [ ] 新迁移（时间戳号）：`shop_banners` 加不可枚举的公开 token（存量行回填）、`target_type`（NONE / PRODUCT / CATEGORY / EXTERNAL，默认 NONE）、`target_value`，加 CHECK 约束：NONE ⇔ target_value 为空。
- [ ] 后台 banner 表单加跳转配置并校验：PRODUCT 必须是存在的商品 token；CATEGORY 必须在枚举里；EXTERNAL 只允许 https。
- [ ] `/api/v1/shop/banner` 下发 `bannerId`（即公开 token）、`targetType`、`targetValue`。旧版 App 会忽略这几个字段。
- [ ] App 点击跳转：PRODUCT → 商品详情；CATEGORY → 切到 Toko 页对应品类；EXTERNAL → 系统浏览器打开（url_launcher，只允许 https）；NONE → 不可点。
- [ ] 埋点 `toko_banner_shown`：露出超过 50% 时上报，一次进入 Toko 页内只报一次，离开再进来重新计；属性 `banner_id`、`position=1`。
- [ ] 埋点 `toko_banner_tapped`：属性 `banner_id`、`target_type`（product_detail / category_list / external_link）、`position=1`；「领券」先预留不上报。
- [ ] 按仓库惯例登记事件（查 v112_events_test 等事件注册测试），属性用 snake_case；后端、后台、App 都补测试。

### 5. 本地全量检查
- [ ] App：`flutter analyze` + 全量 `flutter test`
- [ ] 后端：`mvn -B clean package` + 全量 `mvn test`（L1 用独立库，如 `petgo_l1_141final` + 独立 REDIS_DB）。已知 2 个与 nanoTime 有关的旧报错，与本次无关。
- [ ] 迁移版本号检查：`bash scripts/ci/check-flyway-versions.sh origin/main`

### 6. 合并与部署
- [ ] 各分支合进 `dev_1.4.1` → push
- [ ] 再拉一次 Bug（见第 0 步）
- [ ] 主工作区切 stag → 合并 dev_1.4.1 → push → `mvn -B clean package` → `SKIP_BUILD=1 ./scripts/deploy-backend-stag.sh` → 健康检查 200。合并后停在 stag。
- [ ] 提醒用户：hex 的 env 副本要用 sudo 同步（AUTO_FOLLOW_USER_IDS=131）。

### 7. 装模拟器
- [ ] 打 stag APK（debug key，连 api-stag，带 stag 专属参数），装到 emulator-5554（Pixel_9_Pro_API_36），版本号不改。

### 8. UI 还原度检测（每屏 ≥95%）
- [ ] Diary D2；支付 A1 / A5 / A2 / A3 / A4；充值 B1 / B1b / B2 / B6；问诊 C1 / C1b / C2；S1 导出图。逐屏截图对比设计稿并打分，不到 95% 的写明原因。
- [ ] stag 的 QRIS 如果仍出不了二维码，二维码屏按 B6 验；付款成功的流程用 stag 支付模拟走。

### 9. 测试用例与执行
- [ ] 写 `_bmad-output/implementation-artifacts/v1.4.1/test-cases-v141.md`，覆盖：门店邀请码、bug 599 / 600 / 604（604 只验限速逻辑，不真发全员推送）、TailUs 自动关注、人群筛选（只核对人数）、支付页三处、Diary 页头、banner 曝光和点击、视频回滚后的兼容性、收藏页、ANR 修复回归。
- [ ] 在模拟器上逐条执行，记录结果和截图，输出问题清单。
- [ ] 交付：结果汇总 + 造数清单 + 未完成项和原因。

## 进度记录
（每完成一步在下面追加：日期时间 · 步骤 · 提交号 · 结论）
