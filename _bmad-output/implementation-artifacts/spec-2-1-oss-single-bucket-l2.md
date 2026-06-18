---
title: 'Story 2.1 OSS 媒体基建——单桶适配 + L2 真凭证验收'
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

**Problem:** Story 2.1 的 OSS 媒体地基代码已 L0 全绿，但从未对真实阿里云验收（L2）。用户现仅提供**单个私有桶 `tailtopia`**（区域 `ap-southeast-5`）与主账号 AccessKey，而代码假设**公开桶+私密桶双桶**——直接配置跑不通：PUBLIC 域对象落到私有桶后对外不可访问。

**Approach:** 把双桶寻址降级为**单桶 + 对象级 ACL**：PUBLIC/PRIVATE 两域都指向 `tailtopia`，仅前缀区分（`public/{userId}/`、`private/{userId}/`）；PUBLIC 上传时给对象打 `public-read` ACL 走 OSS 公网 URL，PRIVATE 保持私有走签名 URL。随后以**一次性、env 注入、不入库**的脚本完成后端 L2 闭环验收（STS 签发→直传→签名 URL 三态→越权拒绝）。STS RoleArn 由用户在阿里云控制台创建后提供。

## Boundaries & Constraints

**Always:**
- 单桶 `tailtopia`，PUBLIC 与 PRIVATE 仅靠对象前缀 + ACL 隔离；STS 策略仍按 NFR-7 最小化收窄到「该用户该前缀」。
- PUBLIC 域 STS 策略允许 `oss:PutObject` + `oss:PutObjectAcl`（仅为打 `public-read`）；PRIVATE 域仅 `oss:PutObject`。
- 所有凭证（AccessKey/Secret、RoleArn）**env 注入，绝不入库/硬编码/进 .env.example 真值**；验收脚本读 env，不把密钥写进仓库或日志。
- 对象 key 不可枚举（沿用现有随机 token）；私密对象绝不拼公开 URL，只走 `SignedUrlService`。
- 既有 L0 单测保持绿；新增/改动逻辑补单测。

**Ask First:**
- 若验收发现单桶 + 对象 ACL 因桶「阻止公共访问」开关而拿不到公开读 → HALT，告知用户需在控制台关闭该桶的 block-public-access 或改回双桶。
- 若用户提供的 RoleArn 信任策略不允许该主账号 AssumeRole（STS 报 `NoPermission`/`InvalidParameter`）→ HALT，给出修正信任策略，不擅自改用户账号。

**Never:**
- 绝不把主账号 AccessKey 下发到客户端（只能下发 STS 临时凭证）。
- 不新建数据库表、不引入 MQ/缓存中间件、不缓存签名 URL。
- 不改动 Story 2.1 的业务边界（不实现头像/内容/健康图等具体上传场景）。
- 不把验收脚本/真实凭证 commit 进仓库。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| PUBLIC 签发 | scope=PUBLIC, userId=42 | bucket=`tailtopia`, uploadDir=`public/42/`, cdnBaseUrl=OSS 公网 host；policy 含 `oss:PutObject`+`oss:PutObjectAcl`，资源限 `tailtopia/public/42/*` | — |
| PRIVATE 签发 | scope=PRIVATE, userId=7 | bucket=`tailtopia`, uploadDir=`private/7/`, cdnBaseUrl=null；policy 仅 `oss:PutObject`，资源限 `tailtopia/private/7/*` | — |
| PUBLIC 直传可公开读 | 用 PUBLIC 临时凭证 PUT `public/42/x.jpg` 带 `x-oss-object-acl:public-read` | 公网 GET 该 URL = 200 | — |
| PRIVATE 直传 + 签名读 | 用 PRIVATE 临时凭证 PUT `private/7/y.jpg` | 无签名 GET=403；签名 URL GET=200；TTL 过期后 GET=403 | — |
| 越权写 | 用 PUBLIC 临时凭证 PUT 到 `private/...` 或他人前缀 | OSS 拒绝（403 AccessDenied） | 视为验收通过项 |
| 缺桶配置 | publicBucket 空 | `AppException`（media-credential ProblemDetail） | 不外泄堆栈 |

</frozen-after-approval>

## Code Map

