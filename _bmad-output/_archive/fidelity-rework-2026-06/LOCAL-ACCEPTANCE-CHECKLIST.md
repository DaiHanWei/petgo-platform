# TailTopia 本地验收清单（L1 起栈 + L2 三方/真机汇总）

> 生成：2026-06-09 · 分支 `prd-v1.0.0-rework`。云端/CI 只跑到 **L0**（编译/lint/单测/MockMvc，无需 DB/凭证）——已全绿（后端 523 / 前端 299，analyze 净）。本清单汇总所有需在**本地**完成的 **L1（Docker 起栈）** 与 **L2（真实三方凭证 / 真机模拟器）** 验收项。
>
> **验证层定义**：
> - **L1** = 需 Docker daemon + PostgreSQL + Redis 真跑（集成测试 / 运行时状态机 / 限流 / 落库）。**无需外部凭证**——三方均有 stub 模式。
> - **L2** = 需真实第三方凭证（阿里 OSS/STS、Gemini、腾讯 IM、Google）或真机/模拟器（视觉、动效、权限、系统能力）。

---

## 0. 设计前提：三方全部可 stub，L1 不需要任何外部凭证

| 三方 | 开关（env / 默认） | L0/L1（stub，免凭证） | L2（live，需真凭证） |
|---|---|---|---|
| Google 登录 | `GOOGLE_OAUTH_CLIENT_ID`（空=stub verifier） | ✅ stub verifier | 真实 OAuth client id + 真机 |
| 阿里云 OSS/STS | `ALIYUN_ACCESS_KEY_ID/SECRET` 等（空=mock） | ✅ mock，不打真实桶 | 真实 AK/SK + 双桶 + RoleArn |
| Gemini 分诊 | `GEMINI_MODE=stub`（默认） | ✅ 打桩固定回包 | `GEMINI_MODE=live` + `GEMINI_API_KEY` |
| 腾讯 IM | `IM_MODE=stub`（默认） | ✅ 桩会话 id + 占位 UserSig | `IM_MODE=live` + `SDKAppID/SecretKey` + 真机 |
| 推送下发 | 复用 IM 离线通道 | ✅ 不实发 | 真机 + 系统推送权限 |

> 前端默认 **mock 模式**（`PETGO_MOCK=true`，debug 默认开，离线假数据跑全流程）。连真后端：`--dart-define=PETGO_MOCK=false --dart-define=PETGO_API_BASE_URL=http://<host>:8080`。

---

## 1. 环境准备

### 1.A — L1 基座（所有 L1 项的前置）
- [ ] 启动 Docker daemon
- [ ] `cp petgo-backend/.env.example petgo-backend/.env`（DB/Redis/JWT 占位即可，stub 三方留空）
- [ ] 起 postgres + redis（docker compose）；Flyway 迁移自动执行（`ddl-auto=validate`）
- [ ] `cd petgo-backend && ./mvnw -B test`（含全部 `*EndpointTest`/`ApiIntegrationTest` 真跑）→ 期望全绿
- [ ] `./mvnw spring-boot:run` + `GET /actuator/health` = `UP`

### 1.B — L2 三方凭证（按需注入 `.env`，绝不入库）
- [ ] **Google**：`GOOGLE_OAUTH_CLIENT_ID` + 前端 `--dart-define=GOOGLE_SERVER_CLIENT_ID=...`
- [ ] **阿里云 OSS/STS（雅加达 ap-southeast-5）**：`ALIYUN_ACCESS_KEY_ID`/`ALIYUN_ACCESS_KEY_SECRET`/`OSS_PUBLIC_BUCKET`/`OSS_PRIVATE_BUCKET`/`OSS_CDN_BASE_URL`/`STS_ROLE_ARN`
- [ ] **Gemini**：`GEMINI_MODE=live` + `GEMINI_API_KEY`（gemini-2.5-flash）
- [ ] **腾讯 IM**：`IM_MODE=live` + `TENCENT_IM_SDK_APP_ID` + `TENCENT_IM_SECRET_KEY`（SecretKey 绝不下发客户端/不落日志）
- [ ] **H5 名片**：前端 `--dart-define=PETGO_H5_BASE_URL=https://<域名>`

