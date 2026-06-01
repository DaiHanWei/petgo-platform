# Story 2.1: 媒体基建——OSS 双桶、STS 直传、签名 URL 与 EXIF 剥离

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **上传的图片被安全地存储与分发**,
so that **我的公开内容能快速加载、私密健康图不被泄露、定位信息不外泄**。

> 这是 **Epic 2 的第一个 Story**，也是整个产品的**媒体三层共享地基**（架构 §Decision Impact「实现序列第 3 步」明确：媒体 STS + 签名 URL 被 triage(私密图)、profile(档案/名片图)、content(Feed 图) 共用）。本 Story 只交付"安全上传 + 分发 + 签名访问 + EXIF 剥离 + 权限申请"的通用能力，**不实现任何具体业务上传场景**——档案头像在 2.2、内容图在 2.3、健康历史图在 2.5、分诊图在 Epic 4 各自调用本 Story 产出的接口与客户端工具。
>
> **依赖前序**：Story 1.1（脚手架 + `shared/media` 空包 + ProblemDetail + Docker 编排）、Story 1.3（JWT，STS 端点需登录用户身份做 scope 限定）。

## Acceptance Criteria

> **验证层标注**：每条 AC 末尾标注验证层级与所需本地环境——
> **L0 静态**（编译/lint/MockMvc，无需 DB/外部）· **L1 集成/运行时**（需 Docker daemon + postgres + redis）· **L2 端到端/外部凭证**（需真实阿里 OSS/STS 凭证、真机相册/相机权限）。
> 本 Story 是 Epic 2 中**唯一含 L2 的 story**——STS 直传与签名 URL 必须对真实阿里 OSS 雅加达双桶验证才算闭环；EXIF 剥离、权限申请、STS scope 受限可在 L0/L1 用 mock + 测试桶覆盖大部分逻辑。

### AC1 — STS 直传双桶 + 客户端压缩 ≤10MB

**Given** 客户端需上传图片
**When** 向后端请求 STS 临时凭证并直传阿里 OSS 雅加达
**Then** 公开图进公开桶（阿里 CDN 分发）、私密图进私密桶（仅短 TTL 签名 URL 访问），STS scope 受限（NFR-7）
**And** 客户端上传前压缩至 ≤10MB
> 验证层：**L0**（`StsService` scope 策略 JSON 构造、桶选择逻辑、客户端压缩到 ≤10MB 的单测可静态验证）+ **L1**（STS 端点经 JWT 鉴权 + 限流，MockMvc/集成测试验返回信封含 accessKeyId/secret/securityToken/expiration/bucket/region/uploadDir，但不真打 OSS）+ **L2**（真实阿里 STS 签发临时凭证 → 客户端用其直传真实公开/私密桶成功落对象、且凭证 scope 越权写其他前缀被拒）。

### AC2 — EXIF GPS 剥离

**Given** 任意上传图片
**When** 进入媒体处理链路
**Then** 剥离 EXIF GPS 等定位元数据后再落桶（G-4，防 H5 公开页定位泄漏）
**And**（决策 E4）**公开桶①对外图片叠加服务端兜底**——客户端剥离为主路径，但因 STS 直传后端不经手原图、改过的客户端可绕过，**公开桶图片对外分发（尤其 H5 名片）必须有服务端层 EXIF 剥离兜底**：用阿里 OSS 图片处理（IMG 样式/`x-oss-process`）在访问/分发时去除 EXIF，或档案/名片图在生成对外引用前由后端拉取重处理一次。私密桶②仅本人签名访问、不对外，可不强制服务端兜底。
> 验证层：**L0**（客户端剥离逻辑单测：构造带 GPS EXIF 的测试图 → 处理后断言 GPS 段被移除、像素不变；服务端兜底的 OSS process 参数/重处理逻辑单测）+ **L2**（真机拍摄/选取含 GPS 照片直传公开桶后，**经对外分发 URL/H5 取回断言无 GPS**——验证兜底对绕过客户端的情形仍生效；私密桶取回断言无 GPS）。

### AC3 — 私密桶仅短 TTL 签名 URL + 日志不落敏感

**Given** 私密桶图片
**When** 业务侧（如 Gemini 拉图、健康历史展示）需访问
**Then** 仅发放短 TTL 签名 URL，日志与对象存储 URL 不落 PII/健康数据
> 验证层：**L0**（`SignedUrlService` 生成签名 URL 含 Expires/Signature、TTL 默认值正确；日志切面/审查确认签名 URL 与对象 key 不进 INFO 日志）+ **L2**（真实私密桶对象：无签名直接 GET 返回 403；带签名 GET 200；过期后再 GET 403）。

