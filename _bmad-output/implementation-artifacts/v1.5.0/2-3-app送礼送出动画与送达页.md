# Story 2.3: App 送礼送出动画与送达页（E1 ~ E3）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-13 设计师新稿 + riv 实测）> PRD A §3.3 / §3.8（FR-189）> 架构 D-14 > App 稿 E 泳道与页头「待补素材」「实现提示」。
> 前置：2-2。
> 范围层：**只 App**。

## Story

As a 送礼人,
I want 付款成功后看到快递把礼物送到宠物那里,
so that 我知道礼物真的送到了。

## Acceptance Criteria

### AC1 — 素材入库 `[L0]`
1. 复制 `_bmad-output/planning-artifacts/v1.5.0/ui-gift-optimization/Send gift/send_gift.riv` → `petgo_app/assets/rive/send_gift.riv`；`Open gift/receive_gift.riv` → `assets/rive/receive_gift.riv`（2-5 用，本 story 一起入库）。
2. 礼盒图：把 `ui-gift-optimization/临时素材/gift_box_closed.png` 转成 WebP 存为 `assets/gift/gift_box_closed.webp`（若云端无转换工具，可先存 `gift_box_closed.png` 并在代码里按常量路径引用，Completion Notes 记一句，本地替换）；`pubspec.yaml` 的 `assets:` 加 `- assets/gift/`。路径用常量 `kGiftBoxClosedAsset`，正式图到了同名覆盖即可。

### AC2 — 送出动画 E1 / E2 `[L0]` `[L2]`
1. 新组件 `A/features/gift/presentation/gift_send_ceremony.dart`：全屏白底，`RiveWidget`（`Fit.cover`）加载 `assets/rive/send_gift.riv`，画板 `GiftSend`、状态机 `SendSM`。
2. 礼物图：优先数据绑定 `Gift.giftImage`（`dataBind(DataBind.auto())` 后设置图片属性）；运行时设置不生效则用 `assetLoader` 按资源名 `plushy-image` 替换为礼物图（下载 `giftIconUrl` 字节解码）。礼物图下载失败 → 用默认图，不阻塞。
3. 结束：监听 Rive 事件 `sendDone` 或 5.5 秒兜底，先到先走 → E3。底部「Lewati」（复用 `archivePromptSkip`，紫字，44 高热区）任意时刻直接到 E3。
4. 「减弱动态效果」（`MediaQuery.of(context).disableAnimations`）/ Rive 加载失败 / 文件缺失 → 直接显示 E3。
5. 播放中切后台再回来：继续或直接到 E3（不卡死），兜底计时以真实时间算。
6. 接法照 `A/features/place/presentation/widgets/stamp_ceremony.dart`（`assetLoader` 换图、`dataBind(DataBind.auto())`、`stateMachine.addEventListener`）。

### AC3 — 送达页 E3 `[L0]` `[L2]`
白底；宠物头像（圆）右下压礼盒（`kGiftBoxClosedAsset`）；灰字「Hadiahmu sudah sampai ke」+ 紫色大字宠物名（新文案拆两段：`Hadiahmu sudah sampai ke {petName}`，用富文本让宠物名换色）；「Kamu dapat {n} EXP」（`n` = PawCoin 送达时取接口响应 `senderExp`，QRIS 到账时取 `目录 senderExp × quantity`；`n = 0` 整行不显示）；紫色主按钮「Lanjut」（复用 `onboardingContinue`）吸底 → 回到进入送礼前的页面（`popUntil` 到送礼流程入口）。

### AC4 — 替换旧反馈 `[L0]`
线上 `gift_send_animation.dart`（`playGiftSendAnimation`）+ toast `giftSentToast` 在送礼成功路径上**不再调用**（文件保留，避免影响其他引用；若无其他引用，可删并在 Completion Notes 说明）。QRIS 付款时已离开 App、之后才付款成功 → 不播（线上行为不变）。

### AC5 — 文案 `[L0]`
新增：`Hadiahmu sudah sampai ke {petName}` / `Your gift has reached {petName}`、`Kamu dapat {n} EXP` / `You got {n} EXP`（2-5 共用）。

### AC6 — 测试 `[L0]`
Widget 测试：`disableAnimations = true` 直接渲染 E3；「Lewati」跳 E3；兜底计时到点进 E3（`fakeAsync`）；`senderExp = 0` 不显示 EXP 行。Rive 本身不在测试里渲染（用可注入的 builder 替身）。

## Tasks / Subtasks

- [x] 素材与 pubspec（AC1）
- [x] 送出动画组件（AC2）
- [x] 送达页（AC3）
- [x] 接到 2-2 的成功回调、移除旧 toast（AC4）
- [x] ARB + 测试（AC5、AC6）

## Dev Notes

