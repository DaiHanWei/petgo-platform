# Epic 7：个人中心、双语与账号注销

> **撰写日期**：2026-06-10  
> **适用版本**：Story 7.1–7.4（均处于 review 状态，L0 已验，L1/L2 待本地）  
> **QA 执行环境**：Android/iOS 真机 + Docker pg+redis（L1）+ TalkBack/VoiceOver（L2 无障碍）

---

## 范围 · 页面/路由清单

| 路由 | 页面文件 | 关联 Story |
|---|---|---|
| `/me` | `features/me/presentation/me_page.dart` | 7.1 |
| `/me/settings` | `features/me/presentation/settings_page.dart` | 7.1、7.3 |
| `/me/language` | `features/me/presentation/language_settings_page.dart` | 7.2 |
| 全局（全 feature 逐屏） | `lib/features/{auth,triage,consult,content,profile,notify,me}` | 7.2、7.4 |

---

## ⚠️ 安全攸关声明

以下两类用例直接影响用户数据权利与医疗安全，**必须 100% 通过，任一失败即阻塞发布**：

1. **账号注销级联删除/匿名化（Story 7.3）**：逐表验证删除/匿名化覆盖完整，不允许 PII 残留。
2. **红色预警无障碍播报（Story 7.4 AC2）**：屏幕阅读器必须以 assertive 模式断言式播报，不允许降级为 polite。

---

## 7.1 「我的」页面与用户信息编辑

### TC-7.1 「我的」Tab 顶栏双图标渲染

- **关联**：Story 7.1 · AC1 · F8
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录用户；任意宠物状态
- **步骤**：
  1. 点击底部 Tab「Me」进入「我的」页面
  2. 观察 AppBar 右上角
- **预期**：右上角并列两个图标——左为「帮助反馈」（`help_outline`，tooltip = "Help & feedback"），右为「设置」（`settings_outlined`，tooltip = "Settings"）；页面主体**不显示**语言/退出登录/账号注销的平铺列表项
- **层级**：L0

---

### TC-7.2 设置图标跳转二级设置页

- **关联**：Story 7.1 · AC1 · F8
- **页面/入口**：`/me` → `/me/settings` · `me_page.dart` → `settings_page.dart`
- **前置**：已登录用户
- **步骤**：
  1. 在「我的」页面点右上角「Settings」图标（ValueKey `meSettings`）
- **预期**：跳转至「Settings / Pengaturan」二级页；该页面包含三个列表项：「Language / Bahasa」（ValueKey `meLanguage`）、「Log out / Keluar」（ValueKey `meLogout`）、「Delete account / Hapus akun」（ValueKey `meDeleteAccount`）；返回「我的」不崩溃
- **层级**：L0

---

### TC-7.3 帮助反馈图标行为

- **关联**：Story 7.1 · AC1
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录用户
- **步骤**：
  1. 点击右上角帮助图标（ValueKey `meHelp`）
- **预期**：底部出现 SnackBar，内容为「Help & feedback coming soon」（en）或对应 id 文案；不跳转外部、不崩溃
- **层级**：L0

---

### TC-7.4 用户信息卡渲染（头像 + 昵称 + 邮箱 + Edit 按钮）

- **关联**：Story 7.1 · AC1、AC2
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录用户，具有昵称和邮箱（`GET /api/v1/me` 返回 nickname/avatarUrl/email）
- **步骤**：
  1. 进入「我的」页面，等待数据加载
- **预期**：用户信息卡（白底圆角卡片）显示：用户头像（网络图或首字母彩色圆）、昵称（`AppTypography.title`）、邮箱（`AppTypography.caption`）、描边「Edit」胶囊按钮（ValueKey `meEditNickname`，图标+文字）；各元素按「人 60%」视觉权重为主视觉
- **层级**：L1（需后端 `GET /api/v1/me` 真实数据）

---

### TC-7.5 昵称编辑弹窗 — 正常保存

- **关联**：Story 7.1 · AC2
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录；昵称为「TestUser」；后端起栈
- **步骤**：
  1. 点击「Edit」胶囊按钮
  2. 在弹窗（标题「Edit nickname / Ubah nama panggilan」）中清空旧值，输入「NewName」（≤20字）
  3. 点击「Submit」按钮（FilledButton）
- **预期**：弹窗关闭；页面昵称区域**即时**更新为「NewName」；无 SnackBar 错误；后端 `PATCH /api/v1/me` 返回 200
- **层级**：L1

---

### TC-7.6 昵称编辑弹窗 — 超长 422 拒绝

- **关联**：Story 7.1 · AC2
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录；后端起栈
- **步骤**：
  1. 点击「Edit」胶囊按钮
  2. 输入 21 个字符（如「abcdefghijklmnopqrstu」），客户端 `maxLength=20` 截断后尝试绕过（或直接 API 测试）
  3. 提交超长内容到后端
- **预期**：后端返回 422 ProblemDetail，`errors[].field = "nickname"`；前端显示「Could not save, please try again / Gagal menyimpan, coba lagi」SnackBar；弹窗关闭后昵称保持原值
- **层级**：L1

---

### TC-7.7 昵称编辑弹窗 — 保存失败保留输入

- **关联**：Story 7.1 · AC2 · UX-DR18 异常态
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录；网络断开或后端模拟 500
- **步骤**：
  1. 断网后点击「Edit」，输入新昵称「NewName」，提交
- **预期**：显示「Could not save, please try again」SnackBar；弹窗已关闭，页面昵称保持旧值（不应显示未保存的新值）；用户可再次打开弹窗重试
- **层级**：L1

---

### TC-7.8 头像替换（Google 默认头像可替换）

- **关联**：Story 7.1 · AC2 · L2
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录；已登录用户有 Google 默认头像；真机 + 真实阿里 OSS 凭证
- **步骤**：
  1. 点击头像区域（若实现了点击替换入口）
  2. 选择相册图片
  3. 客户端压缩 + EXIF 剥离 → STS 直传公开桶① → `PATCH /api/v1/me` 回填 `avatarUrl`
- **预期**：头像即时更新为所选图片；OSS 公开桶①可访问新 URL；旧 Google 头像 URL 不再显示；日志无 avatarUrl 记录
- **层级**：L2

