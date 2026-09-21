# Epic 1：应用地基与账户接入 — 人工测试用例集

> **范围**：双产物脚手架可运行骨架 · 设计 token + Tab Bar 外壳 + i18n · Google 登录 + JWT · 登录引导浮层与回跳 · 游客只读与受控入口门控 · 新用户引导（昵称 + 宠物状态）· 状态 A 建档引导与首页提示条。
>
> **测试环境说明**：L0=flutter analyze / flutter test / mvn compile|package（无 DB）；L1=需本地 Docker postgres+redis + mvn spring-boot:run；L2=需真实 Google OAuth 凭证 + 真机/模拟器。
>
> **重要约定**：App **绝不渲染中文**（所有 UI 文案走 .arb en/id 双语）。

## 涉及页面与路由总表

| 页面 | 路由 | 说明 |
|---|---|---|
| 首页（空容器/Feed） | `/home` | 游客可访问，Epic 3 填充真实 Feed |
| 成长档案（Tab） | `/profile` | 受控，需登录 |
| 问诊（Tab） | `/triage` | 受控，需登录 |
| 我的（Tab） | `/me` | 受控，需登录 |
| 登录入口页 | `/login` | `login_page.dart` |
| 开发者登录引导自测 | `/dev/login-guide` | `dev_login_guide_page.dart` |
| 新用户引导（薄荷绿换肤主流程） | `/onboarding` | `mint_onboarding_page.dart`，3 步：欢迎→创建宠物→完成 |
| 昵称确认 | `/onboarding/nickname` | `nickname_page.dart` |
| 宠物状态选择 | `/onboarding/pet-status` | `pet_status_page.dart` |
| 档案创建引导 | `/onboarding/profile` | `profile_onboarding_page.dart` |
| 建档庆祝页 | `/profile/created` | `profile_created_celebration_page.dart`，via extra |
| 首页提示条（组件） | 内嵌于 `/home` | `profile_prompt_bar.dart` |
| Shell（Tab Bar 外壳） | `AppShell` + `StatefulShellRoute` | `app_shell.dart`，承载 4 个 Tab |

---

## 1.1 双产物脚手架与本地可运行骨架

### TC-1.1.1 后端 Maven 编译与打包通过

- **关联**：Story 1.1 · AC1 · AC3
- **页面/入口**：后端命令行 · `petgo-backend/`
- **前置**：Java 21、Maven 3.9、网络可访问 Maven Central（`petgo-backend/.mvn/settings.xml` 直连 Central）
- **步骤**：
  1. 进入 `petgo-backend/` 目录
  2. 运行 `./mvnw -B compile`
  3. 运行 `./mvnw -B package -DskipTests`
- **预期**：
  - `compile` 阶段 BUILD SUCCESS，零编译错误
  - `package` 阶段 BUILD SUCCESS，生成 `target/petgo-backend-*.jar`（约 80MB+）
  - 无 `WARN` 级依赖解析失败
- **层级**：L0

---

### TC-1.1.2 后端 Actuator 健康检查 + Flyway 迁移

- **关联**：Story 1.1 · AC2
- **页面/入口**：`GET /actuator/health` · `GET /v3/api-docs`
- **前置**：Docker daemon 已启动；`petgo-backend/` 目录下有 `.env`（参照 `.env.example` 填写 DB_*/REDIS_* 本地值）
- **步骤**：
  1. `docker compose up -d` 启动 postgres + redis
  2. 等待 postgres 健康（`docker compose ps` 确认 healthy）
  3. `./mvnw spring-boot:run` 启动后端
  4. 观察启动日志中 Flyway 输出
  5. `curl -s localhost:8080/actuator/health | jq .`
  6. `curl -s localhost:8080/v3/api-docs | jq .openapi`
- **预期**：
  - 启动日志含 `Successfully applied 1 migration` 及 `V1__baseline`（schema_meta 行插入）
  - `/actuator/health` 返回 `{"status":"UP"}` 且含 `db.status=UP`、`redis.status=UP`
  - `/v3/api-docs` 返回 JSON，`openapi` 字段为 `"3.1.0"`
- **层级**：L1

---

### TC-1.1.3 ProblemDetail RFC 9457 信封验证

- **关联**：Story 1.1 · AC2（B6）
- **页面/入口**：`GET /api/v1/_ping-error`（dev profile 专用端点）
- **前置**：后端以 dev profile 运行（L1 环境就绪）
- **步骤**：
  1. `curl -s localhost:8080/api/v1/_ping-error | jq .`
- **预期**：
  - HTTP 422（或 400）
  - Content-Type: `application/problem+json`
  - 响应 JSON 含字段：`type`、`title`、`status`、`detail`、`instance`、`traceId`
  - **无** `stackTrace` / `exception` / `cause` 字段（不外泄堆栈）
- **层级**：L1（`GlobalExceptionHandlerTest` MockMvc 测试可在 L0 验证信封结构）

---

### TC-1.1.4 前端 Flutter 静态分析零警告

- **关联**：Story 1.1 · AC1 · AC3（F4）
- **页面/入口**：`petgo_app/` 命令行
- **前置**：Flutter 3.44.x SDK、`flutter pub get` 已完成
- **步骤**：
  1. 进入 `petgo_app/`
  2. `flutter pub get`
  3. `flutter analyze`
  4. `flutter test`
- **预期**：
  - `flutter analyze` 输出 `No issues found!`（零警告、零错误）
  - `flutter test` 全部通过（当前 252+ 例），无 FAILED
- **层级**：L0

---

### TC-1.1.5 前端 feature-first 目录骨架完整性

- **关联**：Story 1.1 · AC1（F2）
- **页面/入口**：文件系统检查
- **前置**：仓库已 clone，Story 1.1 已实现
- **步骤**：
  1. 检查 `petgo_app/lib/core/{network,router,theme,l10n,storage}` 目录是否存在
  2. 检查 `petgo_app/lib/features/{auth,triage,consult,content,profile,notify,me,vet}/{data,domain,presentation}` 是否存在
  3. 检查 `petgo_app/lib/shared/{widgets,utils}` 是否存在
- **预期**：
  - 上述所有目录均存在（可为空目录或含占位文件）
  - 无 `moderation` feature 目录（该功能属后端 admin slice，前端无对应 feature）
- **层级**：L0

---

## 1.2 设计 token 系统、Tab Bar 外壳与 i18n 脚手架

### TC-1.2.1 全局底色恒为 #FAF8F5（薄荷绿换肤后实际 token 值）

- **关联**：Story 1.2 · AC1 · UX-DR1
- **页面/入口**：所有 Tab（`/home` `/profile` `/triage` `/me`）
- **前置**：模拟器已启动，App 已运行（`flutter run`）；`AppColors.base` 在 `colors.dart` 定义
- **步骤**：
  1. 启动 App，默认落在首页 `/home`
  2. 依次点击 Tab：成长档案、问诊、我的、首页
  3. 观察每个 Tab 的页面背景色
- **预期**：
  - 所有 Tab 的 `Scaffold` 背景色**不变**（恒为 `AppColors.base`，实际值参见 `petgo_app/lib/core/theme/colors.dart`；薄荷绿换肤后该值已更新，非原始 `#FAF8F5`，以代码为准）
  - 切换 Tab 时**整屏背景不闪变色**
  - **不出现蓝色（Flutter 默认主题色）**
- **层级**：L2（L0：`widget test` 断言 `scaffoldBackgroundColor == AppColors.base`，文件 `test/widget_test.dart`）

---

### TC-1.2.2 底部 Tab Bar 凸起「＋」与弧形缺口结构

- **关联**：Story 1.2 · AC2 · UX-DR2
- **页面/入口**：`AppShell`（所有 Tab 均可见 Tab Bar）
- **前置**：L2 模拟器
- **步骤**：
  1. 启动 App
  2. 观察底部 Tab Bar 整体外观
  3. 检查「＋」按钮位置与视觉效果
