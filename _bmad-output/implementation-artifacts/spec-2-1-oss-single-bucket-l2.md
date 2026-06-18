---
title: 'Story 2.1 OSS 媒体基建——单桶 + 预签名上传 + L2 真凭证验收'
type: 'feature'
created: '2026-06-18'
status: 'done'
baseline_commit: 'ea64ab2502f16c894939f4c4ff4a0bb30b6ccbdf'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/2-1-媒体基建-oss-双桶-sts-直传-签名-url-与-exif-剥离.md'
  - '{project-root}/_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.1 的 OSS 媒体地基代码已 L0 全绿，但从未对真实阿里云验收（L2）。用户仅提供**单个私有桶 `tailtopia`**（区域 `ap-southeast-5`）与一个 RAM 子账号 AccessKey（`TailTopia_java`），无双桶、无 STS RoleArn。

**Approach:** 双桶降级为**单桶 + 对象级 ACL**：PUBLIC/PRIVATE 两域都指向 `tailtopia`，仅前缀区分；PUBLIC 上传打 `public-read` ACL 走公网 URL，PRIVATE 私有走读签名 URL。客户端直传凭证从「STS 临时凭证」改为**后端预签名上传 URL**（用户决策，省去 RAM 角色/STS）：后端用 env 注入的 AccessKey 现签一张限定对象 + 限定头 + 短 TTL 的预签名 PUT URL，客户端凭此直传（字节不绕后端），**真 AccessKey 始终只在后端**。以一次性、env 注入、不入库的脚本完成后端 L2 闭环。

## Boundaries & Constraints

**Always:**
- 单桶 `tailtopia`，PUBLIC/PRIVATE 仅靠对象前缀 + ACL 隔离。对象 key **服务端生成**、不可枚举、收窄在 `<scope>/<userId>/` 前缀下。
- 预签名 URL 绑定单一对象 key + Content-Type（公开域再绑 `x-oss-object-acl:public-read`）；客户端改对象/漏头即 SignatureDoesNotMatch。
- 所有凭证 **env 注入，绝不入库/硬编码/进 .env.example 真值**；脚本读 env，不把密钥写进仓库或日志。
- 私密对象绝不拼公开 URL，只走读签名 URL（`SignedUrlService`）。
- 既有 L0 单测保持绿；新增/改动逻辑补单测。

**Ask First:**
- 若桶级「阻止公共访问」开着致 public-read 拿不到 → HALT，告知用户关闭桶级 BPA 或改回双桶。

**Never:**
- 绝不把 AccessKey 下发到客户端（客户端只拿单对象预签名 URL）。
- 不建数据库表、不引 MQ/缓存中间件、不缓存签名 URL。
- 不改 Story 2.1 业务边界（不实现头像/内容/健康图等具体上传场景）。
- 不把脚本/真实凭证 commit 进仓库。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PUBLIC 签发 | scope=PUBLIC, userId=42, ct=image/jpeg | uploadUrl(预签名)；objectKey=`public/42/<rand>.jpg`；headers含 Content-Type+`x-oss-object-acl:public-read`；publicUrl=CDN/key | — |
| PRIVATE 签发 | scope=PRIVATE, userId=7 | objectKey=`private/7/<rand>.jpg`；headers仅 Content-Type；publicUrl=null | — |
| PUBLIC 直传可公开读 | 客户端 PUT 预签名 URL + 原样头 | 公网 GET=200 | — |
| PRIVATE 直传 + 读签名 | 客户端 PUT 私密预签名 URL | 无签名 GET=403；读签名 GET=200；过期后 403 | — |
| 改对象路径复用 URL | 拿 keyA 的预签名 URL 去 PUT keyB | OSS 拒（403 SignatureDoesNotMatch），证 URL 绑定单对象 | 视为验收通过项 |
| 公开域漏配 CDN base | scope=PUBLIC, cdnBaseUrl 空 | `AppException`（fail-fast，杜绝对象落桶不可读） | 不外泄堆栈 |
| 缺桶配置 | publicBucket 空 | `AppException`（media-credential ProblemDetail） | 不外泄堆栈 |

</frozen-after-approval>

## Code Map

- `petgo-backend/.../shared/media/PresignedUploadService.java` -- 签发预签名上传票据（scope→桶/前缀/ACL/publicUrl + fail-fast）
- `petgo-backend/.../shared/media/AliyunOssClient.java` -- `presignedPutUrl`（OSS SDK 现签 PUT，公开域签入 public-read 头）
- `petgo-backend/.../shared/media/web/MediaController.java` -- `POST /media/upload-url`（JWT + 限流 → service）
- `petgo-backend/.../shared/media/dto/UploadUrlRequest|Response.java` -- 请求/票据 DTO
- `petgo-backend/.../shared/media/MediaProperties.java` -- 移除 STS 块；signed-url 加 uploadTtlSeconds
- `petgo_app/lib/features/media/data/upload_ticket.dart` -- 客户端票据模型（替 sts_credential）
- `petgo_app/lib/features/media/data/oss_uploader.dart` -- 简化为按票据 dio.put（删除 V1 签名）
- `petgo_app/lib/features/media/data/media_repository.dart` -- `requestUploadTicket`
- `petgo_app/lib/features/media/domain/media_upload_use_case.dart` -- `uploadBytes` 用票据（公开方法签名不变）
- `petgo_app/lib/core/mock/{mock_backend,mock_media}.dart` -- mock `/media/upload-url` + mock 上传器
- `scripts/local/oss_l2_accept.py`（gitignored）-- 预签名 L2 验收脚本
- `docs/oss-setup.md` -- 单桶 + 预签名配置与验收说明（替 oss-ram-setup.md）