---

### TC-7.9 宠物区位 — 状态 A 已建档显示宠物卡片

- **关联**：Story 7.1 · AC5
- **页面/入口**：`/me` · `me_page.dart`（`_PetZone` → `_PetCard`）
- **前置**：用户 `petStatus=HAS_PET`，`hasPetProfile=true`，宠物有名字和头像，且「我的发布」有至少一条 `GROWTH_MOMENT` 类型帖子含图片
- **步骤**：
  1. 进入「我的」页面
- **预期**：用户信息卡下方出现宠物卡片（ValueKey `mePetCard`）：宠物头像、宠物名称、最近一条快乐时刻首图缩略图（ValueKey `mePetCardHappyImage`）；视觉权重低于用户信息区；点击跳转成长档案 Tab（`/profile`）
- **层级**：L1

---

### TC-7.10 宠物区位 — 状态 A 未建档显示引导卡

- **关联**：Story 7.1 · AC5
- **页面/入口**：`/me` · `me_page.dart`（`_PetZone` → `_PetGuideCard`）
- **前置**：用户 `petStatus=HAS_PET`，`hasPetProfile=false`
- **步骤**：
  1. 进入「我的」页面
- **预期**：出现绿色边框引导卡（ValueKey `mePetGuideCard`），文案「Create a profile for your pet / Buat profil untuk hewanmu」；点击跳转 `/profile/create` 创建流程；**不显示**宠物卡片
- **层级**：L0/L1

---

### TC-7.11 宠物区位 — 状态 B/C 不显示任何卡片

- **关联**：Story 7.1 · AC5
- **页面/入口**：`/me` · `me_page.dart`（`_PetZone`）
- **前置**：用户 `petStatus=PLANNING` 或 `ENTHUSIAST`
- **步骤**：
  1. 进入「我的」页面
- **预期**：用户信息卡与宠物状态卡之间**无**宠物卡片也**无**引导卡；`_PetZone` 返回 `SizedBox.shrink()`
- **层级**：L0

---

### TC-7.12 宠物状态卡 — 状态 A 显示「编辑宠物档案」入口

- **关联**：Story 7.1 · AC4
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：用户 `petStatus=HAS_PET`，`hasPetProfile=true`
- **步骤**：
  1. 进入「我的」，观察「Pet status / Status hewan」卡片区域
- **预期**：显示状态徽章「🐾 I have a pet / Saya punya hewan」；「Change status / Ubah status」按钮（ValueKey `mePetStatus`）；另一行显示「Edit pet profile / Ubah profil hewan」入口（ValueKey `meEditPetProfile`），点击跳转 `/profile/edit`（FR-39 编辑页）
- **层级**：L1

---

### TC-7.13 宠物状态 B/C → A 修改触发 FR-0G 创建引导

- **关联**：Story 7.1 · AC4
- **页面/入口**：`/me` → `/onboarding/pet-status`
- **前置**：用户当前 `petStatus=PLANNING`，无宠物档案；后端起栈
- **步骤**：
  1. 在「我的」点击「Change status / Ubah status」
  2. 在宠物状态选择页选择「I have a pet / Saya punya hewan」并确认
- **预期**：状态更新成功；跳转 FR-0G 宠物档案创建引导（与注册时体验一致，含「Skip」选项）；若跳过则显示 FR-0H 首页提示条；「我的」页、成长档案 Tab、首页 Feed 均反映新状态
- **层级**：L1

---

### TC-7.14 「我的发布」三类混合时间线倒序按 created_at

- **关联**：Story 7.1 · AC3、AC6（F9）
- **页面/入口**：`/me` · `me_page.dart`（`_MyPostsList`）
- **前置**：已登录用户，种植三类内容：GROWTH_MOMENT（成长日记，`event_date` 与 `created_at` 顺序相反）、DAILY_LIFE（日常）、PET_KNOWLEDGE（科普）；后端起栈
- **步骤**：
  1. 进入「我的」，等待「My posts / Postingan saya」区域加载
- **预期**：横向缩略图列表按**发布时间 `created_at` 倒序**排列（最新发布在前）；**不按** `event_date` 排序；三类内容混合展示；每张缩略图可点击进入详情（`/content/<id>`）
- **层级**：L1（需种 `event_date` 与 `created_at` 反序的种子数据）

---

### TC-7.15 「我的发布」空态

- **关联**：Story 7.1 · AC3 · UX-DR18
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录用户，无任何已发布内容
- **步骤**：
  1. 进入「我的」
- **预期**：「My posts / Postingan saya」区域显示空态文案「You haven't posted anything yet / Kamu belum memposting apa pun」（ValueKey `meNoPosts`）；不崩溃不报错
- **层级**：L1

---

### TC-7.16 「我的发布」删除条目后 Feed/时间线同步移除

- **关联**：Story 7.1 · AC3 · FR-36
- **页面/入口**：`/me` · `me_page.dart`
- **前置**：已登录用户，至少两条已发布内容；后端起栈
- **步骤**：
  1. 在「我的」或 Feed 中删除一条内容（FR-36 二次确认流程）
- **预期**：「我的发布」横向列表该条目消失；Feed 同步移除；关联的点赞/评论随内容软删（不在 Feed 可见）
- **层级**：L1

---

### TC-7.17 越权访问 /me 拒绝（安全）

- **关联**：Story 7.1 · Dev Notes 合规约束
- **页面/入口**：`GET /api/v1/me` · 后端接口
- **前置**：用户 A、用户 B 均已登录；后端起栈
- **步骤**：
  1. 用用户 A 的 JWT token 手动请求 `GET /api/v1/me`
  2. 构造请求尝试读取用户 B 的 id（如 `GET /api/v1/me?userId=<B_id>`）
- **预期**：`GET /api/v1/me` 仅返回 token sub 对应的本人数据；任何越权读取返回 403 ProblemDetail；不暴露他人 PII
- **层级**：L1

---

## 7.2 语言设置与双语文案收口

### TC-7.18 语言设置入口路径正确

- **关联**：Story 7.2 · AC2 · F8
- **页面/入口**：`/me` → `/me/settings` → `/me/language` · `language_settings_page.dart`
- **前置**：已登录用户
- **步骤**：
  1. 「我的」→ 右上角「Settings」图标 → 设置页 → 点击「Language / Bahasa」（ValueKey `meLanguage`）