### 1.C — L2 真机/模拟器
- [ ] iOS 真机/模拟器 + Android 真机/模拟器（视觉、动效、portrait-only、权限弹窗）
- [ ] 真实相机/相册照片（含 GPS EXIF、HEIC）用于媒体链路
- [ ] 两台设备（用户端 + 兽医端）用于双端联动（在线态、抢单、IM 对话、封禁中断）

---

## 2. L1 清单（Docker pg+redis 起栈，无需外部凭证）

> 跑 `./mvnw test` 即覆盖大部分（集成测试已写）；以下为需人工确认的运行时流。

### 认证 / 门控（Epic 1）
- [ ] **1-3/1-5** 受控写端点无 token → 401 ProblemDetail；游客只读端点 permitAll → 200（`GatewayPingControllerEndpointTest` 对称性）
- [ ] **1-3** JWT 签发/续期/refresh 单飞；401→refresh 一次→重放

### 档案 / 媒体元数据（Epic 2）
- [ ] **2-2/2-8** 单账号单宠物约束、档案创建/编辑落库、`pet_type` 置灰只读
- [ ] **2-4** 成长档案时间线（event_date 排序）+ 日历视图聚合
- [ ] **2-6** 名片 H5 6 区块 + event_date + 零态/防枚举（404 失效页）
- [ ] **2-5** 问诊存档承接 + 健康事件时间线

### 内容（Epic 3）
- [ ] **3-2** Feed 倒序游标 + 宠物状态硬过滤（B=PLANNING 排除 GROWTH_MOMENT）
- [ ] **3-4/3-5** 点赞计数 + 两级评论落库
- [ ] **3-6/3-7** 软删级联（评论/点赞）+ 举报工单 PENDING + Admin 人工下架 → Feed/时间线移除、详情 404；**3-7 运营下架发 `CONTENT_REMOVED` 通知作者、驳回零通知**
- [ ] **3-1** Admin 门控（仅 ADMIN）+ 种子内容发布

### 分诊（Epic 4，Gemini stub）
- [ ] **4-1** `POST /triage`→202+id→短轮询 DONE/FAILED 三态；幂等 + 限流
- [ ] **4-2** ⚠️安全：高危症状（误食巧克力）经安全规则层**强制升红 RED**（Gemini stub 回 GREEN 也被后置裁决覆盖）
- [ ] **4-3** AC5 文字必填图片选填：仅文字→202、空白文字→422

### 兽医问诊（Epic 5，IM stub）
- [ ] **5-1** 兽医账密登录 + BANNED 不可登录
- [ ] **5-2** Redis 在线态 ZSET TTL 兜底（不发离线请求掉线→`anyOnline` 不计入）；`/consult/availability` 只回 bool 不回人数
- [ ] **5-3** ⚠️安全：**抢单 DB 原子写先到先得**（影响 0 行=已被抢）+ 退单≤2 重广播 + 等待期退出 WAITING→CANCELLED
- [ ] **5-6** 兽医结束→PENDING_CLOSE→30min 评分门（评分→CLOSED+存档事件 / 超时→UNRATED+补弹 PENDING）；**AC5 有活跃会话则 `pendingRating` 返回空（补评分推迟）**
- [ ] **5-7** ⚠️安全：封禁→BannedVetFilter 即时 401 + 进行中会话批量 INTERRUPTED + 未接 WAITING 不受影响（不误伤他人/断线可恢复会话）

### 推送 / 通知（Epic 6）
- [ ] **6-1/6-6** `notifications` 落库 + Redis 未读角标 INCR/DECR/库回算；越权标记他人→404 防枚举
- [ ] **6-6** AC3 推送直跳 `NotificationDeepLink.open` markRead + 角标重算（推送已读/中心一致）

### 我的 / 注销（Epic 7）
- [ ] **7-1** AC6 `/me/posts` 按 created_at 倒序（与成长档案 event_date 口径分离，`myPostsOrdersByCreatedAtNotEventDate` 真 DB 验）
- [ ] **7-3** ⚠️安全：注销级联删除（各模块 deleteByUserId + OSS 对象）+ UGC 匿名化保留（D1）+ IM 媒体处置（D2）+ 名片 token 立即失效；越权 403

---

## 3. L2 三方凭证清单