### AC4 — 相机/相册权限申请与拒绝引导

**Given** 相机/相册权限
**When** 用户首次点击上传
**Then** 按场景触发权限申请，拒绝时弹引导「需要相册权限才能上传，请前往设置开启」+「去设置」深链（FR-22D 相机/相册部分）
> 验证层：**L0**（权限状态机 + 文案 i18n key 存在 + 「去设置」调用 `openAppSettings` 的单测/widget 测试，用 mock permission handler）+ **L2**（真机/模拟器首次触发系统权限弹窗、拒绝后出引导、点「去设置」跳系统设置页）。

---

## Tasks / Subtasks

> **按"后端 / 前端 / 联调验收"三段组织**。本 Story **后端重**（OSS 客户端、STS 凭证签发、签名 URL 三件套是地基），前端提供可复用的压缩+直传+权限工具。建议执行顺序：后端 → 前端 → 联调（含 L2 真凭证）。

### 🟦 后端子任务（petgo-backend / Spring Boot · `shared/media`）

- [ ] **B1. 引入阿里 OSS/STS SDK 与双桶配置** (AC: 1, 3)
  - [ ] `pom.xml` 加阿里云 OSS Java SDK + STS（`aliyun-sdk-oss` + `aliyun-java-sdk-sts`/`aliyun-java-sdk-core`，取与 Java 21/SB4 兼容最新版，记录实际版本）。
  - [ ] `application-*.yml` 媒体配置块（**全部从 env 注入，绝不入库**）：`media.oss.endpoint`(雅加达 `oss-ap-southeast-5`)、`media.oss.region`、`media.oss.public-bucket`、`media.oss.private-bucket`、`media.oss.cdn-base-url`、`media.sts.role-arn`、`media.sts.duration-seconds`(默认 900)、`media.signed-url.ttl-seconds`(默认 300)、`media.access-key-id`/`media.access-key-secret`。
  - [ ] `.env.example` 补 OSS/STS 占位 + 注释「真实凭证仅运行环境注入」。
- [ ] **B2. `AliyunOssClient`（薄封装）** (AC: 1, 3)
  - [ ] `shared/media/AliyunOssClient`：封装 OSSClient 构建（公开/私密桶寻址）、生成对象 key/前缀、（可选）服务端探测对象是否存在。仅基础设施，不含业务。
  - [ ] **（E4 服务端 EXIF 兜底）公开桶对外分发去 EXIF**：提供「对外公开 URL 带 `x-oss-process` 去元数据样式」或「拉取重处理后另存无 EXIF 副本」的方法，供 2.6 名片 H5 / 档案对外图调用。私密桶不强制。
- [ ] **B3. `StsService`——受限 scope 临时凭证签发** (AC: 1)
  - [ ] `shared/media/StsService.issueUploadCredential(MediaScope scope, Long userId)`：调 STS AssumeRole，**Policy 动态收窄**到「仅允许 `PutObject` 到 `<bucket>/<scope前缀>/<userId>/*`」，禁止读其他用户/其他桶。`MediaScope ∈ {PUBLIC, PRIVATE}` 决定目标桶与前缀（如 `public/content/{userId}/`、`private/health/{userId}/`）。
  - [ ] DTO：`StsCredentialResponse`(record：accessKeyId, accessKeySecret, securityToken, expiration(ISO-8601 UTC), bucket, region, endpoint, cdnBaseUrl, uploadDir)。**省略 null**（Jackson NON_NULL）。
- [ ] **B4. `MediaController`——STS 端点 + 限流幂等** (AC: 1)
  - [ ] `shared/media`（或 profile/web 下统一媒体入口）暴露 `POST /api/v1/media/sts-credentials`（需 JWT），body `StsCredentialRequest{scope, contentType, count?}`，Bean Validation：scope 枚举、count ≤9。返回 `StsCredentialResponse`。
  - [ ] 套 `RedisRateLimiter`（写端点令牌桶，防滥发凭证）；返回 200。**绝不在响应或日志打印 accessKeySecret/securityToken 明文进 INFO**（仅 DEBUG 且本地）。
- [ ] **B5. `SignedUrlService`——私密桶短 TTL 签名 URL** (AC: 3)
  - [ ] `shared/media/SignedUrlService.sign(String objectKey)` → 私密桶 GET 预签名 URL，TTL=`media.signed-url.ttl-seconds`。提供批量签名方法（健康历史多图）。
  - [ ] **复用入口**：供后续 GeminiClient 拉私密图（Epic 4）、健康历史展示（2.5）调用；本 Story 仅产出 service，不接业务。