- **预期**：进入「Language / Bahasa」页面（`languageSettingsTitle`）；显示三选项：「Follow system / Ikuti sistem」（ValueKey `langFollowSystem`）、「English」（ValueKey `langEn`）、「Bahasa Indonesia」（ValueKey `langId`）；当前选中项有对勾图标
- **层级**：L0

---

### TC-7.19 手动切换至印尼语即时全局生效

- **关联**：Story 7.2 · AC2
- **页面/入口**：`/me/language` · `language_settings_page.dart`
- **前置**：当前语言为「English」；真机
- **步骤**：
  1. 进入语言设置页，点击「Bahasa Indonesia」
- **预期**：**无需重启**，全 App 文案即时切换为印尼语（TabBar、页面标题、按钮文案等均变为 id）；语言页标题变为「Bahasa」；「Bahasa Indonesia」选项出现对勾
- **层级**：L2（真机验 UI 树重建）

---

### TC-7.20 手动切换至英语即时全局生效

- **关联**：Story 7.2 · AC2
- **页面/入口**：`/me/language` · `language_settings_page.dart`
- **前置**：当前语言为「Bahasa Indonesia」；真机
- **步骤**：
  1. 进入语言设置页，点击「English」
- **预期**：全 App 文案即时切换为英语；无需重启；「English」选项出现对勾
- **层级**：L2

---

### TC-7.21 语言设置持久化（杀进程重启保持）

- **关联**：Story 7.2 · AC2
- **页面/入口**：`/me/language` · `language_settings_page.dart`
- **前置**：真机；用户已手动设置为「Bahasa Indonesia」
- **步骤**：
  1. 手动设置为「Bahasa Indonesia」
  2. 完全关闭 App（杀进程）
  3. 重新启动 App
- **预期**：重启后 App 保持「Bahasa Indonesia」；语言设置页显示「Bahasa Indonesia」选中
- **层级**：L2

---

### TC-7.22 首次启动跟随系统语言 — 印尼语设备

- **关联**：Story 7.2 · AC1
- **页面/入口**：App 启动
- **前置**：真机系统语言设置为「Bahasa Indonesia」；App 已卸载重装（无历史偏好）
- **步骤**：
  1. 安装 App，首次启动
- **预期**：App 默认显示印尼语；语言设置页显示「Follow system / Ikuti sistem」选中
- **层级**：L2

---

### TC-7.23 首次启动跟随系统语言 — 非 id/en 设备回退英语

- **关联**：Story 7.2 · AC1
- **页面/入口**：App 启动
- **前置**：真机系统语言设置为第三语言（如中文、日语）；App 已卸载重装
- **步骤**：
  1. 首次启动 App
- **预期**：App 显示英语（回退 en）；语言设置页显示「Follow system」选中
- **层级**：L2

---

### TC-7.24 UGC 内容不受语言设置影响

- **关联**：Story 7.2 · AC2
- **页面/入口**：Feed、「我的发布」、成长档案
- **前置**：已有英文 UGC 帖子（正文、评论）；当前切换至印尼语
- **步骤**：
  1. 在印尼语 UI 下浏览包含英文帖子的 Feed
- **预期**：英文帖子正文、评论按**原文**显示，不被翻译/改写；昵称（UGC）原样显示
- **层级**：L1/L2

---

### TC-7.25 双语 ARB key 完全对齐（无漏译）

- **关联**：Story 7.2 · AC3
- **页面/入口**：`lib/l10n/app_en.arb`、`lib/l10n/app_id.arb`
- **前置**：代码最新；`flutter gen-l10n` 通过
- **步骤**：
  1. 执行 `microcopy_rules_test.dart` 中的 key 对齐测试
  2. 手动 diff `app_en.arb` 与 `app_id.arb` 的 key 集合
- **预期**：两套 ARB key 集合完全相同（无缺漏）；`flutter gen-l10n` 无报错；`microcopy_rules_test` 全绿
- **层级**：L0

---

### TC-7.26 microcopy 规则守门 — 每条 ≤1 emoji

- **关联**：Story 7.2 · AC3 · UX-DR14
- **页面/入口**：`lib/l10n/app_en.arb`、`lib/l10n/app_id.arb`
- **前置**：代码最新
- **步骤**：
  1. 执行 `test/l10n/microcopy_rules_test.dart`（emoji count 校验）
- **预期**：所有 ARB 文案每条 emoji 数量 ≤1；测试全绿
- **层级**：L0

---

### TC-7.27 microcopy 规则守门 — 问诊结果页无感叹号

- **关联**：Story 7.2 · AC3 · UX-DR14
- **页面/入口**：`lib/l10n/app_en.arb`、`lib/l10n/app_id.arb`
- **前置**：代码最新
- **步骤**：
  1. 执行 `microcopy_rules_test.dart`（`triage*` key 无感叹号校验）
- **预期**：所有 `triage*` 前缀文案不含「!」「！」；测试全绿
- **层级**：L0

---

### TC-7.28 microcopy 规则守门 — 红色预警文案简短无歧义

- **关联**：Story 7.2 · AC3 · UX-DR14（安全攸关）
- **页面/入口**：`lib/l10n/app_en.arb`、`lib/l10n/app_id.arb`
- **前置**：代码最新
- **步骤**：
  1. 执行 `microcopy_rules_test.dart`（`triageRed*` 长度 ≤120 校验）
- **预期**：所有 `triageRed*` 文案长度 ≤120 字符；无修辞、无歧义；测试全绿
- **层级**：L0

---

### TC-7.29 合规文案双语语义等价（隐私政策/免责声明）

- **关联**：Story 7.2 · Dev Notes 合规约束 · NFR-8
- **页面/入口**：`lib/l10n/app_en.arb`、`lib/l10n/app_id.arb`
- **前置**：代码最新
- **步骤**：
  1. 比对免责声明（`disclaimer*`）、隐私政策入口（`privacy*`）、注销说明（`deleteAccount*`）的 en 与 id 文案
- **预期**：id 与 en 文案语义等价，无一方完整另一方缺失；印尼语文案非机器直译、措辞准确
- **层级**：L0（人工语义审查）