- `petgo-backend/.../shared/media/StsService.java` -- `buildPolicy` 需按 scope 区分 action（PUBLIC 加 `oss:PutObjectAcl`）
- `petgo-backend/.../shared/media/MediaScope.java` -- 可加 `requiresPublicReadAcl()` 语义辅助（或在 StsService 内分支）
- `petgo-backend/.../shared/media/AliyunOssClient.java` -- 单桶下 `publicUrl` 仍用 cdnBaseUrl，无需改；确认 `publicExifStrippedUrl` 兼容
- `petgo-backend/src/test/java/.../shared/media/StsServiceTest.java` -- 改/加断言：PUBLIC policy 含 `PutObjectAcl`、PRIVATE 不含
- `petgo_app/lib/features/media/data/oss_uploader.dart` -- PUBLIC 上传加 `x-oss-object-acl:public-read` 头并纳入 StringToSign（按字母序）
- `petgo_app/test/media/oss_uploader_test.dart` -- StringToSign 含 object-acl 头的纯函数单测
- `petgo-backend/.env` -- 填 OSS 单桶/区域/CDN base/AccessKey；`STS_ROLE_ARN` 待用户提供（gitignored）
- `_bmad-output/implementation-artifacts/2-1-...md` -- 验收后回填 L2 完成记录
- `docs/oss-ram-setup.md`（新增）-- 给用户的 RAM 角色创建步骤 + 信任/权限策略 JSON

## Tasks & Acceptance

**Execution:**
- [x] `StsService.java` -- `buildPolicy(scope, bucket, uploadDir)`：PUBLIC 域 Action 加 `oss:PutObjectAcl`，PRIVATE 维持仅 `oss:PutObject`；资源前缀不变。
- [x] `StsServiceTest.java` -- 拆为 `publicPolicyAllowsPutAndPutAclScopedToUserPrefix`（含 `PutObjectAcl`）+ `privatePolicyIsPutOnlyNoAcl`（不含）。
- [x] `oss_uploader.dart` -- `stringToSign` 加可选 `objectAcl`，按字母序在 `x-oss-security-token` 前插 `x-oss-object-acl:<v>\n`；`put` 对公开域（cdnBaseUrl!=null）自动打 `public-read` 并加同名请求头。
- [x] `oss_uploader_test.dart` -- 补 objectAcl 计入签名串 + 字母序断言。
- [x] `.env` -- 单桶/区域/endpoint/CDN base/AccessKey 已填；`STS_ROLE_ARN` 占位等用户。
- [x] `docs/oss-ram-setup.md` -- RAM 角色信任策略 + `oss:PutObject/PutObjectAcl` 受限权限策略 JSON + 控制台/CLI 步骤。
- [x] `scripts/local/oss_l2_accept.py`（gitignored）-- 纯 stdlib，读 env 跑 J1/J1b/J1c/J2，PASS/FAIL，密钥不打印。

**Acceptance Criteria:**
- [x] Given 单桶代码改动已落，when 后端 media 单测（14）+ `flutter test test/media/`（6）+ `flutter analyze`，then 全绿、analyze 无问题。
- [~] Given 用户已提供 RoleArn 且 .env 配齐，when 运行 L2 脚本，then J1+J2 全 PASS。**STS 腿待 RoleArn**；不依赖 STS 的腿（主账号 key 现场验证）已通过：PUT public-read=200、公网 GET=200、private PUT=200、无签名 GET=403、签名过期=403。
- [x] Given 验收，when `git status`，then 仓库内无真实凭证/RoleArn/验收脚本被跟踪（`.env`、`scripts/local/` 均 gitignored）。

## Design Notes

**单桶 ACL 的关键点**：私有桶中对象默认私有；PUBLIC 域上传须在 PUT 时带 `x-oss-object-acl: public-read`。OSS V1 签名要求所有 `x-oss-` 开头的头都计入 CanonicalizedOSSHeaders 并按字母序排列——`x-oss-object-acl` 字母序在 `x-oss-security-token` **之前**，StringToSign 必须为：

```
PUT\n\n<contentType>\n<date>\nx-oss-object-acl:public-read\nx-oss-security-token:<tok>\n/<bucket>/<key>
```

漏掉或顺序错 → OSS 返回 `SignatureDoesNotMatch`。PRIVATE 域不带该头，签名串保持现状。

**为何 PUBLIC 需 `oss:PutObjectAcl`**：STS 临时凭证按收窄策略行权；带 ACL 的 PutObject 在 RAM 鉴权时会校验 `oss:PutObjectAcl`，故 PUBLIC 策略须显式加该 action（仍限同一前缀，最小权限不破）。