- **预期**：
  - Tab Bar 显示 5 个位置：首页 / 成长档案 / [＋] / 问诊 / 我的
  - 中间「＋」按钮**高于** Tab Bar 上沿约 20px（凸起悬浮效果）
  - 「＋」按钮为约 44px 圆形，有 3px 白色描边
  - Tab Bar 上沿在「＋」位置向下内凹（弧形缺口或平直顶边 + centerDocked 半埋，以换肤实现为准）
- **层级**：L2（L0：widget test 文件 `test/widget_test.dart` 断言 5 个 Tab 渲染 + 凸起按钮存在）

---

### TC-1.2.3 active Tab 区域色填充圆 + 120ms 淡入切换

- **关联**：Story 1.2 · AC2 · UX-DR2 · UX-DR13
- **页面/入口**：底部 Tab Bar
- **前置**：L2 模拟器
- **步骤**：
  1. 观察当前 active Tab（首页）的图标样式
  2. 点击「成长档案」Tab，观察切换动效与激活样式
  3. 依次点击问诊、我的、首页
- **预期**：
  - Active Tab 显示约 34×34 区域色填充圆 + 白色图标
  - Inactive Tab 显示约 18px 图标 + 9px 标签文字
  - Tab 内容区切换有明显淡入淡出（约 120ms），不是瞬切
  - Active Tab circle 有轻微 scale 入场动效（0.7→1.0）
- **层级**：L2

---

### TC-1.2.4 Tab 标签 i18n — en 语言

- **关联**：Story 1.2 · AC3 · NFR-11
- **页面/入口**：底部 Tab Bar
- **前置**：设备/模拟器语言设为 **英语（en）**
- **步骤**：
  1. 启动 App（或切换系统语言为 English 后重启 App）
  2. 观察底部 4 个 Tab 的标签文字（「＋」无文字）
- **预期**：
  - 首页 Tab 显示：**Home**
  - 成长档案 Tab 显示：**Growth**
  - 问诊 Tab 显示：**Consult**
  - 我的 Tab 显示：**Me**
  - **无任何中文或印尼语文字**
- **层级**：L2（L0：widget test 注入 `locale: Locale('en')` 断言文案）

---

### TC-1.2.5 Tab 标签 i18n — id 语言

- **关联**：Story 1.2 · AC3 · NFR-11
- **页面/入口**：底部 Tab Bar
- **前置**：设备/模拟器语言设为 **印尼语（id）**
- **步骤**：
  1. 切换系统语言为 Bahasa Indonesia，重启 App
  2. 观察底部 4 个 Tab 的标签文字
- **预期**：
  - 首页 Tab 显示：**Beranda**
  - 成长档案 Tab 显示：**Tumbuh**
  - 问诊 Tab 显示：**Konsultasi**
  - 我的 Tab 显示：**Saya**
  - **无任何中文文字**
- **层级**：L2

---

### TC-1.2.6 非 id/en 语言回退英语

- **关联**：Story 1.2 · AC3
- **页面/入口**：底部 Tab Bar
- **前置**：设备语言设为非 id/en 语言（如日语 ja、中文 zh）
- **步骤**：
  1. 切换系统语言为日语或中文
  2. 重启 App
  3. 观察 Tab 标签文字
- **预期**：
  - Tab 标签显示英语（Home / Growth / Consult / Me）
  - **不显示中文字符**
- **层级**：L2

---

### TC-1.2.7 Tab Bar 空白占位页不崩溃

- **关联**：Story 1.2 · AC2（F5）
- **页面/入口**：`/profile` `/triage` `/me`
- **前置**：已登录账号（或通过 mock 模式绕过门控）
- **步骤**：
  1. 依次点击成长档案、问诊、我的 Tab
  2. 观察是否崩溃或白屏
- **预期**：
  - 各 Tab 正常渲染（不崩溃、不白屏）
  - 显示占位文案（「Coming soon」或对应语言占位）
- **层级**：L2

---

## 1.3 Google 登录与 JWT 签发

### TC-1.3.1 登录入口页渲染（en）

- **关联**：Story 1.3 · AC1 · AC2 · FR-0D
- **页面/入口**：`/login` · `login_page.dart`
- **前置**：locale=en；以游客态进入（或直接导航 `/login`）
- **步骤**：
  1. 导航至 `/login`
  2. 观察页面布局
- **预期**：
  - 页面背景为 `AppColors.base`（`#FAF8F5` 级别全局底色）
  - 显示 App 标题「TailTopia」
  - 显示主按钮：**「Sign in with Google」**（`key=googleLoginButton`，实心按钮，区域色）
  - 按钮下方显示协议文本：「By signing in you agree to the **Terms of Service** and **Privacy Policy**」
  - 「Terms of Service」和「Privacy Policy」为**可点击链接**（`key=termsLink`、`key=privacyLink`）
  - **无勾选框（checkbox）**
- **层级**：L0（widget test 可验；L2：模拟器视觉确认）

---

### TC-1.3.2 登录入口页渲染（id）

- **关联**：Story 1.3 · AC2 · FR-0D
- **页面/入口**：`/login` · `login_page.dart`
- **前置**：locale=id；以游客态进入
- **步骤**：
  1. 切换系统语言为印尼语，导航至 `/login`
  2. 观察按钮与协议文案
- **预期**：
  - 主按钮显示：**「Masuk dengan Google」**
  - 协议文本前缀：「Dengan masuk, Anda menyetujui 」
  - 链接：「**Ketentuan Layanan**」和「**Kebijakan Privasi**」
  - **无勾选框**
  - **无中文字符**
- **层级**：L0（widget test）+ L2

---

### TC-1.3.3 服务条款 / 隐私政策链接跳转外部

- **关联**：Story 1.3 · AC2 · FR-0D
- **页面/入口**：`/login` · `_AgreementLinks`
- **前置**：L2 真机/模拟器，有外部浏览器可启动
- **步骤**：
  1. 进入登录页
  2. 点击「Terms of Service」链接
  3. 返回，点击「Privacy Policy」链接
- **预期**：
  - 两个链接均调用 `launchUrl`，打开外部浏览器或 WebView
  - URL 为 env 注入的占位 URL（默认 `https://petgo.example/terms` / `https://petgo.example/privacy`）
  - **不导航到 App 内部页面**
- **层级**：L2

---

### TC-1.3.4 Google 登录成功 — 老用户进首页

- **关联**：Story 1.3 · AC1 · AC4
- **页面/入口**：`/login` → `/home`
- **前置**：L2，已有真实 Google 账号 + 已完成 onboarding（`onboardingCompleted=true`）；后端运行
- **步骤**：
  1. 以游客态打开 App
  2. 导航至 `/login`，点击「Sign in with Google」
  3. 在系统账号选择器中完成 Google 授权
  4. 等待登录完成
- **预期**：
  - 账号选择器弹出，用户完成选择
  - 登录成功后路由跳转至 `/home`（Tab Bar 首页）
  - **不进入 `/onboarding` 引导流程**
  - `flutter_secure_storage` 中存有 access token 和 refresh token
- **层级**：L2

---

### TC-1.3.5 Google 登录成功 — 新用户进引导

- **关联**：Story 1.3 · AC1 · AC4
- **页面/入口**：`/login` → `/onboarding`（或 `/onboarding/nickname`）
- **前置**：L2，首次使用的全新 Google 账号（后端无对应 `users` 行）；后端运行
- **步骤**：
  1. 使用全新 Google 账号登录
  2. 完成账号选择器授权
