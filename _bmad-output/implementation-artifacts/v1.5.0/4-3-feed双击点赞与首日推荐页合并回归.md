# Story 4.3: Feed 双击点赞与首日推荐页合并回归（App · 对齐分支）

Status: ready-for-dev

> 版本 v1.5.0 · 主题 likes（会话 ⑤）
> 🔴 **不在云端会话里做**。执行条件：③ 的 `feat/1.5.0-onboarding`（首日与新手任务）已合进 `dev_1.5.0`，且本主题 Story 3.1 / 4.1 / 4.2 已在 `feat/1.5.0-likes` 完成。
> 🔴 **分支**：从最新 `dev_1.5.0` 切 `feat/1.5.0-likes-align`，把 `feat/1.5.0-likes` 合进来，在对齐分支上改；**不反向合并 dev 进 `feat/1.5.0-likes`**（CROSS-STORY「2026-11-14 V1.5.0 六会话共用地基」末条）。
> 口径优先级：`decision-log.md` > PRD B §6（合并顺序、回归范围）> 首日与新手任务 PRD FR-173 / FR-174 / FR-175 > `architecture-v1.5.0-likes-delta.md`（F-1 ~ F-3、G-6）。
> 范围层：只 App（若合并发现后端冲突，按冲突处理，不在本 story 加后端功能）。

## Story

As a 首日新用户,
I want 推荐页宠物卡上的点赞和 Feed、详情页的点赞状态一致,
so that 我在引导里点过的赞，进 App 后看到的也是已赞。

## Acceptance Criteria

**AC1 — 先查** `[L0]`
在对齐分支上 `grep -rn "LikeButton(" petgo_app/lib`，列出 ③ 新增的所有挂载点（预期在首日推荐页宠物卡、游客 Diary 展示页「真实宠物」区，见首日 PRD FR-173 / FR-175）。结论写进 Completion Notes：复用了 `LikeButton` / 自写了点赞 / 两者都有。
（2026-11-14 规划时已知：③ 的 `1-2-首日推荐页与引导出口.md` 在 `RecommendedPetCard` 大图右上角点赞胶囊上复用 `LikeButton`，绑定 `coverPostId` / `coverLikeCount`，「只用公开参数、不 fork」；`1-3-首日游客展示页真实宠物区.md` 要把 `entry_source = 'guest_real_pets'` 透传给卡内 `LikeButton`——**`LikeButton` 现在没有 entrySource 参数**，③ 很可能会加一个。合并时保留 ③ 加的参数，并让 3.1 改造后的 `_toggle` 把它传给 `requireLogin`。）

**AC2 — 复用了 `LikeButton`** `[L0]`
- 给这些挂载点的快照（推荐卡数据模型，含 `postId / liked / likeCount`）加 `likeEpoch` 并在发请求前打戳（同 3.1 AC2）；挂载处传 `snapshotEpoch`。
- 埋点 `source` 沿用 ③ 定的值；`trigger: 'button'` 由 `LikeButton` 自动带上。
- 推荐卡**不加**图片双击（PRD B「本文不含」第 6 条：那里图片本身就是点赞入口）。
- 推荐卡点赞后进 Feed / 详情同帖显示已赞；反之亦然。

**AC3 — 自写了点赞** `[L0]`
改为调 `postLikeStoreProvider.toggle`（登录门控仍在 Widget 层，沿用 ③ 的 `entrySource`），保证三处共享同一份状态。改动最小化，不改 ③ 的 UI 与文案。

**AC4 — 新手任务不受影响** `[L1]` `[L2]`
③ 新手任务 ④「点赞」（按账号记录判定，FR-176）在双击点赞、推荐页点赞、爱心点赞三种入口下都会完成（同一个点赞接口）；② 获赞在收到自动点赞时完成（PRD A A-AC42）。

**AC5 — 回归** `[L0]` `[L2]`（PRD B §6 回归范围）
首页「发现」「关注」、话题页、详情页底栏点赞、点赞人列表、长按举报、多图轮播、顶置卡、**首日推荐页点赞**；`flutter analyze` + 全量 `flutter test` 绿；模拟器（Android）逐项点一遍。

## Tasks / Subtasks

- [ ] T0 确认 ③ 已合 dev；从 dev 切 `feat/1.5.0-likes-align`；合入 `feat/1.5.0-likes`，解决冲突
- [ ] T1 AC1 清点
- [ ] T2 AC2 或 AC3
- [ ] T3 回归（AC4、AC5）；结果写 Completion Notes；合并 / push / 部署仍等用户明确指令

## Dev Notes
- 冲突高发点：`like_button.dart`（3.1 改造 vs ③ 可能加参数）、`feed_item.dart`（`likeEpoch` vs ③ 可能加字段）、`app.dart` `resetUserScopedCaches`（双方都可能登记 provider，取并集）、`push_service.dart` 的 `kPushDirectTypes`（本主题不改，若冲突取并集）。
- 判定冲突方向看文件树与 diff，不看提交标题。

## Dev Agent Record

### Completion Notes
（待填）

### File List
（待填）