---

### TC-7.30 错误文案本地化 — 422/401 按语言显示

- **关联**：Story 7.2 · AC3 · B1
- **页面/入口**：全 App 错误弹窗/Toast
- **前置**：后端起栈；分别在 en 和 id 语言下测试
- **步骤**：
  1. 在印尼语下触发 422 错误（如昵称超长）
  2. 在英语下触发 401 错误（token 失效）
- **预期**：错误提示文案分别以对应语言显示；ProblemDetail `type` 可被前端 dio 拦截器映射为本地化文案；`detail` 不含写死中文或混语
- **层级**：L1

---

### TC-7.31 全 App 零写死中文字符串（全局回归）

- **关联**：Story 7.2 · AC3 · MEMORY（App 绝不渲染中文）
- **页面/入口**：全 App 代码
- **前置**：代码最新；`flutter analyze` 通过
- **步骤**：
  1. 执行写死字符串扫描（`flutter analyze` + 自定义 lint）
  2. 在英语模式和印尼语模式下各运行真机，逐页检查是否有中文硬编码出现
- **预期**：零中文字符串硬编码出现在 UI 层；`flutter analyze` 零写死文案告警；两种语言模式下均不出现中文
- **层级**：L0（静态扫描）+ L2（真机逐页目测）

---

## 7.3 退出登录与账号注销级联删除

### TC-7.32 退出登录 — 确认弹窗文案

- **关联**：Story 7.3 · AC1
- **页面/入口**：`/me/settings` · `settings_page.dart`（`_logout`）
- **前置**：已登录用户；语言 en
- **步骤**：
  1. 设置页点「Log out」（ValueKey `meLogout`）
- **预期**：弹出确认对话框，标题「Log out?」（`logoutConfirmTitle`）；有两个按钮：取消（`consultCancel`）和「Log out」（ValueKey `logoutConfirmYes`，FilledButton）；文案无中文
- **层级**：L0

---

### TC-7.33 退出登录 — 取消后数据完好

- **关联**：Story 7.3 · AC1
- **页面/入口**：`/me/settings` · `settings_page.dart`
- **前置**：已登录用户
- **步骤**：
  1. 点「Log out」
  2. 弹窗出现后点「Cancel」
- **预期**：弹窗关闭，返回设置页；用户仍为登录态；任何数据未被删除
- **层级**：L0

---

### TC-7.34 退出登录 — 确认后清本地态回游客

- **关联**：Story 7.3 · AC1
- **页面/入口**：`/me/settings` · `settings_page.dart`
- **前置**：已登录用户；后端起栈
- **步骤**：
  1. 点「Log out」→ 弹窗确认「Log out」（ValueKey `logoutConfirmYes`）
- **预期**：`flutter_secure_storage` 中 access/refresh token 被清除；auth provider 重置为游客态；App 导航至首页（`/home`）；游客态可浏览 Feed；**所有数据保持完整不删除**；后端 `POST /api/v1/auth/logout` 使 refresh 失效
- **层级**：L1

---

### TC-7.35 退出后旧 token 无法访问受保护接口

- **关联**：Story 7.3 · AC1
- **页面/入口**：`POST /api/v1/auth/logout` + 后续 API
- **前置**：已登录；后端起栈；保存旧 refresh token
- **步骤**：
  1. 退出登录
  2. 使用旧 refresh token 调用刷新接口
  3. 使用旧 access token 调用受保护接口
- **预期**：旧 refresh token 失效（401）；access token 短时内自然过期后亦失效；不能重新获取数据
- **层级**：L1

---

### TC-7.36 ⚠️安全 账号注销 — 第一重确认弹窗文案与语义

- **关联**：Story 7.3 · AC2 · NFR-8
- **页面/入口**：`/me/settings` · `settings_page.dart`（`_deleteAccount` 第一步）
- **前置**：已登录用户；语言 en
- **步骤**：
  1. 设置页点「Delete account」（ValueKey `meDeleteAccount`）
- **预期**：弹出第一重确认对话框：标题「Delete account?」（`deleteAccountWarnTitle`）；正文「Your personal data will be permanently deleted and cannot be recovered.」（`deleteAccountWarnBody`）；有「Cancel」和「Continue」（ValueKey `deleteAccountContinue`，红色高危按钮 `AppColors.danger`）；措辞庄重、无歧义；印尼语下同语义等价文案
- **层级**：L0

---

### TC-7.37 ⚠️安全 账号注销 — 第二重确认需输入「DELETE / HAPUS」短语

- **关联**：Story 7.3 · AC2
- **页面/入口**：`/me/settings` · `settings_page.dart`（`_deleteAccount` 第二步）
- **前置**：已登录用户；通过第一重确认
- **步骤**：
  1. 在第二重确认弹窗（标题「Confirm deletion / Konfirmasi penghapusan」）中观察
  2. 输入错误短语（如「delete」小写）
  3. 输入正确短语（en: 「DELETE」；id: 「HAPUS」）
- **预期**：
  - 输入错误短语时：「Delete permanently」（ValueKey `deleteAccountConfirmYes`）按钮**不可点击**（`onPressed=null`）
  - 输入完全匹配的正确短语时：按钮**变为可点击**（红色 FilledButton）
  - 短语大小写敏感（「DELETE」≠「delete」）
- **层级**：L0

---

### TC-7.38 ⚠️安全 账号注销 — 中途取消不删数据

- **关联**：Story 7.3 · AC2
- **页面/入口**：`/me/settings` · `settings_page.dart`
- **前置**：已登录用户
- **步骤**：
  1. 第一重确认点「Continue」
  2. 第二重确认输入正确短语后点「Cancel」
- **预期**：弹窗关闭，返回设置页；用户仍为登录态；任何数据未被删除；可再次触发注销流程
- **层级**：L0

---

### TC-7.39 ⚠️安全 账号注销 — 级联删除完成后清本地态回游客

- **关联**：Story 7.3 · AC2
- **页面/入口**：`/me/settings` · 后端 `DELETE /api/v1/me`
- **前置**：已登录；后端起栈
- **步骤**：
  1. 完成双重确认，提交「Delete permanently」