- **预期**：
  - 登录成功后路由跳转至 `/onboarding`（薄荷绿换肤主流程）或 `/onboarding/nickname`（旧分步流程，取决于路由配置）
  - **不跳转到 `/home`**
  - 后端 `users` 表新增一行，`isNewUser=true`、`onboardingCompleted=false`
- **层级**：L2

---

### TC-1.3.6 Google 登录 — 用户取消账号选择器

- **关联**：Story 1.3 · AC5（R2）· FR-0D · 决策 F13
- **页面/入口**：`/login` · `login_page.dart`
- **前置**：L2 真机/模拟器；locale=en
- **步骤**：
  1. 进入登录页
  2. 点击「Sign in with Google」
  3. 在系统账号选择器中**取消**（点取消/返回）
- **预期**：
  - 页面显示 SnackBar：「Sign-in cancelled」（`loginCancelled`，en）
  - **不跳转页面**，停留在 `/login`
  - **未创建账号**（后端无新 `users` 行）
  - 「Sign in with Google」按钮恢复可点击状态（可重试）
- **层级**：L2（L0：`login_page_test.dart` 注入 `LoginCancelled` → 断言 `loginCancelled` banner + 仍游客 + 按钮可点）

---

### TC-1.3.7 Google 登录 — 网络失败/Google 服务异常

- **关联**：Story 1.3 · AC5（R2）· 决策 F13
- **页面/入口**：`/login` · `login_page.dart`
- **前置**：L2 真机，或 L0（inject fake repo 抛异常）；locale=id
- **步骤**：
  1. 模拟网络断开或 Google 服务失败（或 L0：fake repo 抛异常）
  2. 点击「Masuk dengan Google」
  3. 等待失败响应
- **预期**：
  - 页面显示 SnackBar：「Gagal masuk, silakan coba lagi」（`loginFailed`，id）
  - **不跳转页面**，停留在登录页
  - **未创建账号**（后端 `users` 行数不变）
  - 登录按钮恢复可点击（支持重试）
- **层级**：L0（fake repo 注入）+ L2（真机断网）

---

### TC-1.3.8 Google ID Token 校验失败 — 后端不建账号

- **关联**：Story 1.3 · AC5（R2）· B9
- **页面/入口**：`POST /api/v1/auth/google`
- **前置**：L0，stub GoogleTokenVerifier 抛 `AppException`
- **步骤**：
  1. 运行 `AuthServiceTest.googleVerifyFailureCreatesNoAccount`
- **预期**：
  - 测试通过：`userRepository.save` 零调用、`refreshTokenRepository.save` 零调用
  - `AuthService` 抛出 `AppException`
  - HTTP 层返回 401 或 422 ProblemDetail
- **层级**：L0

---

### TC-1.3.9 access token 过期 — dio 拦截器静默续期一次

- **关联**：Story 1.3 · AC3 · NFR-12
- **页面/入口**：`core/network/auth_interceptor.dart`
- **前置**：L0，`auth_interceptor_test.dart`
- **步骤**：
  1. 运行 `auth_interceptor_test.dart` 全套
- **预期**：
  - 401 → 调 refresh → 重放原请求成功：测试通过
  - refresh 也失败 → 清 token、落游客态：测试通过
  - **只续期一次**（不死循环）：`retried` 标志单测通过
- **层级**：L0

---

### TC-1.3.10 refresh token 轮换 — 旧 refresh 重放被拒

- **关联**：Story 1.3 · AC3
- **页面/入口**：`POST /api/v1/auth/refresh`
- **前置**：L1，Docker pg+redis 运行，后端启动
- **步骤**：
  1. 通过 stub 登录拿到 `refreshToken A`
  2. 调 `POST /api/v1/auth/refresh`（body: `{refreshToken: A}`）取新 refresh `B`
  3. 再次调 `POST /api/v1/auth/refresh`（body: `{refreshToken: A}`，重放旧 refresh）
- **预期**：
  - 第一次 refresh：HTTP 200，返回新 `accessToken` 和新 `refreshToken B`
  - 第二次 refresh（重放旧 A）：HTTP 401 ProblemDetail（旧 refresh 已失效）
- **层级**：L1

---

### TC-1.3.11 登录限流 — 超阈值返回 429

- **关联**：Story 1.3 · AC1（B7）· NFR-12
- **页面/入口**：`POST /api/v1/auth/google`
- **前置**：L1，后端运行（redis 限流配置：10次/min）
- **步骤**：
  1. 连续快速调用 `POST /api/v1/auth/google` 超过 10 次（用 stub idToken）
- **预期**：
  - 超出阈值后返回 HTTP 429 ProblemDetail
  - `type/title/status/detail/traceId` 字段齐备
  - **无堆栈外泄**
- **层级**：L1

---

### TC-1.3.12 新老用户分流信号 — MockMvc 验证

- **关联**：Story 1.3 · AC4
- **页面/入口**：`POST /api/v1/auth/google`
- **前置**：L0，`AuthServiceTest`（Mockito，无 DB）
- **步骤**：
  1. 运行 `AuthServiceTest` 全套（8 例）
- **预期**：
  - 首次建号：`isNewUser=true`、`onboardingCompleted=false`
  - 二次登录（同 googleSub）：`isNewUser=false`
  - 8/8 全通过
- **层级**：L0

---

## 1.4 登录引导浮层与登录后回跳

### TC-1.4.1 软性浮层（FR-0B）基本结构与文案（en）

- **关联**：Story 1.4 · AC1 · FR-0B
- **页面/入口**：`shared/widgets/login_soft_sheet.dart`
- **前置**：locale=en；`login_guide_test.dart` 或模拟器手动触发 `/dev/login-guide`
- **步骤**：
  1. L0：运行 `login_guide_test.dart` 中软浮层结构测试
  2. L2：导航到 `/dev/login-guide`，点击「Show Soft Sheet」按钮
- **预期**：
  - 软浮层从底部上滑弹出（bottom sheet 样式）
  - 包含主 CTA「Sign in with Google」（显著，实心大按钮）
  - 包含关闭按钮（小号浅色，弱化视觉层级）
  - 标题/副文显示 i18n 文案（en：`loginGuideSoftTitle` = 「Sign in for the full experience」）
  - **无勾选框**
- **层级**：L0（`login_guide_test.dart`）+ L2

---

### TC-1.4.2 软性浮层 i18n（id）

- **关联**：Story 1.4 · AC1
- **页面/入口**：`login_soft_sheet.dart`
- **前置**：locale=id
- **步骤**：
  1. 以印尼语 locale 触发软浮层
  2. 观察所有文案
- **预期**：
  - 所有文案显示印尼语
  - **无中文字符**
  - 「Close」→「Tutup」，Google 登录按钮→「Masuk dengan Google」
- **层级**：L0（widget test 注入 locale=id）

---

### TC-1.4.3 软浮层每 session 最多触发一次（去重）

- **关联**：Story 1.4 · AC1 · FR-0B
- **页面/入口**：`features/auth/domain/login_guide_controller.dart`
- **前置**：L0，`login_guide_test.dart`
- **步骤**：
  1. 运行「软浮层第二次调用不弹」测试
- **预期**：
  - 第一次 `showSoftSheet` → 浮层弹出
  - 第二次 `showSoftSheet`（同 session）→ **no-op，浮层不再弹出**
  - 测试通过
- **层级**：L0

---

### TC-1.4.4 强登录引导弹窗（FR-0C）基本结构（en）

- **关联**：Story 1.4 · AC1 · FR-0C
- **页面/入口**：`shared/widgets/login_hard_dialog.dart`
- **前置**：locale=en；`login_guide_test.dart`
- **步骤**：
  1. 运行强弹窗结构测试
  2. L2：导航 `/dev/login-guide`，触发「Show Hard Dialog」