- [ ] **B6. 日志与隐私护栏** (AC: 3)
  - [ ] 确认 media 链路所有日志：**不落** 签名 URL、STS secret/token、对象 key 中可能含的健康语义。MDC 仅 traceId/userId。logback JSON。
  - [ ] `shared/media/ImToOssArchiver` **建空壳类 + 方法签名占位**（`archiveImImagesToPrivate(...)`），实现留 Story 2.5——此处仅占位以固化目录，避免 2.5 改动 shared 结构。

### 🟩 前端子任务（petgo_app / Flutter · `core` + `shared`）

- [ ] **F1. 依赖与权限插件** (AC: 1, 4)
  - [ ] `flutter pub add image_picker permission_handler flutter_image_compress`（或等效；取兼容版本）。iOS `Info.plist` / Android `AndroidManifest.xml` 补相机/相册权限声明与 i18n 友好用途串。
- [ ] **F2. 媒体上传 repository + DTO** (AC: 1)
  - [ ] `lib/features/media/data/`（或 `core/media/`）：`MediaRepository.requestStsCredential(scope, contentType, count)` → 调 `POST /media/sts-credentials`，dio + ProblemDetail 映射。`StsCredential` 不可变模型。
  - [ ] `OssUploader`：用 STS 凭证客户端直传 OSS（PutObject），**不经后端**；返回落桶对象的应用自有 URL（公开桶=CDN base + key，私密桶=仅返回 key 供后端签名）。
- [ ] **F3. 压缩 + EXIF 剥离（客户端主路径）** (AC: 1, 2)
  - [ ] `shared/utils/image_processor.dart`：选图后压缩到 **≤10MB**（按需降质/降分辨率循环），重编码过程**丢弃 EXIF（含 GPS）**。单测：带 GPS 的测试图处理后无 GPS、体积 ≤10MB。
- [ ] **F4. 权限申请状态机 + 拒绝引导** (AC: 4)
  - [ ] `shared/utils/media_permission.dart`：首次点上传按场景请求相机/相册权限；`granted`→继续；`denied/permanentlyDenied`→弹引导对话框（i18n key `media.permissionDeniedTitle/Body`、按钮「去设置」`media.openSettings`），点击调 `openAppSettings()`。文案走 .arb（id/en 双套），**无写死字符串**。
- [ ] **F5. 可复用上传 widget/封装** (AC: 1, 4)
  - [ ] 提供 `MediaPickAndUpload`（或 provider 用例）：选图→权限→压缩剥离→请求 STS→直传→回调 URL/key，**供 2.2/2.3/2.5/Epic4 复用**。单/多图（≤9）模式，单张超 10MB 压不下则提示。

### 🟨 联调验收子任务（含 L2 真凭证）

- [ ] **J1. STS→直传双桶闭环（L2）** (AC: 1 / **L2**)
  - [ ] 配真实阿里 STS/OSS 雅加达测试桶凭证（env，仅本地/CI secret）→ 前端请求 PUBLIC 凭证直传公开桶成功、经 CDN URL 可访问；请求 PRIVATE 凭证直传私密桶成功。
  - [ ] **越权验证**：用 PUBLIC 凭证尝试 Put 到 `private/...` 或他人前缀 → 被 OSS 拒（403/AccessDenied），证明 scope 受限。
- [ ] **J2. 签名 URL 私密访问（L2）** (AC: 3 / **L2**)
  - [ ] 私密桶对象：无签名直接 GET=403；`SignedUrlService` 签出 URL GET=200；等 TTL 过期后 GET=403。
- [ ] **J3. EXIF 剥离端到端（L2）** (AC: 2 / **L2**)
  - [ ] 真机相机拍/相册选含 GPS 照片 → 走 F3 处理直传 → 从桶取回用 exif 工具断言无 GPS。
- [ ] **J4. 权限拒绝引导（L2）** (AC: 4 / **L2**)
  - [ ] 模拟器/真机首次上传触发系统权限弹窗；拒绝→出引导→「去设置」跳系统设置。
- [ ] **J5. 隐私日志审查（L0/L1）** (AC: 3)
  - [ ] grep 运行日志确认无签名 URL / STS secret / token 落 INFO；MockMvc 验 STS 端点需 JWT、限流生效、返回信封字段齐全。

---

## Dev Notes

### 架构约定（本 Story 必落实）

**媒体三层（隐私边界，架构 §Data Architecture）：**
- ① **公开桶**（阿里 OSS 雅加达 + 阿里 CDN）：Feed/档案/H5 名片图。客户端 STS 直传，访问走 CDN URL。
- ② **私密桶**（仅短 TTL 签名 URL）：AI 分诊图、健康历史图。客户端 STS 直传，读取必须经 `SignedUrlService`。
- ③ 腾讯 IM 托管（聊天图/视频）——本 Story 不涉及；其「复制到 ②」桥接由 2.5 的 `ImToOssArchiver` 完成（本 Story 仅占位类）。
- **客户端 STS 临时凭证直传 OSS，不经后端**（减少印尼↔德国往返、降后端带宽）。