- **预期**：前端调用 `DELETE /api/v1/me`（附确认短语），收到 202 受理；清除本地 token；auth provider 重置为游客态；跳转 `/home` 游客态；SnackBar 或页面显示「注销受理」提示；**不显示注销过程中间状态**
- **层级**：L1

---

### TC-7.40 ⚠️安全 账号注销 — 双重确认缺失载荷 422

- **关联**：Story 7.3 · AC2 · B2
- **页面/入口**：`DELETE /api/v1/me` · 后端接口
- **前置**：后端起栈；已登录
- **步骤**：
  1. 直接调用 `DELETE /api/v1/me` 不携带确认短语 body
  2. 携带错误短语（如 `{"confirmPhrase": "wrong"}`）
- **预期**：两种情况均返回 422 ProblemDetail；`detail` 说明缺少或错误的确认载荷；不触发任何删除操作
- **层级**：L1

---

### TC-7.41 ⚠️安全 账号注销 — 逐表删除完整性验证（纯个人数据）

- **关联**：Story 7.3 · AC2 · B3 · D1（核心安全用例）
- **页面/入口**：后端 `AccountDeletionService` · PostgreSQL
- **前置**：Docker pg+redis；种植完整用户数据：users（含 refresh_tokens）、pet_profiles、health_events、triage_tasks（AI 分诊记录）、notifications；宠物有 H5 名片（pet_profiles 中有 card_token）
- **步骤**：
  1. 触发账号注销（双重确认 + `DELETE /api/v1/me`）
  2. 等待 `AccountDeletionJob` 状态机走到 `DONE`
  3. 用 psql 逐表验证：
     ```sql
     SELECT count(*) FROM users WHERE id = <userId>;
     SELECT count(*) FROM pet_profiles WHERE user_id = <userId>;
     SELECT count(*) FROM health_events WHERE pet_id IN (...);
     SELECT count(*) FROM triage_tasks WHERE user_id = <userId>;
     SELECT count(*) FROM notifications WHERE user_id = <userId>;
     ```
  4. 验证 `account_deletions` 表中对应行 `status = 'DONE'`
- **预期**：以上各表 `count = 0`；`account_deletions.status = 'DONE'`；不存在任何该用户的个人数据残留
- **层级**：L1

---

### TC-7.42 ⚠️安全 账号注销 — consult 会话/评分匿名化保留（D1）

- **关联**：Story 7.3 · AC2 · B5b · 决策 D1（核心安全用例）
- **页面/入口**：后端 `ConsultAnonymizationService` · PostgreSQL
- **前置**：Docker pg+redis；种植：`consult_sessions`（含 user_id、symptom、danger_level）和 `consult_ratings`（含 user_id、vet_id、stars、comment）
- **步骤**：
  1. 触发账号注销
  2. 等待 `AccountDeletionJob` 状态机 `DONE`
  3. 用 psql 验证：
     ```sql
     SELECT id, user_id, symptom, danger_level FROM consult_sessions WHERE id = <sessionId>;
     SELECT id, user_id, vet_id, stars, comment FROM consult_ratings WHERE id = <ratingId>;
     ```
- **预期**：
  - `consult_sessions` 行**仍存在**（count=1）；`user_id` 为 NULL 或匿名占位（**PII 已剥离**）；`symptom`/`danger_level` 字段保留（供运营 FR-33 与未来 FR-5）
  - `consult_ratings` 行**仍存在**；`user_id` 为 NULL；`vet_id`/`stars`/`comment` 保留
  - **无任何该用户 PII 残留在 consult 表中**
- **层级**：L1

---

### TC-7.43 ⚠️安全 账号注销 — UGC 帖子/评论匿名化保留（AC3）

- **关联**：Story 7.3 · AC3 · B5
- **页面/入口**：后端 `AccountQueryService.findAuthorViews` · Feed 页面
- **前置**：Docker pg+redis；种植：该用户的 `content_posts`（含图片）和 `comments`；另一用户也在 Feed 可见
- **步骤**：
  1. 触发账号注销
  2. 用 psql 验证 `content_posts`/`comments` 行存在（count > 0）
  3. 以其他用户身份浏览 Feed（L1 API 或 L2 真机）
- **预期**：
  - 帖子/评论行**仍存在**于数据库（不物理删除）
  - Feed API 返回该帖子时：`authorNickname` = 「已注销用户」（对应 en「Deleted user」/ id 本地化文案）、头像 URL 为默认占位
  - 帖子图片（公开桶①）**仍可访问**（UGC 图随匿名化保留）
  - `author_id` 已解关联（无法回溯到已注销用户）
- **层级**：L1

---

### TC-7.44 ⚠️安全 账号注销 — 匿名作者不触发 FR-26 迷你主页卡

- **关联**：Story 7.3 · AC3 · FR-26
- **页面/入口**：Feed 页面、评论区
- **前置**：注销后，Feed 中仍有该已注销用户的帖子；后端起栈
- **步骤**：
  1. 在 Feed 点击「已注销用户 / Deleted user」头像或昵称
- **预期**：**不弹出** FR-26 迷你主页卡；点击无响应或显示已注销占位页；不展示任何个人数据
- **层级**：L1

---

### TC-7.45 ⚠️安全 账号注销 — H5 宠物名片 token 立即失效（F14）

- **关联**：Story 7.3 · AC4 · 决策 F14
- **页面/入口**：`/card/<cardToken>` · `profile/web/CardPageController.java`
- **前置**：注销前保存宠物 H5 名片链接（含不可枚举 token）；后端起栈
- **步骤**：
  1. 触发账号注销（删除 `pet_profiles`）
  2. 访问原名片链接
- **预期**：返回 404 失效页（「这只宠物的档案已不存在」）；**不返回 200**、**不展示任何宠物数据**；防枚举（不暴露 token 曾否存在）；两路保证：① `pet_profiles` 被删 → `findByCardToken` 返空 → `gone()` 404；② `isActive(ownerId)==false` → 同 `gone()` 404
- **层级**：L1

---

### TC-7.46 ⚠️安全 账号注销 — 个人 OSS 图片删除（私密桶②）

