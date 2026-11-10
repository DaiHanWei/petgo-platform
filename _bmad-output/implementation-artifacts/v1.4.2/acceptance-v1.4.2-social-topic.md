# V1.4.2 social-topic 本地验收记录（2026-11-05）

验收对象：`dev_1.4.2` @ `92aca778`（PR #48 合入后）。环境全部在本机：后端另开工作目录打包、连全新空库 `petgo_l2_142`（推送 / IM / AI 均为桩）；App 为 Android 模拟器调试包连本地后端；后台为本机浏览器。未使用 stag，未动生产。

## L0

| 项 | 结果 |
|---|---|
| `flutter analyze` | 零问题 |
| `flutter test`（合并后） | 4488 全过 |

## L1

| 项 | 结果 |
|---|---|
| 全量 `mvn -B clean verify`（新库 `petgo_l1_142`） | 7754 例，3 errors，**均非本线**：① `AdminPublishIdentityDrawerIntegrationTest` / `AdminVirtualDisplayLevelIntegrationTest` —— `SEQ = System.nanoTime()` 在开机久的 macOS 上位数过长，昵称超 20 字（CI 机器上过；文件最后改动在 1.4.0）；② `HealthRecordEndpointIntegrationTest.profileDeletionCascadeHardDeletesRecords` —— 异步健康里程碑完成记录与紧随其后的删档竞态，单跑通过 |
| 本线新增集成测试（PetFollow* / PetDiaryNotify* / FollowingFeed* / AccountMilestone* / Topic* / MentionScope* / AdminTopic* / AdminTopicPin* / AdminOpsNotify* / OpsRecipientVariables*） | 全过 |
| 新迁移 `ddl-auto=validate` | 空库从头迁移到底通过 |
| 1-3 关注流 EXPLAIN | 首页 / 深翻页均走 `idx_content_posts_feed`，执行约 2 ms（结果已写入 story 1-3） |
| 本地 flyway `20261104.2060` 记录 | 本机所有库均无 `20261104*` 记录，无需删除 |

## L2 还原度（对照两份 UI 定稿逐屏，标准 ≥ 95）

### App

| 屏 | 分 | 备注 |
|---|---|---|
| A1 / A2 | 97 | 追更按钮两态、Pengikut 计数 ±1 |
| A3 | 96 | 断网 → 顶部「Gagal mengikuti」+ 回滚；超频另有底部提示（story 设计） |
| A4 | 97 | 信息卡第 4 格 Pengikut |
| A5 / A6 / A7 | 96–98 | 胶囊计数、副标题格式、取消后行保留 + 浅紫 Ikuti |
| A8 | 96 | 方卡 88 + 头像 + 天数 + Ikuti |
| A9 / A10 / A11 | 96–98 | 他人列表无按钮；追更宠物未关注主人 → + Ikuti |
| A12 | 97 | 两类新通知文案加粗；落点：帖子详情 / 追更者主页 |
| A13 | 98 | `PET_DIARY` 开关 PATCH 落库已验 |
| A14 | 93 → **96（已修）** | 失效图标改为放大镜 + 实心圆 ×（`GoneIcon`） |
| A15 | 97 | 断网失败 → Coba lagi 恢复 |
| B1 / B2 / B3 / B4 | 96–97 | 单宠 / 多宠庆祝页；Level S 20 项；通知不带宠物名（R-4）。账号级里程碑不即时弹、进里程碑列表补弹（story 2-3 AC3/AC5） |
| C1 | 96 | 首页话题行 + Resmi |
| C2 / C3 / C4 | 内容一致 | 联想行、新话题行、第 6 个话题不高亮；**面板停靠键盘上方需真机**（模拟器接实体键盘，Gboard 仅悬浮） |
| C5 | 97 | 正文话题紫色高亮 |
| C7 / C8 | 96 | 官方 / 用户话题、Terbaru / Populer、顶置帖 Disematkan |
| C9 | 93 → **96（已修）** | 「Topik populer lainnya」箭头未贴右（`Flexible` + `Spacer` 平分空余）→ 改 `Expanded` 包名字 + 标记 |
| C10 | 93 → **96（已修）** | 同 A14 图标 |
| C11 | 97 | |
| C6 | — | 本地请求太快未截到骨架屏，以 L0 测试为准 |
| D1 / D2 / D4 / D5 | 96 | 新按钮组、加载态、宠物级 / 账号级预填句式（单宠用宠物名，≥2 宠用 Kami） |
| D3 | — | 「Gagal membuat kartu」需本地画卡失败才出现，以 L0 为准；上传失败走发帖页原有「Coba lagi」 |
| D6 / D7 | 待真机 | 卡图仅在内存生成，需真机导出原图核对；花结为兜底样式（待素材） |
| E1 / E3 / E4 | 内容一致 | 快捷列表 / 查询结果 / 无结果；停靠同 C2 需真机 |
| E2 / E5 / E6 | — | 以 L0 为准 |

### 后台（中文 + EN / ID 三语扫描无漏翻、无裸 key）

| 屏 | 分 | 备注 |
|---|---|---|
| F1 / F8 | 95 | 第 5 张统计卡「停用」vs 稿「已禁用」、按钮「停用」vs「禁用」、「恢复」vs「恢复为正常」—— PRD 文案表本身写「复用 admin.common.disable / admin.content.restore」，复用值即「停用 / 恢复」，属 PRD 自相矛盾，按复用执行；另多一个「搜索」按钮、关闭态不显示「—」序号框 |
| F2 / F3 | 96 | 重名升级：黄色提示 + 原生 confirm，文案与 PRD 逐字一致 |
| F4 / F5 / F7 | 92 → **95（已修）** | 抽屉头部摘要两列网格、创建时间折行 → 改单列「标签 → 值」 |
| F6 | 96 | 禁用 confirm 文案与 PRD 一致 |
| F9 | 97 | |
| G1 / G1a / G1b / G2 | 95 | 选帖只列该话题；`slot = TOPIC:{id}`；App 话题页顶置生效 |
| I1–I6 | 96 | 变量标签插入（真实点击位置正确）、手打变量拦截、超长拦截、随机真人预览 + 换一个、0 人拦截、发送记录「跳过 N（没有宠物）」；收件人通知变量替换正确 |

## 待真机 / 待素材（不在本次本地可验范围）

1. 1-2 / 1-5：`PET_FOLLOWED` / `PET_DIARY_POSTED` 系统推送到达与点击落点、关闭开关后不推。
2. 4-4 / 5-2：`#` / `@` 面板在软键盘停靠时贴键盘上方。
3. 3-1：里程碑卡导出原图（D6 / D7）。
4. 素材：`first_friend` / `first_save` 徽章、里程碑卡花结 SVG（2-3 / 3-1 最终还原度）。

## 观察项（不阻塞）

- A8 推荐卡：本会话内在别处取消追更后，卡片仍显示本会话覆盖态，重启后正确（推荐卡未做 epoch 比对）。
- C4：「Maksimal 5 topik per postingan」提示本次未截到，待真机复核出现时机。
- 首次在庆祝页上生成卡图时缩略图里疑似混入一片彩纸，第二次生成无此现象；真机导出原图时一并核对。