## Tasks & Acceptance

**Execution:**
- [x] 后端：`PresignedUploadService` + `AliyunOssClient.presignedPutUrl` + `UploadUrlRequest/Response` + `MediaController /upload-url`；删除 STS 类（StsService/AliyunStsClient/DefaultAliyunStsClient/StsCredentialRequest/Response）；`MediaProperties` 去 STS 块 + 加 uploadTtl；重写 Media(Controller|Endpoint)Test；新增 PresignedUploadServiceTest。
- [x] 前端：`upload_ticket.dart` 替 `sts_credential.dart`；`oss_uploader.dart` 简化为按票据 PUT；`media_repository.requestUploadTicket`；`media_upload_use_case.uploadBytes` 用票据；`api_paths.mediaUploadUrl`；mock_backend/mock_media 改 `/upload-url`。
- [x] 测试：改 oss_uploader_test/media_upload_use_case_test/mock_contract_test/triage_upload_controller_test。
- [x] 配置/文档：`.env` 单桶值（去 STS）；`.env.example`/`application.yml` 去 STS 加 uploadTtl；`docs/oss-setup.md`；`scripts/local/oss_l2_accept.py`（gitignored）。

**Acceptance Criteria:**
- [x] Given 代码改动已落，when 后端 media 单测（19）+ `flutter test test/media test/triage ...` + `flutter analyze`，then 全绿、analyze 无问题。（注：`login_page_test`/`vet_*` 4 例为 main 既有 i18n 失败，与本改动无关。）
- [x] Given .env 配齐（仅需 AccessKey + 单桶，无 RoleArn），when 运行 `python3 scripts/local/oss_l2_accept.py`，then **7/7 PASS**（J1 公网读 + J1b URL 绑定对象 + J1c 私密直传 + J2 读签名三态）。
- [x] Given 验收，when `git status`，then 仓库内无真实凭证/脚本被跟踪（`.env`、`scripts/local/` gitignored）。

## Spec Change Log

- **[2026-06-18 · 人工重谈 frozen Approach] STS 直传 → 后端预签名 URL。** 触发：用户指出只需 AccessKey 即可访问桶、STS/RAM 角色摩擦大（RAM 子账号缺 AssumeRole 权 + 角色未建致 `EntityNotExist.Role`）。改为后端用 AccessKey 现签预签名 PUT URL（真 key 仍只在后端，安全等价），删除全部 STS 代码与 RAM 角色文档。已对真实桶验证预签名 PUT(含 public-read)/读签名三态/URL 绑定对象均通过。**KEEP**：单桶 + 对象级 ACL 前缀隔离、公开域 fail-fast（漏 CDN base 即拒）、对象 key 服务端生成不可枚举、桶级 BPA 必须关闭这几点在两方案下都成立，须保留。

## Design Notes

**预签名 + 单桶 ACL 的关键点**：公开域 PUT 须签入并发送 `x-oss-object-acl:public-read`；Content-Type 也计入预签名——客户端 PUT 必须发**与签名完全一致**的 Content-Type + 头，否则 OSS `SignatureDoesNotMatch`。预签名 URL 绑定单一对象 key（改路径即失配），天然防越权写他人/他域前缀，无需 STS scope 策略。读取私密对象走 `SignedUrlService` 读签名 URL（同一 key 现签）。

**桶级 Block Public Access（验收期发现，已处置）**：桶 `tailtopia` 原 BPA=true，挡 public-read（`Put public object acl is not allowed`）。经用户授权用 `PUT ?publicAccessBlock`(false) 关闭桶级 BPA（账号级=false）。**方案依赖此设置保持关闭**。

## Verification

**Commands:**
- `cd petgo-backend && ./mvnw -B -Dtest='PresignedUploadServiceTest,MediaControllerTest,SignedUrlServiceTest,AliyunOssClientTest,ImToOssArchiverTest' test` -- expected: 19 绿
- `cd petgo_app && flutter test test/media/ test/triage/triage_upload_controller_test.dart test/support/mock_contract_test.dart` -- expected: all passed
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `python3 scripts/local/oss_l2_accept.py` -- expected: 7/7 PASS

**Manual checks:**
- `git status` + `git check-ignore petgo-backend/.env scripts/local/oss_l2_accept.py` -- 确认凭证与脚本均被忽略。