- **关联**：Story 7.3 · AC2 · B3 · 决策 D2
- **页面/入口**：阿里 OSS 私密桶② · `shared/media/MediaDeletionService`
- **前置**：真实阿里 OSS 凭证 + 真实个人 OSS 图片；注销前记录对象 key 列表
- **步骤**：
  1. 触发账号注销
  2. 等待 `AccountDeletionJob` 完成
  3. 用阿里 OSS API 列举私密桶②中该用户路径下的对象
- **预期**：分诊图、健康历史图、consult 存档图**均已被删除**（桶内对象 key 不存在）；头像（公开桶①）**已被删除**；帖子图（公开桶①，属 UGC）**仍保留**
- **层级**：L2

---

### TC-7.47 ⚠️安全 账号注销 — 腾讯 IM 聊天媒体处置（D2）

- **关联**：Story 7.3 · AC2 · B5c · 决策 D2
- **页面/入口**：腾讯 IM API · `shared/im/TencentImClient`
- **前置**：该用户有 IM 聊天记录（含图片）；真实腾讯 IM 环境（L2）
- **步骤**：
  1. 触发账号注销
  2. 验证 `TencentImClient.deleteUserConversationMedia` 是否被调用（L1 可验 stub 调用；L2 验真实删除）
  3. 若 dev 选择依赖 IM 侧 TTL：确认 TTL 清理周期已记录在 Completion Notes
- **预期**：IM 聊天媒体被删除或有明确 TTL 自动清理机制（二选一，**不可含糊带过**）；stub 在 L1 被调用（无异常）；真实删除在 L2 验
- **层级**：L1（stub）+ L2（真实 IM）

---

### TC-7.48 ⚠️安全 账号注销 — Redis 痕迹清理

- **关联**：Story 7.3 · AC2 · B3
- **页面/入口**：Redis · `AccountDeletionService`
- **前置**：Docker redis；该用户有在线态/角标计数/队列态等 Redis 键
- **步骤**：
  1. 触发账号注销
  2. 等待 `AccountDeletionJob` 完成
  3. 用 `redis-cli KEYS *<userId>*` 扫描
- **预期**：该用户所有 Redis 键均已删除（count=0）；无痕迹残留
- **层级**：L1

---

### TC-7.49 ⚠️安全 账号注销 — 状态机 PENDING→PROCESSING→DONE 幂等可重试

- **关联**：Story 7.3 · AC2 · B3（可靠异步）
- **页面/入口**：后端 `AccountDeletionService` · `account_deletions` 表
- **前置**：Docker pg；模拟删除中途失败（如第一次处理抛异常）
- **步骤**：
  1. 触发注销，模拟中途失败
  2. 观察 `account_deletions` 表 `status=FAILED`，`retry_count++`
  3. 启动重扫或手动重触
- **预期**：重跑剩余删除步骤（幂等，已删的不重删）；最终状态机走到 `DONE`；半途不留残留状态；`retry_count` 累加记录
- **层级**：L1

---

### TC-7.50 ⚠️安全 账号注销 — 日志不含 PII

- **关联**：Story 7.3 · AC2 · B6 · 架构日志规范
- **页面/入口**：后端应用日志（logback JSON）
- **前置**：后端起栈；触发完整注销流程
- **步骤**：
  1. 触发账号注销，收集 logback JSON 输出
  2. 搜索日志中是否含昵称、头像 URL、令牌、签名 URL、健康数据（症状）
- **预期**：日志**仅含** deletionId + 状态机进度 + 删除项计数；**不含**任何 PII（昵称/头像 URL/令牌/签名 URL/健康数据）；`account_deletions` 中仅记录代理 id + 时间（合规留证）
- **层级**：L1

---

### TC-7.51 ⚠️安全 账号注销 — 注销后旧 token 无法访问任何资源

- **关联**：Story 7.3 · AC2
- **页面/入口**：`GET /api/v1/me`、`GET /api/v1/me/posts` 等受保护接口
- **前置**：注销前保存旧 access token；注销完成后
- **步骤**：
  1. 用旧 access token 调用受保护接口
- **预期**：返回 401；不返回任何已注销用户数据；token 彻底失效
- **层级**：L1

---

### TC-7.52 退出登录 vs 注销严格区分

- **关联**：Story 7.3 · AC1 · Dev Notes 强制护栏
- **页面/入口**：设置页 · 后端 `POST /auth/logout` vs `DELETE /me`
- **前置**：两个已登录用户 A（退出）和 B（注销）；后端起栈；数据库已种植完整数据
- **步骤**：
  1. 用户 A 执行退出登录
  2. 查 A 的 `users`/`pet_profiles` 等表行
  3. 用户 B 执行账号注销
  4. 查 B 的 `users`/`pet_profiles` 等表行
- **预期**：用户 A 退出后所有数据**完好**（count > 0）；用户 B 注销后个人数据**删除/匿名化**（count=0）；两者代码路径严格分离
- **层级**：L1

---

### TC-7.53 陈旧入口访问已注销用户 — UX-DR18 占位页

- **关联**：Story 7.3 · B5 · UX-DR18 ④⑥
- **页面/入口**：深链、他人分享的已注销用户相关页
- **前置**：已注销用户曾有 Feed 帖子详情链接；后端起栈
- **步骤**：
  1. 他人从陈旧入口访问已注销用户的个人页（如 `/user/<id>`）
  2. 访问含已注销作者的帖子详情
- **预期**：个人页返回 UX-DR18 ④「不存在」占位页，不暴露资源曾存在；帖子详情仍可见（UGC 匿名保留），作者显示「已注销用户」；**不崩溃**
- **层级**：L1

---

## ⚠️ 注销数据生命周期逐表核对清单

> QA 执行 TC-7.41–TC-7.48 时，对照本清单逐一打勾。**所有行必须 100% 通过，任一勾不满足即阻塞发布。**