- **预期**：
  - 居中 modal 弹出（遮罩更重，视觉强度高于软浮层）
  - 含说明文案：「Sign in to continue using this feature」（`loginGuideHardMessage`）
  - 含主 CTA「Sign in with Google」
  - 含关闭按钮
- **层级**：L0 + L2

---

### TC-1.4.5 强弹窗连续触发不去重

- **关联**：Story 1.4 · AC1 · FR-0C
- **页面/入口**：`login_guide_controller.dart`
- **前置**：L0
- **步骤**：
  1. 运行「强弹窗连续触发每次都弹」测试
- **预期**：
  - 连续多次调用 `showHardDialog` → 每次均弹出（**不去重**）
  - 测试通过（与软浮层 session 去重行为明显区分）
- **层级**：L0

---

### TC-1.4.6 老用户登录成功 → pendingAction 回跳触发点

- **关联**：Story 1.4 · AC2 · FR-0B/0C
- **页面/入口**：`login_guide_controller.dart` + 回跳机制
- **前置**：L0，`login_guide_test.dart`
- **步骤**：
  1. 注入 `pendingAction`（如「打开问诊」路由）
  2. 触发强弹窗
  3. mock 老用户登录成功（`onboardingCompleted=true`）
  4. 验收后置行为
- **预期**：
  - 引导关闭
  - `pendingAction` 被执行（路由到问诊目标）
  - 测试通过
- **层级**：L0

---

### TC-1.4.7 新用户登录成功 → 先引导后回跳

- **关联**：Story 1.4 · AC2
- **页面/入口**：`login_guide_controller.dart`
- **前置**：L0，`login_guide_test.dart`
- **步骤**：
  1. 注入 `pendingAction`「打开问诊」
  2. 触发引导
  3. mock 新用户（`isNewUser=true`/`onboardingCompleted=false`）登录成功
- **预期**：
  - 路由至 `/onboarding`（引导占位）
  - `pendingAction` **保留未执行**
  - 引导完成后调 `resumePendingAfterOnboarding` → 执行 pendingAction（路由问诊）
  - 测试通过
- **层级**：L0

---

### TC-1.4.8 主动关闭引导 → pendingAction 清空

- **关联**：Story 1.4 · AC2
- **页面/入口**：`login_guide_controller.dart`
- **前置**：L0
- **步骤**：
  1. 注入 pendingAction，触发引导
  2. 用户点关闭按钮（未登录）
- **预期**：
  - 引导关闭
  - `pendingAction` 被清空
  - 停留原页，未路由到任何目标
  - 测试通过
- **层级**：L0

---

### TC-1.4.9 授权失败 → pendingAction 保留 + 失败态展示（AC3 / R2）

- **关联**：Story 1.4 · AC3（R2）· 决策 F13
- **页面/入口**：`login_guide_controller.dart` + `login_soft_sheet.dart` / `login_hard_dialog.dart`
- **前置**：L0，`login_guide_test.dart`（+4 例 R2）
- **步骤**：
  1. 注入 `pendingAction`「打开问诊」
  2. 触发强弹窗
  3. mock 登录回调**抛出异常**（网络失败/Google 异常）
- **预期**：
  - `pendingAction` **仍保留未清空**
  - **未路由到 `/onboarding`**（不进入注册引导）
  - 弹窗内显示「Sign-in failed, please try again」失败态横幅（`loginFailed` key）
  - 主 CTA「Sign in with Google」可再次点击（即重试入口）
  - 测试通过
- **层级**：L0

---

### TC-1.4.10 失败后重试成功 → 用保留的 pendingAction 回跳

- **关联**：Story 1.4 · AC3（R2）· 决策 F13
- **页面/入口**：`login_guide_controller.dart`
- **前置**：L0，`login_guide_test.dart`
- **步骤**：
  1. 按 TC-1.4.9 步骤触发失败
  2. 点击「Sign in with Google」重试
  3. mock 重试成功（老用户）
- **预期**：
  - 使用**保留的 pendingAction** 回跳（不丢失原意图）
  - 测试通过
- **层级**：L0

---

### TC-1.4.11 失败后关闭弹窗 → pendingAction 清空

- **关联**：Story 1.4 · AC3（R2）
- **页面/入口**：`login_guide_controller.dart`
- **前置**：L0
- **步骤**：
  1. 触发引导，mock 失败
  2. 点击关闭按钮
- **预期**：
  - `pendingAction` 被清空
  - 停留原页
  - 测试通过
- **层级**：L0

---

### TC-1.4.12 软浮层拖拽/点背景关闭手感（L2）

- **关联**：Story 1.4 · AC1 · UX-DR11/12
- **页面/入口**：`login_soft_sheet.dart`
- **前置**：L2 真机/模拟器
- **步骤**：
  1. 触发软浮层
  2. 下拉拖拽软浮层（尝试手势关闭）
  3. 点击浮层外背景遮罩区域
- **预期**：
  - 下拉拖拽可关闭浮层（可拖拽行为）
  - 点击背景遮罩可关闭浮层
  - 自底上滑入场动效约 300ms spring 感可感知
- **层级**：L2

---

## 1.5 游客只读进入与受控入口登录门控

### TC-1.5.1 游客进入首页 — 不崩溃，内容可见

- **关联**：Story 1.5 · AC1 · FR-0A
- **页面/入口**：`/home` · `home_page.dart`
- **前置**：游客态（未登录），locale=en；L2 或 mock 模式
- **步骤**：
  1. 以游客态启动 App（未登录）
  2. 默认落在首页 `/home`
  3. 尝试上下滚动
- **预期**：
  - 首页渲染成功，不崩溃
  - 显示可滚动容器 + 空状态占位（「Your feed is empty」+ 副文「Pet stories will appear here soon」或 id 对应文案）
  - **不强制跳转到登录页**
  - **不弹任何门控弹窗**
- **层级**：L0（widget test `story_1_5_gating_test.dart`）+ L2

---

### TC-1.5.2 游客首页空状态（id）

- **关联**：Story 1.5 · AC1 · UX-DR8
- **页面/入口**：`/home`
- **前置**：locale=id，游客态
- **步骤**：
  1. 以 locale=id 游客态进入首页
  2. 观察空状态文案
- **预期**：
  - 显示印尼语空状态文案（`homeEmptyTitle`/`homeEmptyBody` id 译文）
  - **无中文**
- **层级**：L2

---

### TC-1.5.3 游客点受控 Tab — 弹强弹窗，不切目的地

- **关联**：Story 1.5 · AC2 · FR-0C · FR-19
- **页面/入口**：Tab Bar → `/profile` `/triage` `/me`
- **前置**：游客态，`story_1_5_gating_test.dart`
- **步骤**：
  1. L0：运行 `story_1_5_gating_test.dart`「游客点问诊 Tab → 弹窗不切目的地」测试
  2. L2：游客态下分别点击「成长档案」「问诊」「我的」「＋」Tab
- **预期**：
  - 点击每个受控 Tab → **强登录引导弹窗**弹出
  - **当前 Tab 不切换**（仍停在首页）
  - 弹窗含说明「Sign in to continue using this feature」
  - 测试通过（4 个受控 Tab 均覆盖）
- **层级**：L0 + L2

---

### TC-1.5.4 游客深链受控路由 → redirect 回首页

- **关联**：Story 1.5 · AC2 · `app_router.dart` redirect 逻辑
- **页面/入口**：直接导航 `/profile`、`/triage`、`/me`
- **前置**：游客态
- **步骤**：
  1. 游客态下，直接调用 `context.go('/profile')`（或 `/triage`、`/me`）
- **预期**：
  - go_router redirect 拦截，路由到 `/home`
  - **不进入受控目的地**
- **层级**：L0（router provider 测试）

---

### TC-1.5.5 游客触发受控操作 → 门控 helper 弹强弹窗 + pendingAction 注入

