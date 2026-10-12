# V1.3.4 follow 主题决策日志

> 本主题拆 epic / story 过程中的产品决定。**与 PRD / UI 稿冲突时以本文件为准。**
> 功能分支 `feat/1.3.4-follow-notify`（自 `dev_1.3.4` 拉出）。
> 输入文档：`PRD-v1.3.4-follow.md`（原名 `1-3-4关注与通知prd.md`，FR-140 / FR-141）· `ui-v1.3.4-follow.html`（原名 `ui-1-3-4-follow-notify-v1.html`，定稿不拆页）。

## 2026-10-12 · 拆 epic 前拍板（操作者 Hex）

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-1 | `ck_notifications_type` 与 app 主题（FR-138 `OPS_ANNOUNCEMENT`）分属两个分支 | **两个分支的重建都列全集 34 值**（现有 31 + `OPS_ANNOUNCEMENT` + `USER_FOLLOWED` + `PLACE_COMMENTED`），先后合并、先后执行都得到完整全集；`NotificationType` 枚举两分支都在末尾按同一顺序追加这三个值（改动逐字相同、合并不冲突；`MentionNotifyTest` 校验「枚举 = 约束」）；app 主题 AD-17 / Story 6-1 已同步（其决策日志 D-26，2026-10-12） | PRD §3.2「合并到同一个迁移」的落地方式 |
| D-2 | 关注相关新接口的用户标识 | **新接口改用不可枚举的用户 token**（`users` 新增 `public_token`）；现有他人主页 / 拉黑 / 作者字段仍是数字 id，本版本不改造。关注列表行同时带 `userId`（跳转现有主页路由用）与 `userToken`（关注操作用） | 架构护栏「对外标识一律 token」的新增接口部分 |
| D-3 | 计数缩写 | **≥ 1.000 即缩写**：1.234 → id「1,2K」/ en「1.2K」；≥ 1.000.000 用 M；最多一位小数、去掉末尾 0（1.000 → 1K）；< 1.000 显示原数。用于主页四格数据 | PRD「≥10,000 显示 1.2K 式」 |
| D-4 | B 拉黑 A 后，A 能否关注 B | **能，照常成功**：不暴露 A 被拉黑；B 的粉丝数 +1，但 B 不收到这条新粉丝通知（拉黑过滤） | 补 PRD §3.1「拉黑与关注」只写了拉黑方不能关注 |
| D-5 | 我的页四格「Postingan」 | **数我的全部帖子**（含私密 / 仅自己可见），与下方网格一致；同理「Suka」取我全部帖子的获赞数。他人主页（含别人看我）仍只数公开帖子 | PRD §3.1 未区分 |
| D-6 | 关注埋点命名 | 改为过去式：`user_followed` / `user_unfollowed`（属性不变）；`follow_list_viewed`、`suggested_follow_shown`、`notification_follow_back_tapped` 不变 | PRD §4 `user_follow` / `user_unfollow` |
| D-7 | 场所评论推送频率 | **同点赞去抖**：同一场所 10 分钟内最多推一条，文案取推送时刻的聚合结果 | 补 PRD §3.2 只写了点赞去抖 |
