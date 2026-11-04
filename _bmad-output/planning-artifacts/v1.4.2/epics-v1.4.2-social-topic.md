---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
status: complete
updated: '2026-11-04'
inputDocuments:
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/1-4-2追更宠物·里程碑·话题prd.md
  - _bmad-output/planning-artifacts/v1.4.2/architecture-v1.4.2-social-topic-delta.md
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-1-4-2-social-topic-integrated-v1.html
  - _bmad-output/planning-artifacts/v1.4.2/追更话题/ui-admin-1-4-2-social-topic-integrated-v1.html
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
version: v1.4.2
theme: social-topic
---

# petgo-platform V1.4.2 social-topic - Epic Breakdown

## Overview

V1.4.2「追更宠物 · 新增 S 级里程碑 · 内容话题」PRD 拆分（FR-153 ~ 157、FR-69、FR-165、AB-10A 扩展 / AB-10B / AB-37）。分支 `feat/1.4.2-social-topic`（从 `dev_1.4.2` 切出；PRD §6：全部需求同一分支）。

> 🔴 **口径优先级**：`决策日志-social-topic.md`（2026-11-04 就绪度评审拍板 R-1 ~ R-5）> PRD（变更记录日期最晚的一条为准）> `architecture-v1.4.2-social-topic-delta.md`（D-1 ~ D-23、P-1 ~ P-12、G-1 ~ G-7）> UI 稿（定稿）。

## Requirements Inventory

### Functional Requirements

**FR-153 追更宠物**
- **FR-153.1** 关注单只宠物，与关注人两套独立关系；不能追自己的宠物；只读宠物可被追；无需主人同意、主人不能移除；与关注人共用每分钟 30 / 每日 500 额度。
- **FR-153.2** 站内宠物访客页整宽 Ikuti / Mengikuti 按钮（再点即取消、无确认、失败回弹 + 顶部轻提示）；统计条第 4 格「N Pengikut」（≥10,000 显示 1.2K）；访客页页头合并为一行；主人 Diary Tab 显示人数不显示名单；名片 H5 / Feed 卡 / 帖子详情无追更按钮。
- **FR-153.3** 关注流 = 关注的人的帖子 ∪ 追更宠物的公开 Diary，按帖去重、时间倒序；关注与追更都为 0 显示现有空态，否则无内容时显示「暂无新动态」+ 推荐关注用户 5 人 +「Jelajahi Discover ›」。
- **FR-153.4** 通知 `PET_FOLLOWED`（每人一条不合并、24 小时内同人同宠不重复、无回关按钮、点击进追更者主页、取消不通知）；`PET_DIARY_POSTED`（同一追更者同一宠物每 WIB 日最多 1 条、已关注主人照常收、多宠只发 1 条取第一只、点击进帖子）；新增第 10 个推送分类「追更宠物」放 INTERAKSI 组「Pengikut baru」后、默认开可关；`PET_FOLLOWED` 归「新粉丝」，其说明改两行。
- **FR-153.5** 关注列表 Following Tab 加 Pengguna N / Hewan N 筛选（默认 Pengguna、无「全部」）；宠物行（头像 + 名 + 物种 +「milik @主人」，点行进访客页）；自己的列表常驻 Mengikuti、取消后行保留刷新才移除；别人的列表行内无按钮；自己的 Hewan 为空时放推荐宠物（带 Ikuti）、推荐空 / 失败整块隐藏；别人的为空只显示空态；四格 Mengikuti 数只计用户；Followers Tab 不变。
- **FR-153.6** 宠物推荐接口排除已追更的宠物；排序改用近 14 天点赞（首页横滑行与集合页同步生效）。
- **FR-153.7** 拉黑：A 拉黑 B → A 对 B 所有宠物的追更同步取消、拉黑期间 A 不能追；B 对 A 宠物的追更保留、可新追，但看不到 A 宠物 Diary、收不到 `PET_DIARY_POSTED`，A 也不收 B 的 `PET_FOLLOWED`；举报隐藏不影响追更。宠物删除 / 主人注销 → 追更一并删除不通知；主人或追更者封号 → 关系保留、访客页按不存在、仍可取消、期间不发通知，解封恢复。
- **FR-153.8** 宠物访客页失效修复：补返回箭头；服务端不存在 →「Konten ini sudah tidak ada」无重试；断网 / 超时 →「Gagal memuat, silakan coba lagi」+「Coba lagi」。

**FR-154 新增两个 S 级里程碑**
- **FR-154.1** 三份物种清单各加两条：「Teman pertama」= C-S19 / D-S19 / G-S12（第一次关注一个人，只计 USER 来源，追更宠物不算）、「Simpan pertama」= C-S20 / D-S20 / G-S13（第一次收藏帖子，场所不算，收藏自己的帖子也算）；取关 / 取消收藏不撤销；访客视图分母猫狗 33→35、其他 18→20。
- **FR-154.2** 账号级完成：账号下全部 ACTIVE 宠物同时完成（只读不完成）；完成当下不弹庆祝、通知中心只发 1 条 `MILESTONE_SM_NODE`（不推送、不写宠物名）；点通知或下次进里程碑列表由补庆祝弹一次（沿用「只弹最高级一条」）；「已庆祝」按账号记；多宠庆祝页显示全部 ACTIVE 宠物头像与名字、不显示 KOLEKSI；成长值每账号每编码只 +5 一次。
- **FR-154.3** 之后新建的宠物、从只读恢复的宠物：账号已做过 → 直接已完成且已庆祝，无庆祝、无成长值、无通知；无宠物账号先记下「做过」。
- **FR-154.4** 存量补算：上线前做过的账号给全部 ACTIVE 宠物标完成、记为已庆祝、不通知；无宠物账号同样记下；成长值照发 `LAUNCH_BACKFILL`（每编码每账号 +5），不弹升级庆祝。

**FR-69 内容话题**
- **FR-69.1** 话题名规则：字母（任何语言）/ 数字 / 下划线，遇其他字符结束；2–30 字符不含 `#`；纯数字不算；不区分大小写（显示用首次写法，官方以后台为准）；同帖重复只算 1 个；单帖最多 5 个，超出为普通文字；话题名随正文走敏感词。
- **FR-69.2** 发布：Moment / Diary / Tips 都可带话题；正文输入 `#` 弹联想（官方在前、热门用户话题在后，无匹配只剩「#名字 · Topik baru」一行），无搜索框、面板贴键盘上沿（最高 220）、光标行保持可见；已有 5 个再输入 `#` 不弹联想、屏幕顶部提示「Maksimal 5 topik per postingan」；输入时前 5 个话题与点选的 @ 高亮（评论框 @ 同样高亮）；无「# Topik」按钮；话题发布时确定、不可改；运营后台发帖同样支持。
- **FR-69.3** 展示：正文 `#话题` 紫色可点进话题页（Feed 卡片与帖子详情；评论区不解析）；隐藏话题照常可点，禁用话题为普通文字；正文按作者原样显示。
- **FR-69.4** 话题详情页：话题名；官方显示「Resmi」+ 说明（印尼语 / 英语）；无封面；不显示帖子数；顶部 1 个顶置坑位（帖子或推广卡，按时间自动上下线）；Terbaru / Populer（累计点赞 + 评论 × `comment_weight`）；只列已发布 `PUBLIC`，过滤同现有内容列表；限流内容照常出现；游客可看、互动拉登录；无分享；空态（图标 + 标题 + 副文 +「Buat Postingan」预填「#话题名 」+ 推荐话题 10 个）；禁用 / 不存在显示「话题不存在」。
- **FR-69.5** Social 首页 Discover 页签分类标签与顶置卡之间一行横滑话题（文字链 + Resmi 徽章，最多 5 个，运营指定在前、按热度补齐，不足 1 个整行隐藏，随 Feed 滚动不吸顶，Following 页签不显示）。
- **FR-69.6** 热度 = 近 7 天新发帖数 × 1 + 近 7 天浏览人数之和 × 0.1 + 近 7 天发生的点赞与评论 × 1。
- **FR-69.7** 发帖事件带 `topicIds`（供 1.5.0 活动引擎）。