| 表/资源 | 操作 | 依据 | 验证方式 | 通过？ |
|---|---|---|---|---|
| `users`（含 OAuth 绑定） | **物理删除** | D1/架构 | `SELECT count(*) WHERE id=<userId> → 0` | ☐ |
| `refresh_tokens`（用户关联） | **物理删除** | D1 | 同上 + `POST /auth/refresh` 401 | ☐ |
| `pet_profiles` | **物理删除** | D1/F14 | `SELECT count(*) WHERE user_id=<userId> → 0` | ☐ |
| `health_events` | **物理删除** | D1 | `SELECT count(*) WHERE pet_id IN (宠物 id 集) → 0` | ☐ |
| `triage_tasks`（AI 分诊记录） | **物理删除** | D1 | `SELECT count(*) WHERE user_id=<userId> → 0` | ☐ |
| `notifications` | **物理删除** | D1 | `SELECT count(*) WHERE user_id=<userId> → 0` | ☐ |
| `content_posts`（UGC 帖子） | **匿名化保留**（解关联 user PII） | D1/B5 | 行存在；`author_id` 解关联/NULL；Feed 显「已注销用户」 | ☐ |
| `comments`（UGC 评论） | **匿名化保留** | D1/B5 | 行存在；`author_id` 解关联；评论区显「已注销用户」 | ☐ |
| `consult_sessions` | **匿名化保留**（剥 user PII，保留 symptom/danger_level/vet_id） | D1/B5b | 行存在；`user_id = NULL`；symptom/danger_level 在 | ☐ |
| `consult_ratings` | **匿名化保留**（剥 user PII，保留 vet_id/stars/comment） | D1/B5b | 行存在；`user_id = NULL`；stars/comment 在 | ☐ |
| 头像（公开桶① 个人图） | **物理删除** | D1/B3 | OSS 列举该 key 不存在 | ☐ |
| 分诊图/健康历史图（私密桶②） | **物理删除** | D1/B3 | OSS 列举无残留 key | ☐ |
| consult 存档图（私密桶②） | **物理删除** | D1/B5b/B5c | OSS 列举无残留 key | ☐ |
| UGC 帖子图（公开桶①） | **保留**（随帖子匿名化保留） | D1/B5 | OSS key 仍存在；Feed 仍可见 | ☐ |
| H5 宠物名片 token | **立即失效** | F14/AC4 | 访问 `/card/<token>` → 404 `gone()` | ☐ |
| 腾讯 IM 聊天媒体 | **删除或 TTL 清理（二选一，dev 已落实记录）** | D2/B5c | stub 调用记录 or TTL 周期文档 | ☐ |
| Redis 在线态/角标/队列键 | **物理删除** | D1/B3 | `KEYS *<userId>* → 0` | ☐ |
| `account_deletions` 状态 | **DONE**（合规留证，仅代理 id+时间，无 PII） | B3/B4 | `status='DONE'`, `user_id=<代理 id>` | ☐ |

---

## 7.4 全局无障碍验收

### TC-7.54 对比度守门测试 — 正文/图标 ≥4.5:1

- **关联**：Story 7.4 · AC1 · NFR-13
- **页面/入口**：`lib/core/theme/colors.dart` · `test/a11y/contrast_test.dart`
- **前置**：代码最新
- **步骤**：
  1. 执行 `contrast_test.dart`（WCAG 2.1 相对亮度 × #FAF8F5 底色）
- **预期**：`textPrimary`/`textSecondary` 对 #FAF8F5 对比度 ≥4.5:1；`textDisclaimer` ≥3:1；`triageRed` ≥3:1；所有断言绿
- **层级**：L0

---

### TC-7.55 对比度守门测试 — disclaimer ≥3:1（即使 10px）

- **关联**：Story 7.4 · AC1 · NFR-13（PDP 合规）
- **页面/入口**：`core/theme/colors.dart`（`textDisclaimer` token）
- **前置**：代码最新
- **步骤**：
  1. 查看 `contrast_test.dart` 中 `textDisclaimer` 断言
  2. 手动在真机上浏览免责声明文本（如前置同意页 FR-0D）
- **预期**：`textDisclaimer` 对 #FAF8F5 ≥3:1；真机免责声明文字清晰可读；不模糊不浅白
- **层级**：L0 + L2（真机目测）

---

### TC-7.56 触摸目标 ≥44×44pt — 「我的」页面关键按钮

- **关联**：Story 7.4 · AC1 · NFR-13
- **页面/入口**：`/me`、`/me/settings` · `me_page.dart`、`settings_page.dart`
- **前置**：真机
- **步骤**：
  1. 在真机上依次点击：「Edit」胶囊、「Change status / Ubah status」、「Log out / Keluar」列表项、「Delete account / Hapus akun」列表项、双重确认弹窗中的「Continue」和「Delete permanently」按钮
  2. 用辅助工具或目测验证命中区
- **预期**：所有可交互元素命中区 ≥44×44pt；在紧凑布局下不因视觉区域小而命中区不足（使用 `Padding`/`MaterialTapTargetSize` 扩大）；注销高危按钮命中区足够（避免误触相邻取消按钮）
- **层级**：L0（widget test 命中区）+ L2（真机触摸测试）

---

### TC-7.57 ⚠️安全 三色态宠物状态 — 非颜色单一依赖

- **关联**：Story 7.4 · AC1 · NFR-13（安全攸关）
- **页面/入口**：分诊结果页（triage result）、`_PetStatusChip`
- **前置**：种植含绿/黄/红三种分诊等级的测试用例；真机
- **步骤**：
  1. 触发绿/黄/红分诊结果，观察结果卡渲染
  2. 将屏幕调为灰度模式（无障碍设置）
  3. 观察宠物状态徽章
- **预期**：绿/黄/红三色态每种均有 **icon + text + color** 三重编码；灰度模式下仍可区分三种等级；不存在仅靠颜色区分的情况；`_PetStatusChip` 含 🐾 emoji（属 icon 辅助）
- **层级**：L0（widget test）+ L2（真机灰度）

---

### TC-7.58 ⚠️安全 红色预警 assertive 播报语义标注存在

- **关联**：Story 7.4 · AC2 · NFR-13（安全攸关）
- **页面/入口**：`shared/widgets/red_alert_overlay` · widget
- **前置**：代码最新
- **步骤**：
  1. 在 `red_alert_overlay_test.dart` 中验证语义树
  2. 检查 `Semantics(liveRegion: true)` 或 `alertdialog` role 标注
- **预期**：红色 Alert 组件 widget 树中存在 `Semantics(liveRegion: true)` 和 `alertdialog` role；配 ⚠️ 图标 + 大字；零变现入口（无兽医/变现相关可聚焦语义元素）；既有测试绿
- **层级**：L0