- `rive: ^0.14.11` 已在 pubspec；`assets/rive/` 已声明（`tailtopia_stamp.riv` 在同目录）。
- 设计师待补 `sendDone` 事件：现文件没有，开发期一律走 5.5 秒兜底，属预期（PRD A §3.8「设计师补事件前，App 可先按状态机状态变化判断」）。
- QA 场景（PRD A §3.8）：点「Lewati」、加载慢 / 失败、减弱动态效果、切后台、不同尺寸礼物图——L2 本地测。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/gift_send_ceremony_test.dart`
- L2（本地）：真机 / 模拟器看 Rive（礼物图替换、5 秒播完进 E3、跳过），E1 ~ E3 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：真机 / 模拟器看 Rive（礼物图替换、约 5 秒播完进 E3、跳过、切后台、加载慢 / 失败、不同尺寸礼物图），E1 ~ E3 还原度。现 `send_gift.riv` 尚无 `sendDone` 事件，开发期走 5.5 秒兜底，属预期。
- L0：`flutter analyze` 无 error / warning；`flutter test test/gift`（含新 `gift_send_ceremony_test.dart` 5 条：减弱动效直出 E3、Lewati、兜底计时、加载失败、EXP=0）114 条全绿；其余送礼相关测试 199 条全绿。测试放 `test/gift/`。
- 素材：`send_gift.riv`、`receive_gift.riv` 已复制进 `assets/rive/`；礼盒临时图用 Pillow 无损转 `assets/gift/gift_box_closed.webp`（258×312），`pubspec.yaml` 加 `assets/gift/`，路径常量 `kGiftBoxClosedAsset`。2-2 包装页的默认礼盒同步换成该图（原图标占位）。
- 实现要点：`GiftSendCeremonyPage`（`gift_send_ceremony.dart`）：全屏白底；默认 Rive 接法照 `stamp_ceremony.dart`——先下载礼物图（3 秒超时，失败用文件自带图），`assetLoader` 按 `plushy-image` 换图 + 数据绑定 `giftImage`；监听 `sendDone`；Rive 运行时不可用 / 加载失败 → 直接 E3。兜底计时 + `WidgetsBindingObserver` 回前台按真实时间补判。送达页：头像右下压礼盒、富文本「Hadiahmu sudah sampai ke **{petName}**」（宠物名紫色大字，用占位分割实现）、「Kamu dapat {n} EXP」（0 不显示）、吸底「Lanjut」（复用 `onboardingContinue`）pop 回入口页。动画区可注入替身（`riveBuilder` / 测试钩子 `giftSendRiveBuilderOverride`）。
- AC4：送礼成功路径不再调用 `playGiftSendAnimation` 与 `giftSentToast`；`gift_send_animation.dart` 与其测试保留（无其他调用方，可后续清理）。`runGiftSendFlow` 的 `flightTargetKey` 参数保留但已不使用（入口不改签名）。
- 顺序调整：送达后先推送达页，同时刷新余额、回调入口（`onDelivered`）、报 `gift_payment_succeeded`；用户点「Lanjut」回到入口页后再拉升级庆祝（不叠在送达页上）。
- EXP：PawCoin 取响应 `senderExp`；QRIS 到账取目录 `senderExp × 数量`。

- Code review（独立复审）处置：
  - CONFIRMED #1 兜底从进页起算，图下载 + 解码最多 3 秒会把 5 秒动画截短 → 真 Rive 时兜底改为「动画就绪开始播」起算，另设进页起 10 秒硬上限防卡住；注入替身视为进页即就绪。已修。
  - PLAUSIBLE #2 换图未统一尺寸 / 下载原图 → 改取 OSS 1000 宽缩放版；非方图在槽位中的形变需设计师确认槽位是否为 layout 节点（L2 验收「不同尺寸礼物图」）。
  - CONFIRMED #3 `RenderImage` / 图片属性句柄未释放、跳过后仍继续解码 → 交出后立即 dispose（资源与 VM 各自持有引用），下载后先判 `mounted`。已修。
  - #4 QRIS 到账 EXP 用「目录单件 senderExp × 数量」与服务端「总额取整」可能差 1：story 指定该口径，**待产品 / 后端确认**（可改为轮询单笔订单时带回 senderExp）。
  - #5 补测 `sendDone` 事件；切后台补判逻辑依赖平台生命周期，L2 验收。
  - #6 `gift_send_animation.dart` 已无调用方，保留不删（AC4 允许保留）。

### File List

- `petgo_app/assets/rive/send_gift.riv`、`assets/rive/receive_gift.riv`、`assets/gift/gift_box_closed.webp`（新）；`pubspec.yaml`（改）
- `petgo_app/lib/features/gift/presentation/gift_send_ceremony.dart`（新）
- `petgo_app/lib/features/gift/presentation/gift_send_flow.dart`、`gift_wrap_page.dart`（改）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（改）
- `petgo_app/test/gift/gift_send_ceremony_test.dart`（新）、`gift_send_flow_test.dart`（改）