- **关联**：Story 1.5 · AC2 · FR-0C
- **页面/入口**：`features/auth/domain/auth_guard.dart`（`requireLogin`）
- **前置**：L0，`story_1_5_gating_test.dart`
- **步骤**：
  1. mock 游客态
  2. 调用 `requireLogin(ref, context, pendingAction: RouteIntent('/triage'))`
- **预期**：
  - 强弹窗弹出
  - `pendingAction` 为 `/triage`（注入正确）
  - 不执行 pendingAction（未登录，直接拦截）
  - 测试通过
- **层级**：L0

---

### TC-1.5.6 已登录 — 受控 Tab/入口直接进入，不弹窗

- **关联**：Story 1.5 · AC2
- **页面/入口**：`requireLogin` + `AppShell`
- **前置**：L0，已登录态 mock
- **步骤**：
  1. mock 已登录态
  2. 调用 `requireLogin`（或点受控 Tab）
- **预期**：
  - 直接执行 onAllowed / 切换 Tab
  - **不弹任何引导弹窗**
  - 测试通过
- **层级**：L0

---

### TC-1.5.7 dio 拦截器 401（refresh 失败后）→ 弹引导流，不静默失败

- **关联**：Story 1.5 · AC2（F3）
- **页面/入口**：`core/network/auth_interceptor.dart` + `login_guide_controller.dart`
- **前置**：L0，`auth_interceptor_test.dart` + `story_1_5_gating_test.dart`
- **步骤**：
  1. 运行「401 refresh 失败 → 弹引导流」测试
- **预期**：
  - 401 → refresh 续期一次 → 续期失败 → 落游客态 → **弹强弹窗**（不静默吞掉）
  - 测试通过
- **层级**：L0

---

### TC-1.5.8 并发多个 401 → 只弹一个引导弹窗（单例守卫）

- **关联**：Story 1.5 · AC2（F3）· `_hardDialogShowing` 守卫
- **页面/入口**：`login_guide_controller.dart` 并发防抖
- **前置**：L0，`story_1_5_gating_test.dart`
- **步骤**：
  1. 运行「并发 401 不叠多窗」测试
- **预期**：
  - 同一时刻多个 401 → 只弹出**一个**强弹窗
  - 测试通过
- **层级**：L0

---

### TC-1.5.9 后端门控对称性 — 无 JWT 写端点 → 401

- **关联**：Story 1.5 · AC1（B1/B3）· NFR-12
- **页面/入口**：`POST /api/v1/_guarded-ping`（dev profile）
- **前置**：L1，Docker pg+redis + 后端运行（dev profile）
- **步骤**：
  1. 不带 Authorization header，调用 `POST /api/v1/_guarded-ping`
- **预期**：
  - HTTP 401
  - Content-Type: `application/problem+json`
  - ProblemDetail 含 `type/title/status=401/detail/instance/traceId`
  - **无堆栈**
- **层级**：L1

---

### TC-1.5.10 后端门控对称性 — 游客只读端点 → 200

- **关联**：Story 1.5 · AC1（B3）
- **页面/入口**：`GET /api/v1/public/_ping`（dev profile）
- **前置**：L1
- **步骤**：
  1. 不带任何认证头，调用 `GET /api/v1/public/_ping`
- **预期**：
  - HTTP 200
  - 响应 body 为占位内容
- **层级**：L1

---

### TC-1.5.11 401 与 403 语义不混用

- **关联**：Story 1.5 · AC1（B2）· `ProblemDetailAuthHandlersTest`
- **页面/入口**：后端 `shared/security/ProblemDetailAuthHandlers`
- **前置**：L0，`ProblemDetailAuthHandlersTest`（2 例）
- **步骤**：
  1. 运行 `ProblemDetailAuthHandlersTest` 全套
- **预期**：
  - 未认证（401）与越权（403）的 ProblemDetail 信封结构测试通过
  - **二者 `status` 字段不同（401 vs 403）**，不混用
  - 2/2 全通过
- **层级**：L0

---

### TC-1.5.12 门控登录成功后回到触发点（L2 完整链路）

- **关联**：Story 1.5 · AC2 · FR-0C 回跳
- **页面/入口**：Tab Bar 问诊 Tab → 强弹窗 → Google 登录 → `/triage`
- **前置**：L2，真实 Google 账号（老用户，`onboardingCompleted=true`）；后端运行
- **步骤**：
  1. 以游客态进入 App
  2. 点击「问诊」Tab（受控）
  3. 强弹窗弹出，点击「Sign in with Google」
  4. 完成 Google 授权
- **预期**：
  - 登录成功后**直接进入问诊 Tab**（回跳触发点，pendingAction 执行）
  - **不停留在首页**，不进入 `/onboarding`（老用户）
- **层级**：L2

---

## 1.6 新用户引导 — 昵称确认与宠物状态选择

### TC-1.6.1 昵称确认页渲染 + 默认填充 Google 名（en）

- **关联**：Story 1.6 · AC1 · FR-0E
- **页面/入口**：`/onboarding/nickname` · `nickname_page.dart`
- **前置**：新用户登录后，`authState` 携带 `displayName`；locale=en
- **步骤**：
  1. 以新用户身份进入昵称确认页（`/onboarding/nickname`）
  2. 观察 TextField 默认内容和字数计数
- **预期**：
  - AppBar 标题：「Confirm your nickname」
  - TextField（`key=nicknameField`）预填 Google `displayName`
  - 字数计数器显示：「{n}/20」（n = displayName 长度）
  - 「Continue」按钮（`key=nicknameContinue`）在字数 ≤20 时可点击
  - 背景色为 `AppColors.base`
- **层级**：L0（widget test）+ L2

---

### TC-1.6.2 昵称确认页渲染（id）

- **关联**：Story 1.6 · AC1
- **页面/入口**：`/onboarding/nickname`
- **前置**：locale=id
- **步骤**：
  1. 以 locale=id 进入昵称确认页
  2. 观察文案
- **预期**：
  - AppBar 标题：「Konfirmasi nama panggilan」
  - 标签：「Nama panggilan」
  - 继续按钮：「Lanjutkan」（或 .arb 对应值）
  - **无中文**
- **层级**：L0 + L2

---

### TC-1.6.3 昵称超过 20 字 → 禁止继续

- **关联**：Story 1.6 · AC1 · FR-0E（客户端预校验）
- **页面/入口**：`/onboarding/nickname`
- **前置**：L0（widget test）
- **步骤**：
  1. 在 TextField 输入 21 个字符（如 「abcdefghijklmnopqrstu」）
  2. 观察字数计数器和继续按钮状态
- **预期**：
  - 字数计数器显示：「21/20」
  - 「Continue」按钮 **disabled**（不可点击）
  - 可能显示错误提示（`errorText`）
  - 删减至 ≤20 字后继续按钮恢复可用
- **层级**：L0

---

### TC-1.6.4 昵称为空/纯空格 → 禁止继续

- **关联**：Story 1.6 · AC1（`_valid` 逻辑：`t.isNotEmpty && t.length <= 20`）
- **页面/入口**：`/onboarding/nickname`
- **前置**：L0
- **步骤**：
  1. 清空 TextField（或仅输入空格）
  2. 观察继续按钮状态
- **预期**：
  - 「Continue」按钮 disabled
- **层级**：L0

---

### TC-1.6.5 昵称过长 — 后端 422 校验

- **关联**：Story 1.6 · AC1（B1）· 服务端权威校验
- **页面/入口**：`PATCH /api/v1/me`
- **前置**：L1，后端运行
- **步骤**：
  1. 带有效 JWT，调 `PATCH /api/v1/me` body `{"nickname":"aaaaabbbbbcccccddddde"}` （21 字符）