**AB-10B 话题管理（后台）**
- **AB-10B.1** 「✍️ 内容」组「内容标签」后新增「话题」：列表（话题名 / 类型 / 状态 / 帖子数 / 近 7 天热度 / 首页话题行开关 + 序号 / 创建时间 / 操作）、按类型 / 状态筛选、按名称搜索、摘要条 5 格、默认按近 7 天热度倒序、筛选无结果空态。
- **AB-10B.2** 新建官方话题（右侧抽屉：名称 + 说明 ID / EN 选填），与用户话题重名 → 黄条 + 确认 → 升级为官方、帖子保留、不可降回；编辑：说明随时可改，显示写法只能改大小写。
- **AB-10B.3** 首页话题行：开关 + 序号，最多 5 个（第 6 个报错）；打开排到最后、改序号互换、关闭后面前移；隐藏 / 禁用自动移出且后面前移；隐藏 / 禁用话题不能放入。
- **AB-10B.4** 隐藏（直接生效）/ 禁用（原生 confirm）/ 恢复（隐藏恢复无确认，禁用恢复需确认）；禁用：话题页不可访问、正文不可点、同名不建关联；恢复后禁用前帖子回来、禁用期间同名不补。
- **AB-10B.5** 编辑抽屉「话题页顶置」区块（官方 / 用户 / 隐藏话题有，禁用无）：当前生效 1 条 + 排期中最多 3 条、「＋ 新建顶置」（坑位与话题预选）、「查看全部排期 →」。

**AB-10A 话题页坑位（后台扩展）**
- **AB-10A.1** 顶置管理加「首页 Feed｜话题页」页签；话题页页签列正常与隐藏话题（话题名 / 状态 / 当前生效 / 排期条数 / 热度），点行进该话题排期页（面包屑「顶置管理 / 话题页 / #话题」），新建配置同 Feed 坑位；内容顶置只能选该话题下已发布公开帖子；话题下拉不列禁用话题；话题禁用立即结束其生效排期（后台显示「已结束」），恢复不自动恢复。

**FR-155 帖子浏览按天数据**
- **FR-155.1** 每次记浏览同时记「帖子 × 观看者 × WIB 日期」（同一人同一天只一条）；采集方式与累计口径、后台两列不变；App 不显示；不回填；定期清理（保留 30 天）。

**FR-156 里程碑发社区**
- **FR-156.1** 「Bagikan ke Komunitas」→ 加载态 → 生成卡图 → 关庆祝页 → 打开发布页，预填 Diary 类型、这只宠物（账号级里程碑预选全部 ACTIVE 宠物）、里程碑卡图 1 张、庆祝文案（不带 #话题）；发布页取消回原页不再弹庆祝；适用全部里程碑（含陪伴 100 / 365 天）。
- **FR-156.2** 里程碑卡 1:1：奖章花结（徽章 + 级别色环 + 飘带）+ 级别芯片 + 短标题 + 宠物头像与名字 · 完成日期 + TailTopia 字标；品牌紫渐变底、白页脚；M 级飘带 #6C48AE + 白描边；账号级显示 1~5 只宠物；无二维码、无水印。
- **FR-156.3** 次入口「Bagikan ke luar」（H5 链接 + 系统分享面板）；按钮组吸底（所有级别），上沿渐隐；失败提示抬到按钮组上方。
- **FR-156.4** 缺陷修复：点击必有加载态、不可连点；依赖在弹出前取好，6 处弹出点统一修；卡图失败轻提示、不打开发布页、庆祝页不关可重试；站外分享生成链接失败退化为只分享文案，异常不吞分享面板。

**FR-157 @ 提及范围扩大**
- **FR-157.1** 范围 = 我关注的人 + 关注我的人 + 最近互动过的人（官方号不特殊；先关注再 @）；快捷列表先放我关注的人（按关注时间倒序），不足 30 用互动候选补齐不重复；拉黑（任一方向）/ 已注销排除；发帖与评论同一套；每条最多 5 人。
- **FR-157.2** 输入昵称在三类范围内包含匹配（不区分大小写）最多 20 条，按「关注的 → 关注我的 → 互动过的」排序、同人一次；`@` 后至少 1 字才查、停 300ms、丢弃旧结果；去掉「Cari nama…」过滤框；发布页面板挪到键盘上沿，评论框只去过滤框；加载 / 无结果 / 失败 / 空态在面板内；空态引导先去关注。
- **FR-157.3** 服务端按「此刻是否在三类范围」校验，范围外静默丢弃；之后取关不影响已发出的 @。

**AB-37 运营系统通知昵称变量**
- **AB-37.1** 新建通知第 ① 步标题 / 正文（EN / ID）下「+ 用户昵称」「+ 宠物昵称」按钮在光标处插入整体标签（整体删除、不可改字）；只能按钮插入，手打同名文字阻止提交并提示；同一变量可多次出现。
- **AB-37.2** 用户昵称 = `users.nickname`，空则 ID「Sobat」/ EN「there」；宠物昵称：按物种筛选时取该物种最早建档（未锁定优先），否则取最早建档的 ACTIVE 宠物（全只读则最早只读）；按发送时刻取值。
- **AB-37.3** 用了宠物昵称（任一语言）→ 没有宠物的用户排除；第 ② 步与第 ④ 步预计触达显示净人数并注明排除数；1 万人门槛按净人数；净人数 0 → 第 ④ 步拦截；发送记录「跳过 N（没有宠物）」（新列 `skipped_no_pet_count`，R-3；原 `skipped_count` 口径不变），「触达人数」列加宽。
- **AB-37.4** 长度：每个变量按 20 字计（标题 120 / 正文 255）；第 ① 步实时示例（hex / tuotuo）；第 ④ 步从本次人群随机 1 人真实替换 +「换一个」；发送记录摘要显示变量原样；日志不记收件人与正文。

**FR-165 暂时隐藏入口**
- **FR-165.1** 隐藏 Social 首页「Tempat Ramah Hewan」入口行、Kenali Hewanmu 的「Jejak Anabul」与「Boarding Pass」卡（其余卡上移无空位）；代码、接口、数据、后台场所管理全保留；其他进入路径不拦截；App 常量控制。

**FR-AN 埋点**（PRD §4）：`pet_follow` / `pet_unfollow`、关注流 `reason`；`topic_inserted` / `topic_page_viewed` / `topic_sort_switched` / `home_topic_row_tapped`；`brag_post_tapped` 新 source `milestone_celebration` + 发帖成功区分来源 + `milestone_share_failed`；`mention_inserted` 加 `relation` / `from_quick_list`。

### NonFunctional Requirements