**STS RoleArn 阻塞**：用户选择「自己建好 RoleArn 再给我」。在拿到前，单桶代码改动 + 单测 + 脚本可先完成；L2 实跑等 RoleArn。RAM 角色信任策略须允许主账号 `5967981790439929` AssumeRole，权限策略给该桶前缀的 OSS 写权（详见 `docs/oss-ram-setup.md`）。

**⚠️ 验收期发现（已处置）——桶级 Block Public Access**：首次实测 `PUT x-oss-object-acl:public-read` 被拒（403 `Put public object acl is not allowed`）。根因：桶 `tailtopia` 桶级「阻止公共访问」=true（账号级=false）。经用户授权，用主账号 key 一条 `PUT ?publicAccessBlock`(false) 关闭桶级 BPA → 复测 public-read 写入=200、公网 GET=200。**单桶+对象ACL 方案依赖此设置保持关闭**；若日后重新开启，公开图将再次不可公网读。账号 UID `5967981790439929`。

**L2 已验证（主账号 key 现场，无需 STS）**：public-read PUT=200 / 公网 GET=200 / private PUT=200 / 无签名 GET=403 / 签名过期 GET=403 → 证明 OSS V1 签名（含 object-acl 字母序）、对象 ACL、签名 URL 逻辑全部正确。**仅剩 STS 临时凭证三腿（J1/J1b 越权/J1c）等 RoleArn 跑脚本闭环。**

## Verification

**Commands:**
- `cd petgo-backend && ./mvnw -B -Dtest='StsServiceTest,SignedUrlServiceTest,AliyunOssClientTest,MediaControllerTest' test` -- expected: BUILD SUCCESS，全绿
- `cd petgo_app && flutter test test/media/` -- expected: all passed
- `cd petgo_app && flutter analyze` -- expected: No issues found
- L2 脚本（RoleArn 就绪后）`python3 scripts/local/oss_l2_accept.py` -- expected: J1/J1b/J1c/J2 全 PASS

**Manual checks:**
- `git status` + `git check-ignore petgo-backend/.env scripts/local/oss_l2_accept.py` -- 确认凭证与脚本均被忽略，无真实密钥进版本库。

## Suggested Review Order

**单桶 + 对象级 ACL（核心设计）**

- 入口：STS 策略按 scope 收窄动作，PUBLIC 加 PutObjectAcl 仍限同一前缀
  [`StsService.java:87`](../../petgo-backend/src/main/java/com/tailtopia/shared/media/StsService.java#L87)

- 公开域 fail-fast：漏配 CDN base 即拒签，杜绝前后端 ACL 判据不一致
  [`StsService.java:44`](../../petgo-backend/src/main/java/com/tailtopia/shared/media/StsService.java#L44)

- 前端 OSS V1 签名串：object-acl 按字母序排在 security-token 之前（错序即 SignatureDoesNotMatch）
  [`oss_uploader.dart:53`](../../petgo_app/lib/features/media/data/oss_uploader.dart#L53)

- 公私域判据 + 请求头：公开域打 public-read，私有域不打
  [`oss_uploader.dart:86`](../../petgo_app/lib/features/media/data/oss_uploader.dart#L86)

**L2 验收脚本（真凭证闭环，gitignored）**

- 越权断言看 Code=AccessDenied 而非裸 403，证 scope 收窄真生效
  [`oss_l2_accept.py:195`](../../scripts/local/oss_l2_accept.py#L195)

- GET 重试抗写后最终一致性假失败 + 内联策略镜像后端
  [`oss_l2_accept.py:58`](../../scripts/local/oss_l2_accept.py#L58)

**配套交付**

- RAM 角色信任/权限策略 + RoleArn 产出步骤
  [`oss-ram-setup.md`](../../docs/oss-ram-setup.md)

**测试**

- PUBLIC 含 PutObjectAcl / PRIVATE 不含 / 公开域漏 CDN 抛错
  [`StsServiceTest.java:58`](../../petgo-backend/src/test/java/com/tailtopia/shared/media/StsServiceTest.java#L58)

- 签名串含 object-acl 且字母序正确
  [`oss_uploader_test.dart:22`](../../petgo_app/test/media/oss_uploader_test.dart#L22)
