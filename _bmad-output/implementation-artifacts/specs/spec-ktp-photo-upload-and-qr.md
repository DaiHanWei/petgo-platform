# KTP 照片上传 + 背面二维码 — 实施规格

> 产出：2026-07-17 · 分支 `v1.1-dev` · 依据代码实读
> 状态：**待确认 1 项（App Store 上架状态，§4），其余按已定决策可实施**

---

## 1. 需求（用户 2026-07-17）

1. KTP 编辑增加**照片上传**，上传后覆盖正面红区（宠物照片位）
2. KTP 背面白区显示**二维码**
3. 扫码后跳 Google Play / App Store / 启动 app（已装唤起）

## 2. 决策（用户 2026-07-17 已拍板）

| # | 决策 | 结论 |
|---|---|---|
| D1 · 照片语义 | **改宠物档案头像（持久）** | 上传即 PATCH 宠物档案 avatarUrl，KTP 正面与「我的宠物」头像一致、重进不丢 |
| D2 · 扫码落地 | **新建下载引导落地页** | `s.tailtopia.id/get` 后端直出，JS 判平台跳商店 + 尝试 `tailtopia://` 唤起已装 app |
| D3 · 免责声明 | **二维码 + 免责声明并存** | 背面白卡重排：二维码 + 「扫码下载」+ 免责声明小字保留（合规必做，AC2/AC4 不可删） |

## 3. 现状（代码实读）

| 事实 | 位置 |
|---|---|
| **正面「红区」= 照片位，已接宠物头像**：有 avatarUrl 渲染 NetworkImage，无则淡紫占位（设计稿红色仅标记） | `ktp_card.dart:257-265` |
| avatarUrl 来源链：`IdCardDataResponse.avatarUrl` ← `PetProfile.getAvatarUrl()`（宠物档案头像） | `IdCardService` / `IdCardDataResponse:21` |
| **改档案头像端点已存在**：`PATCH /api/v1/me`（`PetProfileUpdateRequest` 含 avatarUrl）；前端 `profileRepository.update(avatarUrl:)` 已有 | `ProfileApiController:109` / `profile_repository.dart:59` |
| KTP 文字编辑是**会话级不落库**（`KtpEdits`，AC3：娱乐仿制不污染真实档案） | `id_card_page.dart:294` |
| **背面白卡现是免责声明**（合规必做 AC2/AC4） | `ktp_card_back.dart:79` |
| **落地页直出范式**：`LegalPageController`（后端直出静态 HTML，`legal.tailtopia.id` 在用） | `shared/web/LegalPageController.java` |
| **app 深链 scheme 已有**：`tailtopia://card/{token}`（成长档案分享页唤起 app） | `AndroidManifest.xml:50` / `Info.plist:44-56` |
| 二维码组件已有（充值页在用 `QrImageView`，qr_flutter） | `recharge_page.dart` |

## 4. 待确认 🔴（不阻塞主体）

**App Store 是否已上架？** 落地页 iOS 分支要真跳 App Store 需要上架链接。iOS 走公司账号（PT SUNGAI KECIL SEJAHTERA）。
- 已上架 → 填 App Store URL
- 未上架 → iOS 分支先占位（跳官网/「敬请期待」），Android 分支正常跳 Play（包名 `com.tailtopia.app`）

## 5. 实施

### 5.1 前端

| # | 改动 | 层级 |
|---|---|---|
| F1 | **KTP 编辑加「上传照片」入口**（`_KtpEditSheet` 或编辑页）：复用建档头像上传（公开桶，`MediaScope.public` → 拿 avatarUrl）→ `profileRepository.update(avatarUrl:)` **持久落档案**（区别于文字会话级）→ invalidate KTP data + 宠物档案 provider 刷新正面。上传中 loading，失败 toast | L0 + L2 |
| F2 | **背面白卡加二维码 + 重排**（`ktp_card_back.dart`）：`QrImageView(data: 落地页URL)` + 「扫码下载 TailTopia」+ 免责声明缩为小字并存。落地页 URL 走 `--dart-define=PETGO_GET_URL` 默认 `https://s.tailtopia.id/get`（照 `card_link.dart` 子域约定） | L0 + L2 |
| F3 | i18n：「上传照片」「扫码下载」等新 key（`app_id.arb` 主 + `app_en.arb`）+ `flutter gen-l10n` + `microcopy_rules_test` | L0 |

> ⚠️ 照片持久落档案会打破 KtpEdits「全会话级」纯洁性——**这是 D1 有意为之**：照片是真实宠物照，落档案头像合理；文字（名字/地址仿制）仍会话级不落库。dev 需知悉这个区分。

### 5.2 后端

| # | 改动 | 层级 |
|---|---|---|
| B1 | **新增 `GetPageController`**（仿 `LegalPageController`）直出 `GET /get` 下载引导 HTML（自包含，内联 CSS/JS）：<br>① UA 判平台：Android → Play（`market://` 或 https Play URL），iOS → App Store（§4 待确认）<br>② 尝试 `tailtopia://`（既有 scheme）唤起已装 app，超时未唤起 fallback 商店<br>③ 页面兜底两个手动按钮（Play / App Store），JS 失败也能点 | L0 + L2 |
| — | 照片改档案头像：**后端无需改**（`PATCH /me` avatarUrl 已支持） | — |

### 5.3 运维

- **CF 路由**：`s.tailtopia.id/get` → 后端（`s.tailtopia.id` 已有隧道到后端 127.0.0.1:8084；加 path 路由或后端直接暴露 `/get`）。照 `runbook`/`domain-setup-cloudflare` 既有做法。
- 落地页里的商店 URL：Play = `https://play.google.com/store/apps/details?id=com.tailtopia.app`；App Store = 待 §4。

### 5.4 不做

QR 里放动态 token（本次是固定下载引导页，非每宠物专属）；deferred deeplink（安装后自动落特定页，属后续）；改文字编辑的会话级语义（仅照片持久）。

## 6. 验证

| 层 | 内容 | 环境 |
|---|---|---|
| **L0** | `flutter analyze` 零警告 + `flutter test`（含 KTP 富卡 widget 测试 + microcopy）；`mvn compile`（GetPageController） | 云端可跑 |
| **L1** | `/get` 端点返 200 text/html（无 DB 依赖，L0 基本够） | 本地 |
| **L2** | 真机：KTP 编辑上传照片 → 正面显示 + 「我的宠物」头像同步更新（持久，重进还在）；背面二维码渲染；**真机扫码** → Android 跳 Play / 已装唤起 app | 本地 Android 模拟器 + 真机扫码 |

### 云端执行须知
- 云端只到 L0 绿灯，L1/L2 待本地。
- 落地页 CF 路由 + App Store URL 是**本地/运维动作**，云端只出 controller + 前端。
- 分支 `v1.1-dev`（无 Flyway 迁移——本功能不加表/列）。

## 7. 影响面
- `PATCH /me` 触发名片 OG 图重渲染（`cardRerenderService`，既有联动）——改头像会连带重渲名片 OG 图，符合预期。
- 落地页是**新公开端点**（无鉴权），需确保只出静态 HTML、不泄任何数据。