### 🔴 阿里云 OSS/STS（Story 2-1 基建，被 2.2/2.3/2.5/4.x 复用）
- [ ] **J1** STS 直传：PUBLIC→公开桶经 CDN 可访问；PRIVATE→私密桶
- [ ] **J1 ⚠️scope** PUBLIC 凭证越权写 `private/`、他人 `<uid>/` 前缀 → 被 OSS 拒（验 scope 收窄真生效）
- [ ] **J2** 私密对象：无签名 GET=403 / 签名 GET=200 / TTL(300s) 过期=403
- [ ] **J3 EXIF** 含 GPS 照片直传后，经对外分发 URL 取回 → 断言无 GPS（服务端 x-oss-process 兜底）
- [ ] 头像（公开桶①）替换：客户端压缩 → STS 直传 → 回填应用自有 URL（2.2/7.1）

### 🔴 Gemini（Story 4-1，`GEMINI_MODE=live`）
- [ ] 真实 gemini-2.5-flash 端到端：上传私密图 + 症状 → 真实分诊三态返回 → DONE 交棒 4.4/4.5
- [ ] 超时（>15s）/服务异常 → 前端降级态 + 重提交复用 imageObjectKeys 不重传
- [ ] 日志不落签名 URL / 健康图内容 / API key

### 🔴 腾讯 IM（Story 5-5，`IM_MODE=live`，需双真机）
- [ ] 接单 → 真实建会话 + UserSig 下发（SecretKey 不下发客户端）
- [ ] 用户↔兽医实时图文对话；PENDING_CLOSE 期间仍可收发（5.6）
- [ ] IM 系统消息：「请评分」（5.6）/「兽医已临时下线…请重新发起」（5.7 封禁中断）
- [ ] 存档桥接：会话 IM 媒体复制到私密桶档案路径（5.6 `ImToOssArchiver`，只引用应用自有 URL）
- [ ] kill 断线→重开从「进行中」恢复、IM 会话连续（5.6 AC5）

### 🔴 Google 登录（Story 1-3/1-4/1-5）
- [ ] 真机真实 Google 登录 → JWT 签发 → 受控入口回跳触发点
- [ ] 新用户 → 1.6 引导（昵称/状态）→ 1.7 建档庆祝页；老用户直接回跳

### 🔴 系统推送（Epic 6，真机 + 推送权限）
- [ ] **6-4** 首启**不**弹推送权限；首次问诊完成 **或** 建档（庆祝页「开始探索」后）→ 软引导→系统弹窗；拒绝后不再弹、「我的」去设置
- [ ] **6-2/6-3/6-7** 授予后真机收推送：兽医回复 / 被赞被评 / 生日·纪念日·里程碑定时
- [ ] **6-6** 点系统推送直达目标 → 回通知中心该条目已读

### 🔴 应用商店（Story 6-5）
- [ ] App 版本更新提醒（需商店上架版本对比）

---

## 4. L2 真机 / 模拟器清单（视觉 / 动效 / 权限 / 无障碍）

### 视觉 / 动效（薄荷绿 mint 设计）
- [ ] **1-2** 切 5 Tab 整屏底色恒 #FBF8F1 不变 + 120ms 淡入；＋凸起半埋几何 + active 圆 scale 入场；真机切系统语言 id↔en
- [ ] **2-7** 分享 FAB 首访 scale pulse + ring ripple、复访静态、pinned 滚动不消失
- [ ] **4-4/4-5** ⚠️安全：分诊三态卡（绿/黄含倒计时协议块）；**红色半屏强提醒**自底滑起 + 红色态零变现/零兽医引流 + 存档入口守护
- [ ] **1-7** 建档「创建成功」庆祝页时序

### 权限（系统弹窗，真机）
- [ ] **2-1** 相机/相册首次触发系统弹窗 → 拒绝 → 引导「去设置」深链（iOS 已补 `NSCameraUsageDescription`/`NSPhotoLibraryUsageDescription`）
- [ ] HEIC 拍照 + JPG/PNG 选图走压缩/EXIF 链路
- [ ] **6-4** 推送权限弹窗时机/拒绝兜底（见 §3 系统推送）

