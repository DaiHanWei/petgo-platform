# Story 2.5: App 送礼拆礼物动画与批量拆（G1 ~ G6）

Status: ready-for-dev

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-02 收礼通知直接拆；11-13 设计师新稿 + riv 实测）> PRD A §3.4 / §3.8（FR-190）> 架构 D-7、D-9、D-14 > App 稿 G 泳道、F 泳道主路径注释。
> 前置：1-4（open 接口）、2-3（rive 文件与礼盒素材已入库）、2-4。
> 范围层：**只 App**。

## Story

As a 宠物主人,
I want 点开礼盒、看礼物升起和祝福语，多份礼物也能一次拆完,
so that 收礼是一次惊喜。

## Acceptance Criteria

### AC1 — 单个拆 G1 → G2 / G3 `[L0]` `[L2]`
1. 新页 `A/features/gift/presentation/gift_reveal_page.dart`：浅色客厅插画背景 + `assets/rive/receive_gift.riv`（画板 `GiftReveal`、状态机 `GiftSM`，`Fit.cover`）。待机：礼盒轻晃（G1）；左上白色圆形关闭（不拆，回来源页）；底部「Ketuk untuk membuka」（新文案）。
2. 点礼盒 → App 触发 `GiftVM.open`（**只触发一次**：首次点击后置标志，连点忽略；文件里的 `TapBox` 若也会触发 open，以先到为准、不重复调接口）→ 同时调 `POST /pets/{petId}/gift-orders/open {orderTokens: [token]}`（接口失败：动画照播，结束后显示 toast 通用错误并停在 G2 文字区，返回时列表刷新以服务端为准）。
3. 礼物图：设置 `GiftVM.giftImage`（现文件没有该属性，传了不生效、显示小熊属预期，Completion Notes 记一句）。
4. 结束：Rive 事件 `revealDone` 或 2.5 秒兜底 → 淡入：顶部送礼人头像 +「{nama} mengirim {n} {hadiah} untuk {petName}」（新文案）；礼物下方祝福语（`message`；`messageHidden` 时显示「Ucapan ini disembunyikan karena melanggar pedoman komunitas.」）；「Kamu dapat {n} EXP」（`ownerExp`，0 不显示，复用 2-3 的 key）；紫色主按钮「Lanjut」（复用 `onboardingContinue`）；紫字「Lihat profil {nama}」（新文案，送礼人已注销时不显示）→ `/users/{userId}`。
5. 右上「⋯」→「Laporkan ucapan」→ 2-6 的举报抽屉（只在 `canReport = true` 时出现；G3 无祝福语时既无祝福语区也无「⋯」，不留空白）。
6. 减弱动态效果 / Rive 加载失败 → 直接显示 G2 终态（仍要调 open 接口）。

### AC2 — 批量拆 G4 ~ G6 `[L0]` `[L2]`
1. 礼物页「Buka Semua」→ 同一个礼盒动画，点开后调 `open {all: true}`。
2. 汇总 G4：画面压淡；「Kamu menerima {n} hadiah」（新文案，`n` = 笔数）；每格：礼物图 + 右下送礼人头像 +「×N 礼物名」+ 灰字「dari 谁」；「Kamu dapat {n} EXP」= `totalOwnerExp`。
3. 每页 9 格，超过 9 份分页（G5，圆点可左右滑）。
4. 有祝福语（`message != null`）的笔数 > 0 时按钮「Berikutnya」（新文案）→ G6：同 G2 版式逐条轮播**只带祝福语的那几笔**，圆点为进度，最后一条按钮变「Lanjut」；都没有祝福语则汇总页直接「Lanjut」。
5. 「Lanjut」回礼物页并刷新（未拆筛选随之清空）。

### AC3 — 收礼通知点开 `[L0]`（D-9）
1. `A/core/router/deep_link_routes.dart` 的 `PET_GIFT_RECEIVED`：`targetRef` 是数字 → `/pets/$id/gifts?open=latest`（不再拼 `wall=expanded`）；非数字照旧落通知中心。
2. `notification_center_page.dart` 里 `PET_GIFT_RECEIVED` 的特殊处理（`location.startsWith('/pets/')`）同步改为新 query。
3. `GiftPage` 收到 `open=latest`：主人视角 → 拉 `filter=UNOPENED` 第一条，有 → push G1；没有 → 拉 `ALL` 第一条 → 打开 H1 详情（2-6）；非主人（理论上不会）→ 只显示礼物页。只执行一次（返回礼物页不再自动弹）。
4. 单测：深链映射；`open=latest` 两分支（有未拆 / 无未拆）。

### AC4 — 文案 `[L0]`
新增：`Ketuk untuk membuka` / `Tap to open`、`{nama} mengirim {n} {hadiah} untuk {petName}` / `{name} sent {n} {gift} to {petName}`、`Lihat profil {nama}` / `View {name}'s profile`、`Kamu menerima {n} hadiah` / `You received {n} gifts`、`Berikutnya` / `Next`、`Ucapan ini disembunyikan karena melanggar pedoman komunitas.` / `This message is hidden because it violates the community guidelines.`（与 2-6 共用）。

### AC5 — 测试 `[L0]`
Widget：连点礼盒只发一次 open 请求；无祝福语不渲染「⋯」；批量 1 / 9 / 10 份的分页数；只有部分有祝福语时 G6 只轮播那几笔；`disableAnimations` 直接终态。

## Tasks / Subtasks

- [ ] 单个拆页面与 Rive 接入（AC1）
- [ ] 批量拆汇总 / 分页 / 逐条（AC2）
- [ ] 通知深链与 `open=latest`（AC3）
- [ ] ARB + 测试（AC4、AC5）

## Dev Notes

- 接法同 2-3（照 `stamp_ceremony.dart`）。设计师待补：`GiftVM.giftImage`、`revealDone`、`TapBox` 说明——均不阻塞，走兜底。
- 拆礼物**不触发任何成长值入账**（服务端保证）；App 只显示接口给的 `ownerExp`，不自算。
- QA 场景（PRD A §3.8）：连点礼盒、批量 1 / 9 / >9、加载慢 / 失败、减弱动态效果、切后台——L2 本地。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/ test/core/router/`
- L2（本地）：G1 ~ G6 还原度 ≥ 95%；通知点开直接拆

## Dev Agent Record

### Completion Notes

### File List