- **NFR1 可见性一致**：拉黑 / 举报 / 注销 / `DEACTIVATED` 在关注流、话题页、@ 候选、追更通知四处口径一致（P-2）。
- **NFR2 隐私**：不提供追更者名单；拉黑对被拉黑方无感知；话题对外只暴露标准名；通知变量替换后的文字不进日志。
- **NFR3 性能**：关注流并集查询 L1 跑 `EXPLAIN`（过慢改两段 UNION）；热度每小时集合 SQL；按天浏览表与追更日志定期清理。
- **NFR4 护栏**：时间戳迁移；`ck_notifications_type` DROP + ADD 全集（与电商线交界由后合并方负责）；无新依赖 / MQ / 缓存；写库监听 `REQUIRES_NEW`；后台写操作审计。
- **NFR5 本地化**：App 新文案写 `app_id.arb` / `app_en.arb`（PRD §7）；后台三语写 `messages_{zh_CN,en,id}.properties`（PRD §7 后台文案表，复用词条引原 key）。
- **NFR6 前后端规则一致**：话题名解析前后端共用测试样例（P-1）。
- **NFR7 兼容**：接口只加字段不改语义；旧版 App 收到新通知类型走现有兜底文案。
- **NFR8 测试**：后端新增 / 改动查询与 service 单测（空集、被拉黑、已注销 / 封号、翻页游标）；App 新组件 widget 测试覆盖稿中每种状态；现有测试全绿；含 UI 的分支验收做还原度检测（≥95%）。

### Additional Requirements（架构）

- 8 个迁移按 P-8 拆分（排在 `V20261030_0330` 之后），其中 ⑦ 通知类型与推送分类约束重建须与两个守卫测试同 story 修改；⑧ 为 AB-37 跳过计数新列（7.1）。
- 新模块 `com.tailtopia.topic`、`features/topic`、`shared/rich_text`（P-4）；共用解析器 + `TopicMentionTextController`，不引第三方库（§2.1）。
- 话题对外用标准名 `/topics/{name}`；话题页坑位 `content_pins.slot = 'TOPIC:{topicId}'`（D-6）。
- 账号级里程碑走新事件 `AccountMilestoneCompletedEvent`，不复用按宠物事件；成长值幂等键 `MILESTONE_ACCT:{userId}:{flagKey}`（D-12、P-7）。
- 庆祝页依赖改为弹出时取 `ProviderContainer`；里程碑卡屏外挂载、等图画上屏再截（D-21）。
- 外部依赖：新徽章 `first_friend` / `first_save` 与花结 SVG 正式素材（未到用 `MilestoneBadge` 兜底）。

### UX Design Requirements

UI 定稿（逐屏说明即实现说明；数值照说明取代码出处，不量截图；标「原有模块照线上」的不改样式）：
- **App 43 屏**：A1–A15 追更宠物 · B1–B4 新 S 级里程碑 · C1–C11 话题（含 FR-165 首页去入口行）· D1–D7 里程碑发社区 · E1–E6 @ 范围。
- **后台 19 屏**：F1–F9 话题管理 · G1 / G1a / G1b / G2 话题页顶置 · I1–I6 通知昵称变量。

### FR Coverage Map

FR-153.1 / .7 → 1.1 · FR-153.4 → 1.2 / 1.5 · FR-153.3 / .6 → 1.3 / 1.5 · FR-153.2 / .8 → 1.4 · FR-153.5 → 1.5
FR-154.1 / .2 / .3 → 2.1 / 2.3 · FR-154.4 → 2.2
FR-156.1 / .2 → 3.1 · FR-156.3 / .4 → 3.2
FR-155.1、FR-69.1 / .7 → 4.1 · FR-69.4 / .5 / .6（后端）→ 4.2 · FR-69.4 / .5（App）、FR-165.1 → 4.3 · FR-69.2 / .3 → 4.4
FR-157.1 / .3 → 5.1 · FR-157.2 → 5.2
AB-10B.1 ~ .4 → 6.1 · AB-10A.1、AB-10B.5 → 6.2
AB-37.2 / .3（后端）→ 7.1 · AB-37.1 / .4、AB-37.3（页面）→ 7.2
FR-AN → 各功能所在 story · NFR1 ~ 8 → 相关 story 通用验收

## Epic List

### Epic 1: 追更喜欢的宠物
用户追更单只宠物，在关注流看到它的公开 Diary、有新动态收到提醒；主人看到自己宠物的追更人数。
**FRs covered:** FR-153.1 ~ .8 · **迁移:** ① `pet_follows`、④ `pet_diary_notify_log`、⑦ 通知类型与推送分类约束

### Epic 2: 第一次关注、第一次收藏也算里程碑
关注第一个人、收藏第一条帖子时，账号下全部 ACTIVE 宠物完成新 S 级里程碑，只通知、由补庆祝弹一次、成长值只发一次。
**FRs covered:** FR-154.1 ~ .4 · **迁移:** ⑤ flag 表与新编码行、⑥ 存量补算

### Epic 3: 把里程碑发到社区
庆祝页「Bagikan ke Komunitas」带里程碑卡打开预填发布页，站外分享保留为次入口，按钮点了必有反应。
**FRs covered:** FR-156.1 ~ .4

### Epic 4: 用话题聚合内容
用户在正文写 `#话题` 发帖、点话题进话题页按最新 / 热门浏览，Discover 首页有话题行；同时补按天浏览数据供热度、隐藏场所 / 足迹 / 登机牌入口。
**FRs covered:** FR-69.1 ~ .7、FR-155.1、FR-165.1 · **迁移:** ② `topics` / `content_topics`、③ `content_post_view_days`

### Epic 5: 能 @ 我关注的人和关注我的人
@ 范围扩大到关注与粉丝，打字即查，面板贴键盘。
**FRs covered:** FR-157.1 ~ .3

### Epic 6: 运营管理话题与话题页顶置
后台话题列表、官方话题、首页话题行、隐藏 / 禁用 / 恢复，顶置管理加话题页坑位。
**FRs covered:** AB-10B.1 ~ .5、AB-10A.1

### Epic 7: 运营通知带上收件人昵称
系统通知可插入用户昵称 / 宠物昵称变量，按收件人替换，无宠物用户自动排除。
**FRs covered:** AB-37.1 ~ .4 · **迁移:** ⑧ `skipped_no_pet_count`

**依赖：** 同一分支串行 E1 → E7。E3 的多宠卡用到 E2；E5 复用 E4.3 / 4.4 的解析器与贴键盘面板；E6 依赖 E4.1 / 4.2 的话题表与查询（用户话题在 App 发帖时自动建，E6 后做不阻塞 App 联调）。

## Epic 1: 追更喜欢的宠物

用户追更单只宠物，在关注流看到它的公开 Diary、有新动态收到提醒；主人看到自己宠物的追更人数。

### Story 1.1: 追更关系与接口（后端）

As a 喜欢某只宠物的用户,
I want 单独追更这只宠物,
So that 不用关注主人也能跟进它的成长。

**范围：** 迁移 ①（`pet_follows` + `pet_profiles.follower_count`，D-1）；`PetFollowService` / `PetFollowController`（D-8）；每日额度两表合计；拉黑 / 注销联动（D-11）；访客与用户主页新字段（G-1）；发布 `PetFollowedEvent`（1.2 消费）。

**Acceptance Criteria:**