### 双端联动（用户端 + 兽医端两设备）
- [ ] **5-2** 兽医切在线/离线 → 用户侧入口「当前暂无兽医在线」即时联动（不透传人数）
- [ ] **5-2/5-3** 抢单：多兽医并发见同批 WAITING；点详情 3min 预览计时；三态返回（用户取消/被他人抢/超时）
- [ ] **5-7** 封禁中断 vs 断线可恢复 文案/状态区分（真机对比）

### 无障碍（Story 7-4 横切验收）
- [ ] 触摸目标 ≥44×44pt；对比度 ≥4.5:1；三色态 icon+text+color 不依赖颜色单一
- [ ] screen reader 语义标签（含 2-7 分享 FAB label、各编辑/入口）
- [ ] **7-2** 语言设置切换 + 双语文案收口逐屏走查

---

## 5. 执行建议

1. **先 L1 一把过**：起 Docker → `./mvnw test` 全绿 → `spring-boot:run` + 前端 `--dart-define=PETGO_MOCK=false` 连真后端，过一遍 §2 运行时流（stub 三方，免凭证）。
2. **再按三方分批 L2**：每接一个真凭证（OSS→Gemini→IM→Google→推送）切 `*_MODE=live`，过对应 §3 块；IM/双端联动需两台真机。
3. **最后真机视觉/无障碍走查**：§4（含 portrait-only、mint 视觉、红色态零变现安全验收、7-4 无障碍）。
4. **⚠️安全攸关优先单独验**（红线，必须真机/真栈确认）：4-2 强制升红 · 5-3 抢单原子写 · 5-7 封禁中断 · 7-3 注销级联+匿名化 · 4-5 红色态零变现。

> 已知非三方缺口（本地一并处理）：无（2-1 iOS Info.plist 权限串已于 2026-06-09 补齐）。

---

## L1 验收执行记录（2026-06-09 · 本机 macOS + Android 模拟器）

> 环境：Docker（postgres:16 + redis:7，6 天已起、healthy）+ Android AVD `Pixel_9_Pro_API_36`（emulator-5554）。三方全 stub（OSS mock / Gemini stub / IM stub / Google dev stub verifier），无外部凭证。

**后端 L1（✅ 全绿）**
- `./mvnw -B -o test` → **523 tests, 0 fail**（含全部 `*EndpointTest`/`ApiIntegrationTest` 真连 pg+redis 落库，14s）。
- `./mvnw spring-boot:run`（host，application.yml 默认连 localhost:5432/6379）→ `GET /actuator/health` = **UP**（db PostgreSQL UP / redis 7.4.9 UP / liveness+readiness UP）。
- API 烟测：游客 `GET /content-posts` → **200 + 真实 DB 数据**；受控写无 token `POST /triage` → **401 RFC 9457 ProblemDetail**；`/consult/availability` 游客 → **401**（符合设计，5-2 把 `/consult/**` 收为 hasRole USER）。

**前端 L1（✅ 连真后端，Android 模拟器）**
- `flutter run -d emulator-5554 --dart-define=PETGO_MOCK=false --dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080` → Gradle assembleDebug 26.5s 构建成功 + 安装 + 启动（Impeller GLES）。
- **FR-0A 游客可浏览**：清数据后干净游客首页渲染**真实后端 Feed**（「被举报的正文」「用户1690519041…」与 curl 响应一致 → 证明 mock=false 真连后端、非假数据）；通知铃对游客正确隐藏；无强制登录框。
- **登录流端到端**：点「Sign in with Google」→ dev stub verifier 签发 JWT → 登录态「测试用户」+ 通知铃出现 + FR-0H 建档提示条「Catat momen hari ini」。
- **门控**：模拟器残留 stale token 冷启动 → restore 失败 → 登录引导弹框（正确行为；清数据后游客态无此弹框，排除 FR-0A 误拦截）。

**结论**：L1（Docker 起栈 + 真连后端运行时）验收通过，无阻塞缺陷。下一步 L2 需真实凭证/真机（见 §3/§4）。

**备注**：dev DB 已累积 523 测试 run 写入的测试行（Feed 显示「测试正文」「被举报的正文」等）；如需干净演示数据可 `docker compose down -v` 重置卷后重跑 seed。后端进程（spring-boot:run）与模拟器本次保持运行，供继续手验。