---

### TC-7.59 ⚠️安全 红色预警 TalkBack/VoiceOver 断言式播报

- **关联**：Story 7.4 · AC2 · NFR-13（安全攸关，L2）
- **页面/入口**：分诊结果页红色半屏 Alert · 真机
- **前置**：Android 开启 TalkBack；iOS 开启 VoiceOver；种植红色分诊结果
- **步骤**：
  1. 启动屏幕阅读器
  2. 触发红色分诊等级结果
  3. 观察播报行为
- **预期**：红色预警弹出时**立即断言式（assertive）播报**，**打断**当前正在朗读的内容；用户无需手动聚焦到 Alert 即能获知预警；播报内容无变现引导；TalkBack 和 VoiceOver 均验证
- **层级**：L2

---

### TC-7.60 动态字体 ≤1.3x 封顶测试

- **关联**：Story 7.4 · AC3 · NFR-13
- **页面/入口**：`app.dart`（全局 `textScaler` clamp）· `test/a11y/text_scale_clamp_test.dart`
- **前置**：代码最新
- **步骤**：
  1. 执行 `text_scale_clamp_test.dart`（系统 3.0 缩放 → 封顶 1.3 断言）
- **预期**：`TailTopiaApp.maxTextScale = 1.3`；3.0 缩放输入被 clamp 为 1.3；正常输入（1.0）不被放大；测试全绿
- **层级**：L0

---

### TC-7.61 动态字体真机 — 放最大布局不破

- **关联**：Story 7.4 · AC3
- **页面/入口**：全 App（重点：「我的」页、设置页、分诊结果页）
- **前置**：真机系统字体调到最大（辅助功能）
- **步骤**：
  1. 系统字体调最大
  2. 遍历「我的」页面、设置页、注销确认弹窗、语言设置页
  3. 检查布局
- **预期**：body 字号放大（≤1.3x）；标题字号封顶不破布局；关键信息不被截断（含印尼语长文案）；不出现文字溢出/覆盖/截断
- **层级**：L2

---

### TC-7.62 portrait-only 锁定 + 仅浅色主题

- **关联**：Story 7.4 · AC3 · NFR-14
- **页面/入口**：`main.dart` · `app.dart`
- **前置**：真机
- **步骤**：
  1. 将设备旋转为横屏
  2. 将系统主题切换为深色模式
- **预期**：横屏被锁定，App 保持竖屏；深色模式不触发（App 仍浅色主题 #FAF8F5 底色）；无横屏布局代码分支被触发
- **层级**：L2

---

### TC-7.63 图标按钮语义标签可读

- **关联**：Story 7.4 · AC1、AC2 · NFR-13
- **页面/入口**：全 App（重点：「我的」页顶栏图标按钮、设置页列表项）
- **前置**：真机 TalkBack/VoiceOver 开启
- **步骤**：
  1. 用屏幕阅读器聚焦「帮助反馈」图标（`help_outline`）
  2. 聚焦「设置」图标（`settings_outlined`）
  3. 遍历设置页三个列表项（语言/退出/注销）
- **预期**：每个图标按钮被屏幕阅读器播报的内容与 tooltip 一致（「Help & feedback」「Settings」）；设置页列表项有明确可读标签；装饰性图标 `excludeSemantics`，不被无意义播报
- **层级**：L2

---

### TC-7.64 横切无障碍 checklist — 各 Epic 已实现页面

- **关联**：Story 7.4 · AC1–AC3 · NFR-13
- **页面/入口**：`features/{auth,triage,consult,content,profile,notify,me}` 所有已实现页面
- **前置**：真机 TalkBack/VoiceOver + 系统字体最大
- **步骤**：
  1. 按无障碍门槛清单逐屏过：对比度 ≥4.5:1 ✓、触摸目标 ≥44pt ✓、三色态非颜色单一 ✓、语义标签完整 ✓、动态字体不破布局 ✓
  2. 记录每屏达标/修复项
- **预期**：全部已实现界面满足上述 5 项门槛；不合格项作为 Bug 记录，修复后回归
- **层级**：L1（真实数据）+ L2（真机读屏）

---

## 本章遗留/盲区

1. **腾讯 IM 聊天媒体 L2 验收方案未定**（D2）：Story 7.3 B5c 记录了 `TencentImClient.deleteUserConversationMedia`（stub），但真实腾讯 IM 媒体删除 API 是否可用、TTL 周期是否已向腾讯确认，均需 dev 在 Completion Notes 落实。若依赖 TTL，须提供 TTL 值和官方文档引用；TC-7.47 L2 步骤目前无法完整执行。

2. **注销确认短语国际化（id 语言下是「HAPUS」而非「DELETE」）**：`deleteAccountConfirmPhrase` 两套 ARB 不同，前端按当前语言显示对应短语，后端接受哪套短语需明确——若后端只接受「DELETE」，则印尼语用户体验矛盾；若后端按语言接受，需接口文档说明。TC-7.37 应在 en 和 id 两种语言下分别验证。

3. **注销状态机重扫频率与超时策略**：Story 7.3 描述了启动重扫续跑残留（FAILED 重跑），但重扫触发时机（定时 `@Scheduled`？启动扫描？）、最大 retry_count 阈值、超过阈值后运营介入流程均未在 story 中明确。TC-7.49 L1 只能验基本幂等，高频/超限重试场景无测试覆盖。

4. **UGC 匿名化自动触发时机与原子性保证**：`AccountQueryService.findAuthorViews` 对缺失 author 自动返回 `anonymized`，依赖 `users` 行被删的时机——`content_posts` 不改行而是靠 join 缺失判断，存在级联删除 `users` 行 vs Feed API 响应的时序窗口（极短时间内可能出现 404 而非匿名化）。无针对该时序竞争的测试用例。

5. **语言设置「跟随系统」状态下切换系统语言的实时响应**：当用户选择「Follow system / Ikuti sistem」后，在 App 运行中切换系统语言，App 是否即时响应（或需重启）未被 TC 覆盖。`localeResolutionCallback` 只在启动时或 locale 变化事件时触发，Android 与 iOS 行为可能不同，属 L2 盲区。