**Given** 他人的 ACTIVE 或 READ_ONLY 宠物 **When** `POST /api/v1/me/pet-follows/{petId}` **Then** 建立关系、`follower_count` +1、发布 `PetFollowedEvent`；重复追更幂等不重复计数〔L1〕
**Given** 已追更 **When** `DELETE` **Then** 删除关系、计数 −1；未追更时幂等成功；主人 `DEACTIVATED` 期间同样可取消〔L1〕
**Given** 追更自己的宠物 / 宠物不存在或主人非 `isActive` / 我 BLOCK 了主人 / 主人 BLOCK 了我 **When** 追更 **Then** 分别 422 / 404 / 403 / 照常成功（不暴露被拉黑）〔L1〕
**Given** 当日 USER 来源关注 + 追更合计 500，或 1 分钟内关注与追更合计 30 次 **When** 再追更 **Then** 429（复用 `rl:social:follow:<uid>`）〔L1〕
**Given** A 拉黑 B **When** 拉黑提交 **Then** 同事务删除 A 对 B 全部宠物的追更并修正计数，B 对 A 宠物的追更不变〔L1〕
**Given** 宠物被删除 / 追更者注销 / 主人或追更者封号 **Then** 关系级联消失 / 该用户全部追更删除且对应计数 −1 / 不删除〔L1〕
**When** `GET /api/v1/users/{token}/pet-follows?cursor=` **Then** 返回追更的宠物（头像、名、物种、主人昵称、`iFollow`），按追更时间倒序，附 `petFollowingCount`；主人 `DEACTIVATED` 的宠物仍列出〔L1〕
**And** 访客 `/profile` 带 `iFollow`、`iFollowOwner`、`ownerToken`，统计（与作者态共用）带 `followerCount`，用户主页带 `petFollowingCount`；pet-follows 列表行同样带 `ownerToken`、`iFollowOwner`（供埋点，R-2）〔L1〕
**Given** 主人注销 **Then** 该主人宠物从所有追更者列表消失、无通知；**Given** 举报产生的隐藏关系 **Then** 不删追更；**Given** 解除拉黑 **Then** 已取消的追更不恢复〔L1〕
**Given** 当日追更已占满额度 **When** 再关注人 **Then** 同样 429（`FollowService` 每日额度两表合计）〔L1〕
**And** 单测与集成测试覆盖空集、被拉黑、已注销 / 封号、翻页游标〔L0 / L1〕

### Story 1.2: 追更通知与推送分类（后端）

As a 宠物主人或追更者,
I want 被追更、或追更的宠物发新 Diary 时收到提醒,
So that 知道谁在关注我的宠物、不错过它的新动态。

**范围：** 迁移 ⑦（`ck_notifications_type` = 当前树最后一次重建的全集 + 2 个新类型；`ck_user_push_mutes_category` 加 `PET_DIARY`，D-7）；迁移 ④（`pet_diary_notify_log`，D-5）；`NotificationType` +2、`PushCategory` + `PET_DIARY`；`PetFollowNotifyListener`、`PetDiaryNotifyListener`（D-10）；改 `PushCategoryTest`、`MentionNotifyTest`。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 约束值 = 当前树最后一条重建迁移的全集 + `PET_FOLLOWED` + `PET_DIARY_POSTED`（不写死数量；电商线先合入则以其为基数）、推送设置允许 `PET_DIARY`；两个守卫测试更新后通过；`GET /me/push-settings` 返回新分类、默认开〔L0 / L1〕
**Given** 用户追更我的宠物 **Then** 我收到 `PET_FOLLOWED`（后端拼文案，ID「{昵称} mulai mengikuti {宠物}」/ EN「{nickname} started following {pet}」），推送受 `FOLLOWER` 开关控制；同一人同一宠物取消后 24 小时内再追不重复；不同宠物分别通知；取消追更不发任何消息〔L1〕
**Given** 双方存在任一方向拉黑 **Then** 不发 `PET_FOLLOWED`〔L1〕
**Given** 追更的宠物发布公开 Diary（含人工审核通过补发） **Then** 追更者收到 `PET_DIARY_POSTED`（后端拼文案：ID「{宠物} punya cerita baru」/ EN「{pet} has a new story」，actor 名为宠物名），点击进该帖；私密 Diary、Moment、Tips、作者本人、任一方向拉黑、主人或追更者 `DEACTIVATED` 均不发〔L1〕
**Given** 同一追更者、同一宠物同一 WIB 日连发 3 条 / 一帖关联我追更的 2 只宠物 **Then** 只收 1 条 / 只收 1 条且文案取第一只〔L1〕
**Given** 关闭「追更宠物」分类 **Then** 不推送、通知中心照常〔L1〕
**And** 监听 AFTER_COMMIT + `REQUIRES_NEW`；扇出 `@Async` 分批；推送走全局限速、失败不重推；去重日志定期清理〔L0 评审 / L1〕

### Story 1.3: 关注流合并追更宠物与宠物推荐改造（后端）

As a 追更者,
I want 在关注流看到追更宠物的新 Diary、推荐里不再出现已追更的宠物,
So that 关注流就是我关心的全部动态。

**范围：** `findFollowingFeed` 并集（D-9）；`FeedService` 出参 `reason` 与两个计数；`PetRecommendationService.pageFor` 排除已追更、`findRecommendablePets` liked 段近 14 天（D-17）。

**Acceptance Criteria:**

**Given** 只追更宠物、未关注主人 **Then** 该宠物新发布的公开 Diary 出现在关注流；它的私密 Diary、主人的 Moment / Tips 不出现〔L1〕
**Given** 同时关注主人并追更宠物 / 一帖关联我追更的 2 只宠物 **Then** 该帖只出现一次〔L1〕
**Given** 宠物主人 A BLOCK 了我（B）且我未关注 A 本人 **Then** A 宠物的 Diary 不出现在我的关注流（PRD FR-153 AC9 按 R-1 收窄）；**Given** 我同时关注 A 本人 **Then** A 的内容（含其宠物 Diary）仍从「关注的人」一路出现，维持 1.3.4 现状（组合用例）〔L1〕
**And** 每条带 `reason`（`followed_user` / `followed_pet`）；首页带 `followingUserCount`、`followingPetCount`〔L1〕
**And** 可见性四件套与游标翻页正确（含追更一路）；L1 以真实量级跑 `EXPLAIN` 记入 Completion Notes〔L1〕
**And** 首页横滑行与集合页不再推荐已追更的宠物；点赞只计近 14 天；排序结构与游标不变〔L1〕

### Story 1.4: 宠物访客页追更与失效修复（App，A1–A4、A14、A15）

As a 访问他人宠物的用户,
I want 在宠物访客页一键追更、看到追更人数，页面失效时能返回或重试,
So that 追更顺手、不会卡在死页面。

**Acceptance Criteria:**

**Given** 他人宠物站内访客页 **Then** 信息卡下整宽 Ikuti；点后变 Mengikuti、Pengikut +1；再点即取消、无确认；失败回弹 + 顶部轻提示（A3）；超频底部轻提示〔L0 widget / L2〕
**And** 统计条第 4 格「N Pengikut」（≥10,000 显示 1.2K）；主人自己的 Diary Tab 同样显示、无按钮无名单（A4）〔L0〕
**And** 页头合并为一行：返回键 +「Diary {name}」+ Kirim Hadiah 胶囊（数值照 UI 稿）；主人 Diary Tab 无返回键不变〔L0 / L2〕
**And** 名片 H5、Feed 卡片、帖子详情均无追更按钮〔L0〕
**Given** 接口 404 **Then** A14：返回箭头 +「Konten ini sudah tidak ada」、无重试 **Given** 断网 / 超时 **Then** A15：「Gagal memuat, silakan coba lagi」+「Coba lagi」，点击重新加载〔L0 widget〕
**And** 埋点 `pet_follow` / `pet_unfollow`（`source = pet_visitor`；`pet_id`、`owner_id` = `ownerToken`、`is_following_owner` = `iFollowOwner`，R-2）〔L0〕
**And** 还原度 ≥ 95%〔L2〕

### Story 1.5: 关注列表、关注流空态、通知与推送设置（App，A5–A13）

As a 追更者,
I want 在关注列表管理追更的宠物、关注流空时知道原因、能单独关掉追更提醒,
So that 追更关系清楚可控。

**Acceptance Criteria:**