- **预期**：
  - HTTP 422 ProblemDetail
  - `errors` 数组含 `{field:"nickname", message: ...}`
- **层级**：L1

---

### TC-1.6.6 昵称确认成功 → 进入宠物状态选择页

- **关联**：Story 1.6 · AC1
- **页面/入口**：`/onboarding/nickname` → `/onboarding/pet-status`
- **前置**：L2（需后端，或 mock 模式）；合法昵称
- **步骤**：
  1. 在昵称页输入合法昵称（≤20 字，非空）
  2. 点击「Continue」
  3. mock/真实后端 `PATCH /api/v1/me` 返回 200
- **预期**：
  - 路由跳转至 `/onboarding/pet-status`
  - 不显示任何错误
- **层级**：L0（mock PATCH）+ L2

---

### TC-1.6.7 昵称确认 — 写昵称网络失败，保留输入可重试

- **关联**：Story 1.6 · 页面多态（决策 F13）
- **页面/入口**：`/onboarding/nickname`
- **前置**：L0（mock repo 抛异常）
- **步骤**：
  1. mock `updateNickname` 抛网络异常
  2. 输入合法昵称，点击「Continue」
- **预期**：
  - 显示 SnackBar 错误提示（「Sign-in failed, please try again」或对应 `loginFailed` key）
  - **停留在昵称页，输入内容未丢失**
  - 「Continue」按钮恢复可用（可重试）
- **层级**：L0

---

### TC-1.6.8 昵称确认页返回键 → 退出登录流程，不建账号（AC4/R2）

- **关联**：Story 1.6 · AC4（R2）· FR-0E
- **页面/入口**：`/onboarding/nickname`（`PopScope canPop:false`）
- **前置**：L0，`onboarding_test.dart`
- **步骤**：
  1. 进入昵称确认页（`authState` 为 `newUserPendingOnboarding`）
  2. 触发设备返回键（`handlePopRoute` 模拟）
- **预期**：
  - `authState` 回到**游客态**（`toGuest()` 调用）
  - 路由跳转到 `/home`（未登录首页）
  - **未调用任何写账号端点**（`writeCalls` 为空）
  - 测试通过
- **层级**：L0

---

### TC-1.6.9 宠物状态选择页渲染 + 必选不可跳过（en）

- **关联**：Story 1.6 · AC2 · FR-0F
- **页面/入口**：`/onboarding/pet-status` · `pet_status_page.dart`
- **前置**：locale=en
- **步骤**：
  1. 进入宠物状态选择页
  2. 观察页面内容，不选任何选项
  3. 观察「Done」按钮状态
- **预期**：
  - AppBar 标题：「Tell us about your pet」
  - 副标题：「This helps us tailor your home feed」
  - 显示三个选项卡（`PetStatusSelector`）：
    - A：「I have a pet」（`petStatusA`）
    - B：「Not yet, but planning to」（`petStatusB`）
    - C：「Pet enthusiast」（`petStatusC`）
  - 「Done」按钮（`key=petStatusComplete`）**disabled**（未选）
  - **无「跳过」入口**
- **层级**：L0（widget test）+ L2

---

### TC-1.6.10 宠物状态选择页渲染（id）

- **关联**：Story 1.6 · AC2
- **页面/入口**：`/onboarding/pet-status`
- **前置**：locale=id
- **步骤**：
  1. 以印尼语 locale 进入状态选择页
  2. 观察文案
- **预期**：
  - AppBar：「Ceritakan tentang hewan Anda」
  - 副标题：「Ini membantu kami menyesuaikan beranda Anda」
  - 三个选项显示印尼语对应译文
  - **无中文**
- **层级**：L0 + L2

---

### TC-1.6.11 选择 A → 路由到档案创建引导

- **关联**：Story 1.6 · AC2（F3 分叉）· FR-0F
- **页面/入口**：`/onboarding/pet-status` → `/onboarding/profile`
- **前置**：L0，`onboarding_test.dart`；mock `updatePetStatus` 返回成功
- **步骤**：
  1. 选择 A（「I have a pet」）
  2. 点击「Done」
  3. mock 后端 PATCH 返回 200 + `onboarding_completed=true`
- **预期**：
  - 路由跳转至 `/onboarding/profile`（档案创建引导页）
  - **不跳转到 `/home`**
  - `petStatusBranchLocation('HAS_PET')` 返回 `/onboarding/profile`
  - 测试通过
- **层级**：L0

---

### TC-1.6.12 选择 B/C → 路由到首页，无提示条

- **关联**：Story 1.6 · AC2（F3 分叉）
- **页面/入口**：`/onboarding/pet-status` → `/home`
- **前置**：L0，`onboarding_test.dart`
- **步骤**：
  1. 选择 B（「Not yet, but planning to」），点击「Done」
  2. mock 后端成功
  3. 验证首页无提示条
- **预期**：
  - 路由跳转至 `/home`
  - 首页**不显示档案提示条**（`ProfilePromptBar`）
  - `petStatusBranchLocation('PLANNING')` 返回 `/home`
  - 选 C 同理：路由 `/home`，无提示条
  - 测试通过
- **层级**：L0

---

### TC-1.6.13 状态选择页返回键 → 回昵称页，保留昵称（AC4/R2）

- **关联**：Story 1.6 · AC4（R2）· FR-0F
- **页面/入口**：`/onboarding/pet-status`（`PopScope canPop:false`）
- **前置**：L0，`onboarding_test.dart`
- **步骤**：
  1. 已经在昵称页填写了昵称「TestUser」，进入状态选择页
  2. 触发设备返回键
- **预期**：
  - 路由回到 `/onboarding/nickname`
  - 昵称页的 TextField **预填「TestUser」**（已填昵称保留，优先昵称而非 displayName）
  - **不退出整个登录流程**
  - `authState` 仍为 `newUserPendingOnboarding`（不回游客）
  - 测试通过
- **层级**：L0

---

### TC-1.6.14 宠物状态 — 非法值后端 422

- **关联**：Story 1.6 · AC2（B2）
- **页面/入口**：`PATCH /api/v1/me`
- **前置**：L1
- **步骤**：
  1. 带有效 JWT，调 `PATCH /api/v1/me` body `{"petStatus":"X"}`（非 HAS_PET/PLANNING/ENTHUSIAST）
- **预期**：
  - HTTP 422 ProblemDetail
  - 后端不写库
- **层级**：L1

---

### TC-1.6.15 onboarding 完成 + GET /me 回显（L1）

- **关联**：Story 1.6 · AC2（B2/B3）
- **页面/入口**：`PATCH /api/v1/me` + `GET /api/v1/me`
- **前置**：L1，Docker pg+redis + 后端运行；已登录（有效 JWT）
- **步骤**：
  1. 调 `PATCH /api/v1/me` body `{"petStatus":"HAS_PET"}`
  2. 调 `GET /api/v1/me`
- **预期**：
  - PATCH 返回 200，响应含 `petStatus:"HAS_PET"`、`onboardingCompleted:true`
  - GET 返回 `nickname`、`petStatus:"HAS_PET"`、`onboardingCompleted:true`、`hasPetProfile:false`
  - 数据库 `users.pet_status = 'HAS_PET'`、`onboarding_completed = true`
- **层级**：L1

---

### TC-1.6.16 B/C → A 状态切换分支 — 纯函数四象限（AC5/R2）

- **关联**：Story 1.6 · AC5（R2）· `petStatusChangeAction` 纯函数
- **页面/入口**：`features/auth/domain/onboarding_branch.dart`
- **前置**：L0，`onboarding_test.dart`（AC5 四象限）
- **步骤**：
  1. 运行 AC5 状态切换纯函数单测