**命名/目录**：DB 无新表（本 Story 不建表）；STS/签名属基础设施，落 `com.petgo.shared.media`。API `POST /api/v1/media/sts-credentials`（资源化命名）。DTO `record` + `NON_NULL`；时间 ISO-8601 UTC。前端 feature/工具落 `core/`+`shared/utils`（跨 feature 复用）。

**错误**：STS/签名异常 → ProblemDetail（type `https://petgo/errors/media-credential` 等）；客户端 dio 拦截 → 本地化（上传失败按 UX「底部 toast 3s」）。绝不外泄 OSS 原始错误/堆栈。

### 强制护栏（违反即返工）

- **禁 MQ / 通用缓存层 / 新中间件**：Redis 仅用于本 Story 的 STS 端点限流/幂等，**不缓存签名 URL**（签名 URL 短 TTL、含敏感，缓存=泄漏面）。
- `ddl-auto=validate`（本 Story 无迁移，勿顺手建表）。
- **凭证全部 env 注入**：OSS AccessKey、STS RoleArn、私密桶名等绝不硬编码/入库/进 .env 真值。
- **对外标识不可枚举**：对象 key 前缀含 userId 但**对外公开 URL 不暴露顺序业务 id**；私密对象绝不给公开 URL，**只走签名 URL**。
- **EXIF GPS 必剥离**（G-4），客户端重编码主路径。
- **日志/对象存储 URL 不落 PII/健康数据/令牌/签名 URL**（架构 §Enforcement）。
- STS **scope 最小化**：Policy 收窄到目标桶 + 该用户前缀 + 仅 PutObject，**不给 list/读他人**（NFR-7）。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不实现任何具体业务上传 UI/接口（头像 2.2、内容图 2.3、健康图 2.5、分诊图 Epic4）。
- ❌ 不做 IM→OSS 实际复制（2.5，本 Story 仅 `ImToOssArchiver` 占位）。
- ❌ 不做视频/VOD/转码（V1 范围外）。
- ❌ 不建任何数据库表。
- ✅ 只做：双桶配置 + `AliyunOssClient`/`StsService`/`SignedUrlService` + STS 端点（限流/JWT）+ 客户端压缩/EXIF 剥离/直传/权限工具 + 日志护栏 + `ImToOssArchiver` 占位。

### Project Structure Notes

- 后端：`com.petgo.shared.media.{AliyunOssClient, StsService, SignedUrlService, ImToOssArchiver(占位)}`；STS 端点 `MediaController` 置于 `shared/media`（跨模块共用基础设施，非单一业务模块）。配置块 `application-*.yml` + `MediaProperties`(@ConfigurationProperties)。
- 前端：复用工具置 `lib/core/media/`（或 `lib/features/media/data`）+ `lib/shared/utils/{image_processor, media_permission}`；上传封装 widget/provider 供各 feature import。
- 与架构 §Complete Project Directory Structure 的 `shared/media/{AliyunOssClient,StsService,SignedUrlService,ImToOssArchiver}` 完全对齐。

### References

- [Source: architecture.md#Data Architecture] — 媒体三层、双桶、STS 直传、桥接规则、签名 URL。
- [Source: architecture.md#Authentication & Security] — 私密桶短 TTL 签名 URL、STS 限定 scope、密钥 env 注入。
- [Source: architecture.md#Integration Points] — 阿里 OSS 雅加达/STS/CDN → `shared/media/*`；客户端 STS 直传。
- [Source: architecture.md#Gap Analysis G-4] — 上传链路剥离 EXIF GPS，归入 `shared/media`。
- [Source: architecture.md#Enforcement Guidelines] — 禁 MQ/缓存层、env 注入、签名 URL/不可枚举 token、日志不落 PII/健康/令牌。
- [Source: architecture.md#Frontend Architecture] — 图片客户端压缩 ≤10MB 后 STS 直传。
- [Source: epics.md#Story 2.1] — 四条原始 AC（Given/When/Then）。
- [Source: epics.md#Epic 2] — 媒体三层作为后续上传场景共享地基。

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

- 记录所用阿里 OSS/STS SDK 实际版本（若与 Java21/SB4 有兼容调整）。
- 记录 L2 验证所用测试桶/RoleArn 来源（仅描述，不写真实凭证）。

### File List