**Given** 关注列表 Following Tab **Then** 顶部 Pengguna N / Hewan N 胶囊，默认 Pengguna，无「全部」〔L0〕
**And** Hewan 行：宠物头像 + 名 + 物种 +「milik @主人」，点行进访客页；自己的列表常驻 Mengikuti，取消后变 Ikuti、刷新后移除（A6 / A7）；别人的列表行内无按钮（A9）〔L0〕
**Given** 自己的 Hewan 为空 **Then** 空态 + 推荐宠物（「Kenalan sama anabul lain」，卡片带 Ikuti，点卡进访客页），推荐空或失败整块隐藏（A8）；别人的为空只显示空态〔L0〕
**And** 我的页 / 他人主页四格 Mengikuti = Pengguna 数〔L0〕
**Given** 关注与追更都为 0 **Then** 现有空态；**Given** 有关注或追更但无内容 **Then** A11「暂无新动态」+ 推荐关注用户 5 人 +「Jelajahi Discover ›」；追更宠物的 Diary 在流中正常显示（A10），曝光 / 点击埋点带 `reason`〔L0〕
**And** 通知中心（A12）：两类型都加入 `serverCopyTypes`，并补深链映射、图标样式、`_notifTypeForAnalytics`；`PET_FOLLOWED` 加粗昵称与宠物名、无回关按钮、点击进追更者主页；`PET_DIARY_POSTED` 加粗宠物名、点击进帖、已删提示内容已不存在〔L0〕
**And** 推送设置（A13）：INTERAKSI 组「Pengikut baru」后新增「Hewan yang kamu ikuti」；「Pengikut baru」说明改两行〔L0〕
**And** 列表内追更埋点 `source = follow_list`，Hewan 空态推荐卡上的追更 `source = pet_recommendation`（新增值，R-2）；还原度 ≥ 95%〔L0 / L2〕

## Epic 2: 第一次关注、第一次收藏也算里程碑

关注第一个人、收藏第一条帖子时，账号下全部 ACTIVE 宠物完成新 S 级里程碑，只通知、由补庆祝弹一次、成长值只发一次。

### Story 2.1: 账号级里程碑（后端）

As a 刚开始在社区互动的用户,
I want 第一次关注、第一次收藏被记为宠物的里程碑,
So that 社交行为也能带来成长激励。

**范围：** `MilestoneCatalog` +6 条（C-S19 / D-S19 / G-S12、C-S20 / D-S20 / G-S13）；迁移 ⑤（`user_milestone_flags` + 存量宠物新编码 roster 行，照 V77）；`AccountMilestoneService` + `AccountMilestoneCompletedEvent`；`GrowthActivityListener`、`MilestoneNotifyListener` 改造；`assignRoster` / `PetSlotStateService.reconcile` 钩子；庆祝回报按账号（D-4、D-12、G-2、G-3、G-7）。

**Acceptance Criteria:**

**Given** 迁移完成 **Then** 三份清单含新两条；新建宠物 roster 带新两条；存量宠物已补行；访客视图分母猫狗 35、其他 20〔L0 / L1〕
**Given** 账号有 2 只 ACTIVE 宠物 + 1 只 READ_ONLY **When** 第一次手动关注一个人 **Then** 两只 ACTIVE 宠物完成对应编码、只读不完成；只 1 条 `MILESTONE_SM_NODE`（不推送、文案无宠物名）；成长值流水只 1 条 +5（幂等键 `MILESTONE_ACCT:{userId}:FIRST_FOLLOW_USER`）〔L1〕
**Given** 系统自动关注官方号 / 追更宠物 / 收藏场所 **Then** 不触发；**Given** 收藏自己的帖子 **Then** 触发「第一次收藏」〔L1〕
**Given** 已完成 **When** 再关注 / 再收藏 / 取关后再关注 **Then** 不再触发；取关、取消收藏不撤销〔L1〕
**Given** 触发时账号没有 ACTIVE 宠物 **Then** 写 flag、发成长值，不写完成、不通知；**When** 之后新建宠物或只读恢复 ACTIVE **Then** 直接「已完成且已庆祝」，无事件、无成长值〔L1〕
**Given** 某宠物回报庆祝了这两个编码 **Then** 账号下所有宠物同一条一并置为已庆祝〔L1〕
**Given** OTHER 物种宠物 **Then** 完成的是 G-S12 / G-S13；**Given** 混养账号 **Then** 通知 `targetRef` 为第一只 ACTIVE 宠物物种对应的编码（R-4）〔L1〕
**And** 并发两次关注只触发一次（flag 主键）〔L1〕
**And** 原有按宠物里程碑与「同编码最多 5 次」成长值规则回归不变〔L1〕

### Story 2.2: 存量用户补算（后端，迁移 ⑥）

As a 上线前就关注过人、收藏过帖子的老用户,
I want 升级后这两个里程碑直接是已完成,
So that 不用重做一遍也拿到成长值。

**Acceptance Criteria:**

**Given** 上线前有 USER 来源关注 / 帖子收藏的账号 **Then** 写 flag；其 ACTIVE 宠物按物种标对应编码（C/D-S19、S20，G-S12、S13）为已完成且已庆祝；不发通知、进列表也不补弹〔L1〕
**Given** 上线时没有宠物的账号 **Then** 同样写 flag、成长值照发；之后建宠按 2.1 直接补完成〔L1〕
**And** 成长值流水每账号每 flag 一条 `LAUNCH_BACKFILL` +5（幂等键 `BACKFILL142:{userId}:{flagKey}`），总额与等级同步（照 `V20261018_1503`），无升级弹窗、无通知〔L1〕
**And** 迁移重跑幂等；scratch 库用造数验证，Completion Notes 记录 stag 补算账号数〔L1〕

### Story 2.3: 新里程碑的 App 展示与补庆祝（App，B1–B4）

As a 完成了账号级里程碑的用户,
I want 在合适的时候看到一次庆祝、看到全部宠物一起完成,
So that 不被打断、也不被重复弹窗。

**Acceptance Criteria:**

**Given** 新编码 **Then** `milestone_badge_assets` 按完整编码映射：C-S19 / D-S19 / G-S12 → `first_friend`，C-S20 / D-S20 / G-S13 → `first_save`（不按后缀判断）；素材未到用 `MilestoneBadge` 兜底〔L0〕
**And** 通知中心对这 6 个账号级编码不拼宠物名（`_milestoneCopy` 走无 `{name}` 的标题 / 正文）；庆祝页文案取 PRD §7 四条（R-4）〔L0〕
**And** 两枚同时待庆祝只弹一条；关注 / 收藏发生处不新增即时庆祝弹出点〔L0〕
**And** 里程碑列表（B3）显示新两条，分组标题与顺序照代码〔L0〕
**Given** 有未庆祝的账号级里程碑 **When** 进任一宠物的里程碑列表 **Then** 补庆祝弹一次（沿用「只弹最高级一条」：同时有未庆祝 M / L 则弹那条，这两枚一并记已庆祝）；回报后进其他宠物列表不再弹〔L0〕
**And** 多宠庆祝页（B2）显示全部 ACTIVE 宠物头像与名字、不显示 KOLEKSI；单宠（B1）照现有〔L0 / L2〕
**When** 点通知中心该条（B4） **Then** 进当前选中宠物的里程碑列表并补弹（当前宠物非 ACTIVE 则第一只 ACTIVE）〔L0〕
**And** 文案按 PRD §7；还原度 ≥ 95%〔L2〕

## Epic 3: 把里程碑发到社区

庆祝页「Bagikan ke Komunitas」带里程碑卡打开预填发布页，站外分享保留为次入口，按钮点了必有反应。

### Story 3.1: 里程碑卡与发帖入口扩参（App，D4–D7）