- **预期**：
  - B/C→A，`hasPetProfile=false`（未建档）→ `PetStatusChangeAction.toProfileOnboarding`（触发 FR-0G 引导）
  - B/C→A，`hasPetProfile=true`（已建档）→ `PetStatusChangeAction.restoreExistingProfile`（不重复引导）
  - A→B/C → `PetStatusChangeAction.switchAwayFromPet`（档案保留，成长档案 Tab 非 A 态）
  - 其他 → `PetStatusChangeAction.refreshOnly`
  - 四象限测试全通过
- **层级**：L0

---

### TC-1.6.17 A→B/C 状态切换 — 后端不删档案（AC5 B5/R2）

- **关联**：Story 1.6 · AC5（R2）· B5 · `MeServiceTest`
- **页面/入口**：`auth/service/MeService.updateMe`
- **前置**：L0，`MeServiceTest`（7 例，含 B5）
- **步骤**：
  1. 运行 `MeServiceTest.switchingAwayFromPetDoesNotDeletePetProfiles`
- **预期**：
  - 测试通过：`petProfiles.delete` / `deleteById` / `deleteAll` 零调用
  - 7/7 全通过
- **层级**：L0

---

## 1.7 状态 A 档案创建引导与首页提示条

### TC-1.7.1 档案创建引导页渲染（en）

- **关联**：Story 1.7 · AC1 · FR-0G
- **页面/入口**：`/onboarding/profile` · `profile_onboarding_page.dart`
- **前置**：locale=en；`authState` 含 `petStatus=HAS_PET`
- **步骤**：
  1. 进入 `/onboarding/profile`（状态 A 用户选完状态后路由至此）
  2. 观察页面内容
- **预期**：
  - 显示说明文案（`profileOnboardingTitle`：「Create your pet's profile」）
  - 副文（`profileOnboardingBody`：「Build your pet's growth archive」）
  - 主 CTA 按钮（`key=profileOnboardingCreate`）：「Create now」
  - 弱化按钮（`key=profileOnboardingSkip`）：「Skip for now」
  - **无其他强制步骤**
- **层级**：L0（widget test）+ L2

---

### TC-1.7.2 档案创建引导页渲染（id）

- **关联**：Story 1.7 · AC1
- **页面/入口**：`/onboarding/profile`
- **前置**：locale=id
- **步骤**：
  1. 以 locale=id 进入引导页
- **预期**：
  - 「Buat profil hewan Anda」（`profileOnboardingTitle` id）
  - 「Bangun arsip tumbuh kembang hewan Anda」（`profileOnboardingBody` id）
  - 「Buat sekarang」（`profileOnboardingCreate` id）
  - 「Lewati dulu」（`profileOnboardingSkip` id）
  - **无中文**
- **层级**：L0 + L2

---

### TC-1.7.3 「立即创建」→ 跳转档案创建表单

- **关联**：Story 1.7 · AC1（F1）
- **页面/入口**：`/onboarding/profile` → `/profile/create`
- **前置**：L0（`profileOnboardingCreate` 按钮路由）
- **步骤**：
  1. 进入引导页，点击「Create now」
- **预期**：
  - 路由跳转至 `/profile/create`（Epic 2 档案创建表单，本 Story 期为占位或真实表单）
  - 不崩溃
- **层级**：L0

---

### TC-1.7.4 「跳过，稍后创建」→ 进首页 + 激活提示条逻辑

- **关联**：Story 1.7 · AC1 · AC2（F1/F3）
- **页面/入口**：`/onboarding/profile` → `/home`
- **前置**：L0，`profile_prompt_widget_test.dart`；`petStatus=HAS_PET`，未建档
- **步骤**：
  1. 进入引导页，点击「Skip for now」
  2. 观察进入首页后的顶部区域
- **预期**：
  - 路由跳转至 `/home`
  - 首页顶部**显示档案提示条** `ProfilePromptBar`（文案 + 「Create now」按钮 + 关闭 X）
  - 提示条文案：「Create your pet's profile to start their growth archive 🐾」（en）
  - 测试通过
- **层级**：L0

---

### TC-1.7.5 状态 B/C 用户进首页 — 无提示条（AC3）

- **关联**：Story 1.7 · AC3
- **页面/入口**：`/home`
- **前置**：L0，`petStatus=PLANNING`（B）或 `petStatus=ENTHUSIAST`（C）
- **步骤**：
  1. 以 B 或 C 状态用户进入首页
  2. 观察顶部区域
- **预期**：
  - **不显示 `ProfilePromptBar`**
  - 提示条计数逻辑**完全不激活**（不读写 prefs 相关键）
  - 测试通过
- **层级**：L0

---

### TC-1.7.6 提示条 3 次重启状态机 — 核心纯函数测试

- **关联**：Story 1.7 · AC2 · FR-0H（`shouldShowProfilePrompt` 纯函数）
- **页面/入口**：`features/profile/domain/profile_prompt_state.dart`
- **前置**：L0，`profile_prompt_test.dart`（7 例）
- **步骤**：
  1. 运行 `profile_prompt_test.dart` 全套纯函数状态机单测
- **预期**（各边界全部通过）：
  - `petStatus='A'`（HAS_PET），重启 1/2/3 次 → `shouldShowProfilePrompt=true`
  - 重启 4 次（>3）→ `shouldShowProfilePrompt=false`
  - 重启 1 次关闭（`count<3` 时 `onDismiss`）→ 本 session 隐藏，次次启动仍显示
  - 第 3 次关闭（`count≥3` 时 `onDismiss`）→ 永久关闭（`dismissedPermanently=true`）
  - `hasPetProfile=true` → 不显示（档案已创建）
  - `petProfileCompleted=true` → 不显示
  - `petStatus=B/C` → 不激活（不显示）
  - `petStatus=null` → 不显示
  - 7/7 全通过
- **层级**：L0

---

### TC-1.7.7 提示条关闭 X — 当次 session 隐藏，下次重启仍显示

- **关联**：Story 1.7 · AC2 · FR-0H
- **页面/入口**：`ProfilePromptBar`（`key=profilePromptClose`）
- **前置**：L0，`profile_prompt_widget_test.dart`；重启次数 <3
- **步骤**：
  1. 状态 A，重启 1 次，首页显示提示条
  2. 点击关闭 X（`profilePromptClose`）
  3. 当次 session 重新导航至首页（不重启）
- **预期**：
  - 点击后提示条消失
  - **当次 session** 不再显示
  - （L2 重启验证）重启后重新显示（未永久关闭）
- **层级**：L0 + L2

---

### TC-1.7.8 第 3 次关闭后永久不显示

- **关联**：Story 1.7 · AC2 · FR-0H（`dismissedPermanently`）
- **页面/入口**：`shouldShowProfilePrompt` 状态机
- **前置**：L0，`profile_prompt_test.dart`
- **步骤**：
  1. 模拟 `restartCount=3`，调 `onDismiss`
  2. 再次检查 `shouldShowProfilePrompt`
- **预期**：
  - `dismissedPermanently=true`
  - `shouldShowProfilePrompt=false`（永久不再显示）
  - 测试通过
- **层级**：L0（L2：真机跨重启 3 次后确认）

---

### TC-1.7.9 档案已创建（hasPetProfile=true）→ 提示条不显示

- **关联**：Story 1.7 · AC2（档案完成回填契约）
- **页面/入口**：`shouldShowProfilePrompt` 状态机
- **前置**：L0
- **步骤**：
  1. 模拟 `petStatus=HAS_PET`，`hasPetProfile=true`，重启 1 次
- **预期**：
  - `shouldShowProfilePrompt=false`
  - 提示条不渲染
  - 测试通过
- **层级**：L0（L1：`GET /api/v1/me` 真实 `hasPetProfile=true`，Epic 2 后回填）

---

### TC-1.7.10 「立即创建」CTA — 跳转档案创建页，无障碍触摸目标

