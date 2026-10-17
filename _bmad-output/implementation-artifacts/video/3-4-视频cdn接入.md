---
baseline_commit: 140b698e
---

# Story 3.4：视频 CDN 接入

Status: review

> 专题线 video · Epic 3 · 后端 + 运维配置。分支 `feat/video`。依赖 1.4（`CdnCacheRefresher` / `AliyunCdnCacheRefresher` / `NoopCdnCacheRefresher`、`MediaConfig` hasText 开关、对账器刷新调用）、2.1（`VideoUrlResolver.publicUrl` 走 CDN 域名）。
> 权威顺序：决策日志-video.md > PRD-video.md > architecture-video-delta.md > epics-video.md。
> **生产上线前提；依赖阿里云企业认证（PRD §10 第 2 条）。开发与 stag 验收不等企业认证。**

## Story

As a 平台，
I want 配置好 CDN 之后，视频改从 CDN 下发，下架时刷新 CDN 缓存，
so that 生产环境的视频播放更快、流量更便宜，下架后的文件也能及时失效。

**范围**：FR-20；架构 AD-11；NFR-6、NFR-8、NFR-9；决策 V-38。

## Acceptance Criteria

1. **刷新请求【L0 打桩】** `AliyunCdnCacheRefresher.refresh(url)` 经 `aliyun-java-sdk-core` 通用请求调 `RefreshObjectCaches`（`ObjectType=File`、`ObjectPath=url`）；SDK 抛 `ClientException` / `ServerException` 时抛出异常（不吞），对账器按单条失败重试（不回写 applied、记漂移）。
2. **配置开关【L0 + L1】** 配了 `media.video.cdn-base-url`：装配 Aliyun 实现，下发的 `video.url` 用 CDN 域名，封面 / 图片仍走 OSS 源站；去掉配置（含空串）→ 源站地址 + Noop，库里数据无需迁移。
3. **CDN 验收【L2：依赖企业认证】** 按配置清单开通后：视频 200 且带 `Accept-Ranges`、`nosniff`；Range 请求 206；非视频路径 403；下架后 10 分钟内 403；RAM 追加 `cdn:RefreshObjectCaches`。
4. **stag 生命周期【L2：运维】** 规则只作用于 `stag/video/` 前缀，不影响 stag 图片（具体规则待 PRD §10 第 3 条定案）。

## Tasks / Subtasks

- [x] **T1 刷新器可测**（AC 1）`AliyunCdnCacheRefresher` 增包内构造器注入 `IAcsClient`（生产仍懒建）；`ServerException` 是 `ClientException` 子类，一并转 `IllegalStateException`（只带 errorCode，不带 URL / 凭证）。
- [x] **T2 测试**（AC 1、2）：L0 `AliyunCdnCacheRefresherTest`（打桩客户端：请求形状、失败抛出）；L0 `MediaAclReconcilerTest` 补「CDN 刷新失败 → 不回写 applied、记漂移、下轮重试」；L1 `VideoCdnSwitchIntegrationTest`（配 CDN：Bean 为 Aliyun 实现、详情 / Feed 的 `video.url` 用 CDN 域名、封面仍 OSS）。
- [x] **T3 运维文档**（AC 3、4）`docs/runbooks/video-cdn-setup.md`：开通前提、CDN 域名配置清单（回源 OSS、不开私有 Bucket 回源、只放行 `(stag/)?video/*.mp4`、Range 回源、`nosniff`、IP 频控、日流量告警与封顶）、RAM 权限、环境变量、L2 验收命令、回退方式、stag 生命周期规则。`.env.example` 注释同步。
- [x] **T4 收尾**：`mvn -B clean package`（云端 `-DskipTests` + 纯单元测试）。本 Story 无迁移。

## Dev Notes

### 必须保留

- 开关用 `hasText`，**不用 `@ConditionalOnProperty`**（AD-11；空串 → Noop 的测试已在 1.4 `MediaConfigCdnRefresherTest`）。
- 封面 / 图片不走视频 CDN（V-38）；只申请 `cdn:RefreshObjectCaches` 权限。
- 凭证 env 注入，不进日志；刷新失败日志不带签名 URL / 凭证。
- 不部署任何环境、不连 stag / prod（云端规则）。