As a 解锁里程碑的用户,
I want 一张好看的里程碑卡能直接带进发帖页,
So that 分享成就不用自己截图。

**Acceptance Criteria:**

**Given** 任一里程碑 **Then** 1:1 里程碑卡按 D6 / D7：奖章花结 + 级别芯片 + 短标题 + 宠物头像与名字 · 完成日期 + TailTopia 字标；紫渐变底、白页脚；M 级飘带 #6C48AE + 白描边；无二维码、无水印；1~5 只宠物排布〔L0 / L2〕
**And** 卡片屏外挂载，等徽章与头像真正画上屏再截；未就绪 / 超时返回失败（不出白图），有 widget 测试〔L0〕
**And** `BragPostEntry.open` 增加 `preset`、`petIds`、`source`（**不加 `milestoneCode`**）；原 4 个来源行为不变（回归）〔L0〕
**When** 以里程碑来源打开发布页 **Then** 预填 Diary、宠物（账号级预选全部 ACTIVE）、卡图 1 张、庆祝文案（单宠 / 多宠两种，PRD §7，不带 #话题）；发布成功事件带 `source = milestone_celebration`〔L0〕
**And** `brag_post_tapped` 新 source `milestone_celebration`，`code`、`level` 由庆祝页调用 `BragPostEntry.reportTap` 时作为附加属性传入（`BragPostEntry.open` 仍不收 `milestoneCode`）〔L0〕

### Story 3.2: 庆祝页重构与两个分享按钮（App，D1–D3）

As a 在庆祝页想分享的用户,
I want 点按钮必有反应、能发到社区也能分享到站外,
So that 不会「点了没反应」。

**Acceptance Criteria:**

**Given** 任一级别庆祝页 **Then** 「Bagikan ke Komunitas / Bagikan ke luar / Lihat semua milestone →」吸底、上沿渐隐、内容区可滚（D1）〔L0 / L2〕
**And** 庆祝页在弹出时取 `ProviderContainer`，6 处弹出点全部改造；宿主页已销毁（如发帖后自动弹出）按钮仍有效〔L0〕
**When** 点任一按钮 **Then** 立即加载态（D2），连点只触发一次〔L0〕
**When** 点「Bagikan ke Komunitas」 **Then** 生成卡图 → 关庆祝页 → 打开发布页；失败（D3）轻提示在按钮组上方、不打开发布页、庆祝页不关可重试；发布页取消回原页、不再弹庆祝〔L0〕
**When** 点「Bagikan ke luar」 **Then** 生成 H5 链接 + 系统分享面板；链接失败退化为只分享文案；任何异常不吞分享面板〔L0〕
**And** 埋点 `milestone_share_failed`（`code`、`stage` ∈ render_card / open_compose / create_link / open_sheet）〔L0〕
**And** 6 处弹出点（含发帖后、健康记录、打卡 picker）与陪伴满 100 / 365 天均验证可发帖；发布后帖子出现在 Feed、该宠物成长档案、追更者关注流（PRD FR-156 AC3，端到端）；还原度 ≥ 95%〔L2〕

## Epic 4: 用话题聚合内容

用户在正文写 `#话题` 发帖、点话题进话题页按最新 / 热门浏览，Discover 首页有话题行；同时补按天浏览数据供热度、隐藏场所 / 足迹 / 登机牌入口。

### Story 4.1: 话题建模、发帖写关联与按天浏览（后端）

As a 发帖的用户,
I want 正文里写的 `#话题` 自动归到对应话题,
So that 同主题的内容能聚在一起。

**范围：** 迁移 ②（`topics` + `content_topics`，D-2）、③（`content_post_view_days`，D-3）；`TopicNameRules` + 共用样例 `fixtures/topic-parse-cases.json`（P-1）；`TopicService` 在 `publish` 与 `publishTrusted` 写关联（G-5）；`ContentPublishedEvent.topicIds`；帖子出参 `topicNames`；`recordView` 按天写入 + 清理任务。

**Acceptance Criteria:**

**Given** 共用样例（纯数字、遇标点结束、2–30 长度、任意语言字母、重复、大小写、第 6 个、emoji） **Then** Java 测试全部通过〔L0〕
**When** 发公开帖「#KucingLucu hari ini」 **Then** 建 USER 话题（显示写法取本次）并写关联；**When** 再发「#kucinglucu」 **Then** 进同一话题、显示写法不变〔L1〕
**Given** 私密 Diary 带话题 **Then** 不写关联、不新建话题〔L1〕
**Given** 命中 DISABLED 话题 **Then** 不写关联；**Given** 命中 HIDDEN **Then** 照常写〔L1〕
**And** 只前 5 个生效；后台种子发帖同样写；同名话题并发建立不报错且只一条〔L1〕
**And** 发帖事件带 `topicIds`；**所有返回帖子正文的出参**（Feed / 详情 / 关注流 / 个人主页与宠物 Diary 列表等）带 `topicNames`（仅 ACTIVE / HIDDEN）；发帖成功响应返回 `topics: [{name, official, created}]`（R-2）〔L1〕
**Given** 话题名命中敏感词 **Then** 整帖按现有审核处理〔L1〕
**Given** 同一观看者当天首次 / 当天再次 / 次日打开帖子详情 **Then** 当天按天数据 +1 / 不变 / 次日 +1；作者本人与 Feed 曝光不计；后台两列口径不变；30 天前数据被清理〔L1〕

### Story 4.2: 话题查询接口与热度任务（后端）

As a 浏览话题的用户,
I want 看到话题下的帖子、首页有热门话题、写 # 有联想,
So that 能发现同主题内容。

**范围：** `TopicController` / `TopicQueryService`（D-13，G-4）；`TopicHeatJob`（D-14）；pinned 接口 `?topic=`。

**Acceptance Criteria:**

**When** `GET /api/v1/topics/{name}` **Then** 返回显示写法、是否官方、按语言的说明；DISABLED / 不存在 404；游客可访问〔L1〕
**When** `GET /api/v1/topics/{name}/posts?sort=latest` **Then** 时间倒序 + 游标；**When** `sort=popular` **Then** 按「点赞 + `comment_weight` × 评论」+ 页码；只已发布 `PUBLIC`、可见性四件套过滤、限流内容照常、私密不出现〔L1〕
**When** `GET /api/v1/topic-suggestions`（无 `q`）**Then** 按热度、官方在前；**When** 带 `q` **Then** 前缀匹配、官方在前；排除 HIDDEN / DISABLED；需登录〔L1〕
**When** `GET /api/v1/topics/home-row` **Then** `featured_order` 在前、`heat_score > 0` 补满 5 个，HIDDEN / DISABLED 不出现；每项带 `featured`、`official`；suggest 与详情出参带 `official`〔L1〕
**When** `GET /api/v1/topics/{name}/related` **Then** 10 个推荐、排除当前与 HIDDEN / DISABLED〔L1〕
**When** `GET /content-posts/pinned?topic={name}` **Then** 返回该话题生效顶置，无则空；有生效「内容顶置」时，被顶置的帖子不在话题列表重复出现（同首页 Feed 让位）〔L1〕
**And** 热度任务每小时：公式与口径按 PRD（近 7 天新发帖 × 1 + 近 7 天按天浏览人数之和 × 0.1 + 近 7 天点赞与评论 × 1）、同时刷新 `post_count`、advisory lock 防重入；公式有单测〔L0 / L1〕
**Given** 话题被禁用 **Then** `topicNames` 不再含它；隐藏仍含〔L1〕

### Story 4.3: 话题详情页、首页话题行与隐藏三个入口（App，C1、C6–C11）