- **关联**：Story 1.7 · AC2（F2）· NFR-13
- **页面/入口**：`ProfilePromptBar`（`key=profilePromptCreate`）
- **前置**：L0/L2
- **步骤**：
  1. 首页显示提示条时，点击「Create now」（`profilePromptCreate`）
- **预期**：
  - 路由至 `/profile/create`（或 Epic 2 档案创建页）
  - 关闭 X 按钮 `minWidth/minHeight ≥ 44pt`（NFR-13 无障碍触摸目标）
- **层级**：L0（按钮尺寸 widget test）+ L2

---

### TC-1.7.11 B/C → A 状态切换后提示条激活，沿用既有计数（AC2 R2 · 决策 F15 邻接）

- **关联**：Story 1.7 · AC2（`shouldShowProfilePrompt` B/C→A 激活）
- **页面/入口**：`profile_prompt_state.dart` 状态机
- **前置**：L0
- **步骤**：
  1. 模拟用户之前是 B/C（`restartCount=0`，未激活计数）
  2. 变更为 `petStatus=HAS_PET`（B/C→A 切换）
  3. 模拟冷启动（`onColdStartIncrement`）
  4. 检查 `shouldShowProfilePrompt`
- **预期**：
  - `petStatus=HAS_PET`，重启计数激活（沿用 A 态既有持久计数，不重置）
  - `shouldShowProfilePrompt=true`（应显示提示条）
  - 测试通过
- **层级**：L0

---

### TC-1.7.12 建档庆祝页渲染 + 主/副 CTA（AC4/R2 · 决策 F15）

- **关联**：Story 1.7 · AC4（R2）· FR-0G · 决策 F15
- **页面/入口**：`/profile/created` · `profile_created_celebration_page.dart`
- **前置**：L0，`profile_created_celebration_test.dart`；`extra=PetProfile(name='Mochi', ...)`
- **步骤**：
  1. 运行庆祝页渲染 widget test
  2. L2：完成建档，观察庆祝页
- **预期**：
  - 显示宠物头像（avatarUrl 或默认图）
  - 显示文案：「Mochi's very own profile is ready! 🎉」（`profileCreatedTitle` 插值，en）
  - 主 CTA 按钮「Start exploring」（`profileCreatedStartExplore`）存在
  - 副 CTA「Share pet card」（`shareFabLabel`）存在
  - 测试通过
- **层级**：L0 + L2

---

### TC-1.7.13 庆祝页主 CTA「开始探索」→ 推送权限闸门 → 进首页（AC4/R2）

- **关联**：Story 1.7 · AC4（R2）· 决策 F15 · FR-22D（建档时机）
- **页面/入口**：`profile_created_celebration_page.dart` → 推送权限弹窗 → `/home`
- **前置**：L0，`profile_created_celebration_test.dart`（mock 推送闸门）
- **步骤**：
  1. 渲染庆祝页，点击「Start exploring」
  2. mock `pushPermissionGateProvider.maybeRequestAfterProfileCreated`
- **预期**：
  - 推送权限闸门被调用（`neverConsulted=true`）
  - 闸门完成后（不管权限授予与否）→ 路由至 `/home`
  - 测试通过
  - L2：真机上显示系统推送权限弹窗（首次问诊或建档触发，仅一次）
- **层级**：L0（mock 闸门）+ L2（真实推送系统弹窗）

---

### TC-1.7.14 跳过分支纯函数 — FR-16/FR-12 来源跳过庆祝页

- **关联**：Story 1.7 · AC4（R2）· `showsBuildCelebration` 纯函数
- **页面/入口**：`features/profile/domain/profile_created_flow.dart`
- **前置**：L0，`profile_created_celebration_test.dart`（来源分支 +4 例）
- **步骤**：
  1. 运行来源分支纯函数单测
- **预期**：
  - `BuildOrigin.onboarding` → `showsBuildCelebration=true`（显示庆祝页）
  - `BuildOrigin.triageArchive` → `showsBuildCelebration=false`（跳过庆祝页，直接回原流程）
  - `BuildOrigin.graySelectPublish` → `showsBuildCelebration=false`（同上）
  - 边界：未知来源 → 默认显示庆祝页（不崩）
  - 4 例全通过
- **层级**：L0

---

### TC-1.7.15 庆祝页 extra 缺失（直接深链）→ 防御性回首页不崩

- **关联**：Story 1.7 · AC4（R2）· 页面多态（无数据深链）
- **页面/入口**：`/profile/created`（无 extra）
- **前置**：L0/L2
- **步骤**：
  1. 直接导航 `/profile/created`（不传 `extra`，模拟刷新/深链）
- **预期**：
  - 不崩溃（不抛 NPE）
  - 自动路由回首页 `/home`（`addPostFrameCallback` 防御逻辑）
- **层级**：L0 + L2

---

### TC-1.7.16 提示条 prefs 损坏/缺失 → 默认从 0 开始，不崩

- **关联**：Story 1.7 · AC2（「计数损坏默认从 0」容错）
- **页面/入口**：`ProfilePromptController` + `main.dart` bootstrap
- **前置**：L0
- **步骤**：
  1. 清空所有 prefs（或模拟 prefs 读取异常）
  2. 以 `petStatus=HAS_PET` 启动 App
- **预期**：
  - App 不崩溃
  - 提示条显示（默认从重启 0 次起算，首次显示）
  - `ProfilePromptController` best-effort 策略：prefs 异常→忽略，不抛
- **层级**：L0

---

## 本章遗留/盲区

以下测试点在当前实现阶段**无法验或待后续就绪**：

1. **L2 真实 Google OAuth 端到端全链路**（TC-1.3.4/1.3.5/1.5.12 等）：需配置真实 Google OAuth client id（`GOOGLE_OAUTH_CLIENT_ID` 后端环境变量 + `GOOGLE_SERVER_CLIENT_ID` / `--dart-define` 前端），以及 iOS/Android 原生 `google_sign_in` 配置（Info.plist URL Scheme / strings.xml），目前 `.env.example` 为占位，待本地接入真机时补全。

2. **提示条跨重启（L2 真机）**（TC-1.7.7/1.7.8）：3 次重启计数状态机的**可观察序列**（第 1/2/3 次重启分别显示，第 3 次关闭后重启不显示）需在真机/模拟器上手动验收，无法在 L0 纯函数测试中自动化跨重启行为。

3. **薄荷绿换肤后 `AppColors.base` 实际值**：Story 1.2 已从焦糖色 `#C8874A` 全面换肤为薄荷绿（`#7FD1AE`）。TC-1.2.1 中「底色恒 `AppColors.base`」的验收**以 `petgo_app/lib/core/theme/colors.dart` 代码为权威值**，非原始 `#FAF8F5`；L0 widget test 仍通过 `AppColors.base` 引用断言（避免硬编码十六进制值漂移失效）。

4. **`/onboarding` 薄荷绿换肤主流程（MintOnboardingPage）的 L1 后端联动**：当前 `MintOnboardingPage` 在 mock 模式下完成 3 步后直接 `context.go('/home')`，未真实调用后端 `/api/v1/me` PATCH 端点。分步引导（`/onboarding/nickname` → `/onboarding/pet-status`）是真实联动路径；薄荷绿版本的后端联动（用昵称/宠物状态/照片真实落库）待联调确认路由实际触发的是哪条路径。

5. **`hasPetProfile` 信号真实驱动（L1 与 Epic 2 联动）**：TC-1.7.9 中 `hasPetProfile=true` 的路径在 Story 1.7 期恒返回 `false`（无 `pet_profiles` 表），须等 **Epic 2 Story 2.2** 建表后 `MeService.hasPetProfile()` 改为 `petProfiles.existsByOwnerId`，L1 方可真实验证「档案已创建后提示条即时隐藏」。