### References

- [Source: architecture-video-delta.md#AD-11]
- [Source: epics-video.md#Story 3.4]
- [Source: PRD-video.md#FR-20、§10 第 2、3 条]

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context)

### Completion Notes List

- 现状：刷新器 / Noop / `MediaConfig` hasText 开关 / 对账器刷新调用 / `VideoUrlResolver.publicUrl` 走 CDN 域名均已在 1.4、2.1 落地，本 Story 补可测性、测试与运维交付。
- **T1** `AliyunCdnCacheRefresher` 增包内构造器注入 `IAcsClient`（生产仍懒建单例）；`refresh` 失败（`ClientException`，含其子类 `ServerException`）一律转 `IllegalStateException`，信息只带 errorCode。
- **T2 测试**：L0 `AliyunCdnCacheRefresherTest`（打桩客户端：`RefreshObjectCaches` / `ObjectType=File` / `ObjectPath`=URL；客户端错误、服务端限流错误都抛出且不带 URL）；L0 `MediaAclReconcilerTest#cdnRefreshFailureIsRetriedNextRound`（刷新失败 → 不回写、记漂移、日志不带 URL；下一轮成功回写）；1.4 已有 `MediaConfigCdnRefresherTest`（空串 → Noop）。L1 `VideoCdnSwitchIntegrationTest`（配 CDN：Bean 为 Aliyun 实现、对账刷新的是 CDN 视频地址、详情与「我的发布」列表的 `video.url` 为 CDN 域名、封面仍 OSS 源站）。
- **T3** `docs/runbooks/video-cdn-setup.md`：前提（企业认证）、CDN 配置清单（回源 OSS、关闭私有 Bucket 回源、只放行 `(stag/)?video/*.mp4`、Range 回源、`nosniff`、IP 频控、日流量告警与封顶）、RAM 只追加 `cdn:RefreshObjectCaches`、环境变量、回退、L2 验收命令、stag 生命周期只作用 `stag/video/`。`.env.example` 注释指向该文档。
- **Code review（2026-10-16）**：CONFIRMED 2 条已修 —— ① runbook「回退」只删环境变量会让刷新器变 Noop，已下发的 CDN 地址在长缓存里继续 200，违反 NFR-6（改为先停用域名 / 目录刷新再去配置；换域名同理）；② story / sprint-status 收尾。PLAUSIBLE 采纳：L1 补列表出口断言；runbook 写明路径白名单用规则引擎 / EdgeScript、4xx 不缓存、HTTPS 回源、stag 路径与非 mp4 验收、刷新配额与 stuck 告警。未改（记为后续项）：`DefaultAcsClient` 关闭时未 `shutdown`；下架等接口的即时对账在请求线程里，CDN 慢时会被 SDK 读超时（默认约 10s）拖慢（正确性不受影响）。
- **L0 结果**：`mvn -B clean package -DskipTests` 通过；纯单元测试 3550 全绿（7 skipped 为既有）。本 Story 无迁移。
- **L1/L2 待本地验收**：L1 `VideoCdnSwitchIntegrationTest` 云端无 Docker 未跑，待本地；L2（依赖阿里云企业认证）CDN 开通后按 runbook §5 验：200 + `Accept-Ranges` + `nosniff`、Range 206、非视频路径 403、下架后 10 分钟内 403、RAM 刷新权限；L2 运维：stag 生命周期规则（待 PRD §10 第 3 条定案）。

### File List

- petgo-backend/src/main/java/com/tailtopia/shared/media/AliyunCdnCacheRefresher.java
- petgo-backend/src/test/java/com/tailtopia/shared/media/AliyunCdnCacheRefresherTest.java（新）
- petgo-backend/src/test/java/com/tailtopia/content/service/MediaAclReconcilerTest.java
- petgo-backend/src/test/java/com/tailtopia/content/web/VideoCdnSwitchIntegrationTest.java（新）
- petgo-backend/.env.example
- docs/runbooks/video-cdn-setup.md（新）

### Change Log

- 2026-10-16：实现 Story 3.4——CDN 刷新器可测、刷新失败重试测试、CDN 开关 L1、运维配置文档；L0 通过，L1/L2 待本地验收。