As a 浏览社区的用户,
I want 在 Discover 首页看到热门话题、进入话题页看同主题帖子,
So that 能按兴趣逛内容。

**Acceptance Criteria:**

**Given** 路由 `/topics/:name`（游客可访问） **Then** C6 加载中 / C7 官方（Resmi + 说明，无封面、无帖子数、无分享）/ C8 用户话题·热门 / C9 空态（「Buat Postingan」打开发布页预填「#话题名 」，游客拉登录 + 推荐话题，无则隐藏）/ C10 不存在 / C11 加载失败可重试；返回键与标题同一行〔L0 / L2〕
**And** Terbaru / Populer 切换上报 `topic_sort_switched`；顶置位只在有生效排期时渲染（帖子或推广卡）〔L0〕
**Given** Discover 页签 **Then** 分类标签下一行话题（文字链 + Resmi 徽章，最多 5 个，不足 1 个整行隐藏，随 Feed 滚动，Following 不显示），点击上报 `home_topic_row_tapped`（C1）〔L0〕
**And** FR-165：`feature_flags.dart` 关闭后首页无「Tempat Ramah Hewan」入口行；Kenali Hewanmu 无「Jejak Anabul」「Boarding Pass」两卡、其余上移无空位；其他进入路径不变〔L0〕
**And** `topic_page_viewed`（`topic_id` = 话题标准名，R-2）带 `source`——本 story 验 `home_row` / `pin`，`post_body` / `feed_card` 在 4.4 接入；新埋点前缀登记 `v112_events_test.dart`；话题页标题按显示写法（后台改为 `#MyPet` 后标题显示 `#MyPet`，PRD FR-69 AC11 的 App 端）；还原度 ≥ 95%〔L0 / L2〕

### Story 4.4: 正文话题高亮、输入框高亮与 # 联想（App，C2–C5）

As a 发帖与看帖的用户,
I want 写 # 有联想、写的时候就看到哪些话题生效，看帖时点话题能跳转,
So that 带话题发帖不出错。

**Acceptance Criteria:**

**Given** `shared/rich_text/body_tokens.dart` **Then** 读取与后端同一份共用样例，Dart 测试全部通过〔L0〕
**Given** Feed 卡片与帖子详情（C5） **Then** `topicNames` 内的话题紫色可点进话题页，禁用话题为普通文字；Feed 卡点话题进话题页、点其他区域进帖子；@ 行为不变（回归）；评论区不解析话题〔L0〕
**Given** 发布页与评论框使用 `TopicMentionTextController` **Then** 前 5 个话题紫色、第 6 个起普通色；点选的 @ 紫色、手打不变、删其中字即失效；只改颜色；输入法 composing 下划线保留（有测试）；字数上限不变〔L0〕
**When** 正文只输入 `#` **Then** 面板显示热门话题（官方在前，调无 `q` 的 suggest）；**When** `#` 后继续打字（C2 / C3）**Then** 停 300ms 查询；官方在前；无匹配只剩「#名字 · Topik baru」；面板贴键盘上沿（最高 220）、光标行保持可见；点候选或打空格确定〔L0 / L2〕
**Given** 已有 5 个话题 **When** 再输入 `#`（C4）**Then** 不弹联想、屏幕顶部提示「Maksimal 5 topik per postingan」〔L0〕
**And** `topic_inserted` 在发布成功后按响应 `topics` 逐个上报（`topic_id` = 标准名、`is_official`、`is_new` = `created`，`method` 由本地记录：从联想选 = suggestion，否则 typed，R-2）；正文话题点击的 `topic_page_viewed.source` = `post_body` / `feed_card`；话题与 @ 混排（PRD FR-69 AC13）通过；还原度 ≥ 95%〔L0 / L2〕

## Epic 5: 能 @ 我关注的人和关注我的人

@ 范围扩大到关注与粉丝，打字即查，面板贴键盘。

### Story 5.1: @ 候选范围扩大（后端）

As a 想 @ 朋友的用户,
I want 能 @ 我关注的人和关注我的人,
So that 不用先互动也能提到他们。

**Acceptance Criteria:**

**When** `GET /api/v1/me/mention-candidates`（无 `q`）**Then** 先放我关注的人（按关注时间倒序），不足 30 用互动候选补齐、不重复〔L1〕
**When** 带 `q` **Then** 在「我关注 ∪ 关注我 ∪ 互动候选」内昵称包含匹配（不区分大小写，转义 `%` `_`），最多 20 条，按三类先后排序、同人一次；范围外搜不到〔L1〕
**Given** 新用户只关注了官方号 **Then** 快捷列表有官方号；官方号发帖可 @ 任何用户〔L1〕
**And** 任一方向拉黑、已注销、`DEACTIVATED` 排除〔L1〕
**Given** 发帖 / 评论带 `mentionedUserIds` **Then** 此刻在三类范围内保留、范围外静默丢弃且帖子照常发布；超 5 个 422；之后取关不影响已发出的 @〔L1〕
**And** 每个候选带 `relation`（following / follower / interacted），命中多类取排序靠前的一类〔L1〕

### Story 5.2: @ 面板改造（App，E1–E6）

As a 正在写帖子或评论的用户,
I want 在 @ 后直接打字就能找到人,
So that 不用在过滤框和正文之间来回切。

**Acceptance Criteria:**

**Given** 正文 `@` 后无字 **Then** 显示快捷列表（E1）；**When** 至少 1 个字 **Then** 停 300ms 查询、新查询丢弃旧结果；「Cari nama…」过滤框已移除〔L0〕
**And** 发布页面板贴键盘上沿（与 # 面板同形态）；评论框面板仍在输入条上方、只去过滤框〔L0 / L2〕
**And** 面板内状态：查询中（E2）/ 结果（E3）/ 无结果（E4）/ 失败（E5）/ 一个都没有引导先去关注（E6）〔L0〕
**And** `mention_inserted` 带 `relation`、`from_quick_list`〔L0〕
**And** 超 5 人提示位置不变；还原度 ≥ 95%〔L2〕

## Epic 6: 运营管理话题与话题页顶置

后台话题列表、官方话题、首页话题行、隐藏 / 禁用 / 恢复，顶置管理加话题页坑位。

### Story 6.1: 话题管理页（后台，F1–F9）

As a 运营,
I want 在后台管理话题、建官方话题、决定首页话题行放哪些,
So that 能策划话题活动、冷处理或封禁违规话题。

**范围：** `AdminTopicController` / `AdminTopicService`、`AdminPageCatalog`（「内容标签」后，`TOPIC_VIEW` / `TOPIC_MANAGE`）、`topics.html` + 列表 / 抽屉片段、`admin-topics.js`、开关组件（D-18）。

**Acceptance Criteria:**

**Given** 有 `TOPIC_VIEW` **Then** 侧栏「✍️ 内容」组「内容标签」后出现「话题」；`@PreAuthorize` 与侧栏 `sec:authorize` 逐字一致〔L0 / L1〕
**And** 列表 8 列（话题名 / 类型 / 状态 / 帖子数（每小时刷新，抽屉内实时）/ 近 7 天热度 / 首页话题行开关 + 序号 / 创建时间 WIB / 操作）、类型与状态筛选、名称搜索、摘要条 5 格、默认按热度倒序、筛选无结果空态（F9）、分页，样式照稿〔L1 / L2〕
**When** 新建官方话题（F2） **Then** 名称按 `TopicNameRules` 校验、说明 ID / EN 选填，成功回列表定位新行；**Given** 与用户话题重名（F3） **Then** 黄条 + 原生 confirm → 升级为官方、帖子保留、不可降回；**Given** 与官方话题重名 **Then** 报错不可保存；**Given** 重名用户话题为 HIDDEN **Then** 升级后保持隐藏；**Given** 为 DISABLED **Then** 拒绝升级、提示先恢复（R-5）；升级后显示写法改为后台填写；黄条内帖子数实时查〔L1〕
**When** 编辑（F4 / F5） **Then** 官方说明可改；显示写法只能改大小写（增删替换字符 → F5 错误槽）；用户话题不能填说明（灰字说明）〔L1〕
**Given** 首页话题行（F1 / F8） **Then** 打开排到最后、改序号与原位互换（超出 1–N 行下报错「序号只能填 1–N」）、关闭后面前移；最多 5 个，第 6 个报错；隐藏 / 禁用话题开关不可用〔L1〕
**When** 隐藏 **Then** 直接生效；**When** 禁用（F6 原生 confirm）**Then** 话题页不可访问、立即结束该话题页生效顶置（写 `terminated_at`）、记 `disabled_at`；**When** 恢复 **Then** 隐藏直接恢复、禁用需 confirm（F7）；隐藏 / 禁用自动移出首页话题行、后面前移〔L1〕
**And** 写操作调 `AdminAuditService.record`；三语按 PRD §7 key；并发改序号结果一致；还原度 ≥ 95%〔L1 / L2〕

### Story 6.2: 话题页顶置（后台，G1 / G1a / G1b / G2，抽屉顶置区块）

As a 运营,
I want 给话题页顶置示范帖或活动推广卡,
So that 话题页有官方引导。

**范围：** `AdminContentPinController` / `AdminContentPinService` 支持 `TOPIC:{topicId}` 坑位（D-6）；`content-pins.html` 页签、`pins-list.html`、`drawer-content-pin.html`；话题抽屉顶置区块。

**Acceptance Criteria:**

**Given** 顶置管理页 **Then** 「首页 Feed｜话题页」页签；首页 Feed 即现有列表不变；话题页页签（G1a）列正常与隐藏话题（当前生效 / 排期条数 / 热度），整行可点〔L1 / L2〕
**When** 点话题行进排期页（G1b）**Then** 面包屑「顶置管理 / 话题页 / #话题」，结构同现有顶置列表；新建（G2）坑位与话题预选；内容顶置只列该话题下已发布公开帖子（推广卡不限）；同坑位重叠校验沿用〔L1〕
**And** 话题下拉只列正常与隐藏，不列禁用〔L1〕
**Given** 话题被禁用 **Then** 其排期显示「已结束」；恢复后不自动恢复〔L1〕
**Given** 话题页被顶置的帖子被作者删除 / 审核下架 / 作者封号 **Then** `ContentPinTakedownListener` 同事务结束该排期（回归，同首页 Feed）〔L1〕
**Given** 话题编辑抽屉（官方 / 用户 / 隐藏）**Then** 「话题页顶置」区块显示生效 1 条 + 排期最多 3 条；「＋ 新建顶置」在抽屉内切到 G2（顶部「← 返回话题详情」）；「查看全部排期 →」跳 G1b；禁用话题无此区块〔L1 / L2〕
**And** 配置后 App 话题页按时间段显示、到点下线（端到端）〔L1 / L2〕
**And** 三语、审计、还原度 ≥ 95%〔L2〕

## Epic 7: 运营通知带上收件人昵称

系统通知可插入用户昵称 / 宠物昵称变量，按收件人替换，无宠物用户自动排除。

### Story 7.1: 通知变量替换与人群计算（后端）

As a 运营,
I want 通知里写的「{用户昵称}」「{宠物昵称}」发出时变成每个人自己的名字,
So that 通知更亲切、打开率更高。

**范围：** 迁移 ⑧（`ops_notification_campaigns.skipped_no_pet_count`，R-3）；`NotificationService.writeOpsAnnouncements` 逐人替换；`OpsRecipient` 带昵称与宠物名（批量）；`OpsAudienceQuery` / `OpsAudienceSql` 净人数与有宠物条件；`OpsNotificationDispatcher` 跳过计数；`AdminOpsNotifyService` / `OpsNotificationCampaignService` 长度与手打校验；预览接口（D-16）。

**Acceptance Criteria:**

**Given** 标题 / 正文含 `{{nickname}}` / `{{petName}}` **When** 发送 **Then** 按收件人 locale 逐人替换；空昵称 ID「Sobat」/ EN「there」；通知中心与推送一致〔L1〕
**And** 宠物名：有物种筛选只在该物种内取，ACTIVE 优先、最早建档，全只读取最早只读；一条 SQL 批量取，不逐人查〔L1〕
**Given** 任一语言用了宠物变量 **Then** 人群追加有宠物条件；预计触达返回净人数与排除数，1 万门槛按净人数；净人数 0 提交 422；排除数记入新列 `skipped_no_pet_count`（所有人群模式），原 `skipped_count` 口径不变〔L1〕
**And** 每个变量按 20 字计（标题 120 / 正文 255，两处校验）；输入中手打变量显示名（中 / EN / ID）或令牌 → 拒绝（`admin.err.ops-notify.var.typed`）〔L0 / L1〕
**When** 调预览接口 **Then** 从本次人群随机取 1 人返回替换效果；0 人返回无可预览〔L1〕
**And** 定时发送按发送时刻取值；日志不记正文与收件人；发送记录摘要显示变量原样〔L1〕
**And** 不含变量的通知行为不变（回归）〔L1〕

### Story 7.2: 通知编辑器与确认页（后台，I1–I6）

As a 运营,
I want 点按钮插入变量、发送前看到真实用户的替换效果,
So that 不会发出带大括号的原文。

**Acceptance Criteria:**

**Given** 新建通知第 ① 步（I1）**Then** 四个输入框换成可显示整体标签的编辑区（`contenteditable`）；下方「+ 用户昵称」「+ 宠物昵称」在光标处插入；标签整体删除不可改字；去掉 `maxlength`，JS 计数（变量按 20）〔L1 / L2〕
**And** 实时「示例效果」（hex / tuotuo）与规则说明句〔L2〕
**Given** 手打变量名 / 超长 **Then** I2「请用下方按钮插入变量」/ I3 超长错误，阻止提交〔L1〕
**And** 第 ② 步预计触达说明句改为新文案、显示净人数〔L1〕
**Given** 第 ④ 步（I4）**Then** 预计触达为净人数并注明「已排除 N 位没有宠物的用户」；预览为本次人群真实用户 +「换一个」；**Given** 净人数 0（I6）**Then** 吸底错误槽拦截不能提交〔L1 / L2〕
**And** 同一变量可多次插入；发给全部用户或超过 1 万人时的手动输入人数按净人数校验〔L1〕
**And** 发送记录（I5）「跳过 N（没有宠物）」读 `skipped_no_pet_count`；「触达人数」列 20.5%、内容摘要与发送时间列相应收窄；摘要显示变量原样〔L1 / L2〕
**And** 三语按 PRD §7 key；还原度 ≥ 95%〔L2〕


## 校验结论（2026-11-04）

需求 39 条、UI 62 屏全覆盖；表按首个需要的 story 建（迁移分布于 1.1 / 1.2 / 2.1 / 2.2 / 4.1）；无前向依赖（6.1 禁用结束话题顶置的测试直接造顶置记录，不依赖 6.2）；`publish_compose_page.dart` 被 3.1 / 4.4 / 5.2 依次改不同区域，保留拆分。4.4 最重，云端上下文不足时可把 # 联想拆为 4.5。

2026-11-04 就绪度评审后回改：B1 编码更正、I1–I6 与 m1–m17 补入对应 story，拍板见 `决策日志-social-topic.md`。
