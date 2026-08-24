---
title: '定时从 Lark 表格拉取内容自动发布社区帖'
type: 'feature'
created: '2026-08-24'
status: 'done'
context: []
baseline_commit: '202f976bca5588d4925f0c8038421196eec7981a'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 运营在 Lark 表格《List of Content for Automatic Upload》里维护待发帖子（文案+图片编号，图片文件在 Lark 云盘文件夹），目前只能人工搬运到 App 发布，费时且无状态追踪。

**Approach:** 后端新增每小时 `@Scheduled` 任务：读表 → 取「上传状态」为空的**最靠前一行**（每轮只发一条，控制放出节奏）→ 从云盘下载图片转存 OSS 公开桶 → 以随机虚拟作者身份走 `ContentService.publishTrusted`（跳过内容审核，运营=可信主体，其余校验/幂等/事件保留）发布 → 回写表格「上传状态=已发布+WIB 时间 / 发布账号=作者昵称」，DB 表按内容编号唯一去重。若首选行失败则记 FAILED+回写后顺延尝试下一行，直到成功发出一条或无待发行。

## Boundaries & Constraints

**Always:**
- 发帖走 `ContentService` 新增的 `publishTrusted(authorId, req, idempotencyKey)`（幂等键 `lark-content:{内容编号}`）：跳过 `ContentModerationService.evaluate`，其余校验/幂等/发布事件全保留；方法注释红字限定「仅运营策划内容源」；绝不直接写 content repository。
- Lark 凭证 env 注入（`LARK_CONTENT_APP_ID/SECRET`，与 bug bot 同一应用的值，但独立 env 键）；默认 `mode=off`，只有显式 `live` 才跑。
- Flyway 迁移用时间戳版本号；`ddl-auto=validate` 不动。
- OSS 对象显式带 `x-oss-object-acl: public-read`（桶级非公开读，靠对象 ACL）。
- 出站 HTTP 用 Spring `RestClient` + 显式超时，参照 `LiveTencentImClient` 范式。

**Ask First:**
- `ContentService` 只允许新增 `publishTrusted`（已获授权）；若需改动其它既有方法或 `ContentPost`，先问。
- 若需在表格里新增/重命名列。

**Never:**
- 不引入 ShedLock/Quartz/MQ 等任何新中间件（单实例 + DB 状态机幂等即可）。
- 不做前端/App 改动；不动既有 `putPublicObject` 的行为。
- 不删除 OSS 对象（决策 F21）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 正常发布 | 存在状态空的行，DB 无记录 | 仅处理最靠前一行：图转存 OSS→发 DAILY 帖(PUBLIC,petId=null)→DB 记 PUBLISHED→回写 E=「已发布 <yyyy-MM-dd HH:mm WIB>」F=作者昵称 | N/A |
| 每轮限一条 | 多行待发 | 本轮只发最靠前一条，其余留到后续小时 | N/A |
| 已发布但上次回写失败 | DB 已有 PUBLISHED，表格状态仍空 | 不重复发帖，仅补回写 | N/A |
| 图片缺失 | 首选行图片编号在云盘找不到 `{编号}.jpg` | 该行 DB 记 FAILED+原因，回写「失败：缺图」，顺延尝试下一待发行 | 顺延直到成功一条或无行可发 |
| 文案超长/文图全空 | text>1000 或无文案且无图 | 该行 FAILED+原因回写，顺延下一行 | 同上 |
| Lark API 失败 | token/读表/下载 4xx5xx | 本轮任务记日志退出，下小时重试 | 不写任何脏状态 |
| 状态列非空 | 运营手填或已处理 | 跳过该行（运营清空单元格即可重发） | N/A |
| mode=off | 默认配置 | 任务体直接 return，不调任何外部 API | N/A |
| 多图行 | 图片编号逗号分隔多个 | 全部下载转存，按序入 imageUrls（≤9） | 任一缺图→整行 FAILED，顺延下一行 |

</frozen-after-approval>

## Code Map

- `petgo-backend/src/main/java/com/tailtopia/content/service/ContentService.java` -- 参照 `publish(...)`（:273）新增 `publishTrusted`，除跳过审核外同链路
- `petgo-backend/src/main/java/com/tailtopia/shared/media/AliyunOssClient.java` -- 加一个带对象 ACL 的公开桶上传重载
- `petgo-backend/src/main/java/com/tailtopia/shared/media/MediaProperties.java` -- `normalizedKeyPrefix()` 拼 key 前缀参照
- `petgo-backend/src/main/java/com/tailtopia/shared/im/LiveTencentImClient.java` -- RestClient+超时范式参照
- `petgo-backend/src/main/java/com/tailtopia/admin/account/service/LarkOAuthClient.java` -- 同域名 token 端点写法参照（不复用，域不同）
- `petgo-backend/src/main/resources/application.yml` -- 新增 `petgo.lark-content` 配置段

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V<yyyyMMdd_HHmm>__init_lark_content_publishes.sql` -- 建表 `lark_content_publishes`（id bigint PK、content_code varchar(32) UNIQUE、image_codes varchar(255)、author_id bigint、post_id bigint null、status varchar(16) PUBLISHED/FAILED、fail_reason varchar(255) null、created_at/updated_at timestamptz）+ 列注释 -- 去重与状态追踪
- [x] `content/larksync/LarkContentSyncProperties.java` + `LarkContentSyncConfig.java` -- `@ConfigurationProperties("petgo.lark-content")`：mode(off/live)、appId、appSecret、baseUrl(默认 open.larksuite.com)、spreadsheetToken、sheetId、folderToken、cron、authorIds(默认1-20)、timeoutSeconds、rowLimit(默认500,触顶告警) -- 全 env 可覆盖
- [x] `content/larksync/LarkContentClient.java` -- RestClient 封装：tenant_access_token/internal（缓存至过期前 5min）、读值域 `A2:F200`、按行回写 `E{row}:F{row}`、列文件夹（分页）、下载文件 bytes -- Lark API 单一收口
- [x] `content/larksync/LarkContentPublish.java` + `LarkContentPublishRepository.java` -- JPA 实体+repo（findByContentCode）-- DB 状态机
- [x] `shared/media/AliyunOssClient.java` -- 新增 `putPublicObjectWithAcl(key, bytes, contentType)`：ObjectMetadata 加 `x-oss-object-acl: public-read`，返回 CDN URL；既有方法不动 -- 对象级公开读
- [x] `content/service/ContentService.java` -- 新增 `publishTrusted(authorId, req, idempotencyKey)`：与 publish 同链路但跳过 moderation.evaluate；红字注释限定运营内容源专用 -- 官方内容免审
- [x] `content/larksync/LarkContentSyncService.java` -- `@Scheduled(cron="${petgo.lark-content.sync-cron:0 7 * * * *}", zone="UTC")`：mode 检查→读表→找最靠前待发行（DB 已 PUBLISHED 的行只补回写不占额度）→下图→OSS key `{prefix}public/lark-content/{内容编号}/{图片编号}.jpg`→随机作者→publishTrusted→落 DB→回写 E/F；失败行记 FAILED+回写并顺延下一行；**每轮最多成功发布一条** -- 核心编排
- [x] `application.yml` -- 新增 `petgo.lark-content` 段（secret 默认空、mode 默认 off）-- 配置接线
- [x] `src/test/java/com/tailtopia/content/larksync/LarkContentSyncServiceTest.java` -- mock client/oss/contentService，覆盖 I/O 矩阵全部场景 -- L0 保障
- [x] `src/test/java/com/tailtopia/content/larksync/LarkRowParserTest.java` -- 行解析（空行/缺列/多图逗号/状态非空跳过）-- L0 保障

**Acceptance Criteria:**
- Given mode=off（默认），when 定时触发，then 零外部调用零日志噪音（L0：单测断言）。
- Given 表中多行状态为空且 DB 无记录，when 任务跑一轮，then **恰好一条** PUBLISHED 帖（type=DAILY、author∈配置池随机、imageUrls 为完整 CDN URL、不经审核直接 PUBLISHED），DB 唯一记录，该行 E=「已发布 <WIB时间>」F=作者昵称（L0 单测 + L1/L2 stag 实测）。
- Given 同一行再跑一轮，when 任务执行，then 不产生第二条帖（L0 单测 + L1 幂等验证）。
- Given 首选行处理抛异常，when 任务执行，then 该行 FAILED 回写且顺延下一行继续（本轮仍只成功发一条）（L0 单测）。
- `mvn -B test-compile && mvn -B test -Dtest='Lark*Test'` 绿；`bash scripts/ci/check-flyway-versions.sh origin/main` 过。

## Design Notes

- 回写时间用 WIB（Asia/Jakarta，与后台 AdminTime 口径一致），格式 `已发布 yyyy-MM-dd HH:mm WIB`。
- 「每轮一条」指成功发布一条即收工；补回写（崩溃窗口修复）不占本轮额度。

- 重试语义：FAILED 行已回写非空状态→默认不重试；运营清空「上传状态」单元格即重发（人驱动重试，无需重试计数器）。
- 崩溃窗口：publish 成功但回写失败→表格状态空、DB=PUBLISHED→下轮走「补回写」分支，不双发。
- 作者选取：配置池内随机；若该 user 不存在/非 ACTIVE 则重选，池全空视为配置错误记 FAILED。
- 图片 contentType 固定 image/jpeg（云盘文件全为 .jpg；其他扩展名按后缀映射，未知则 application/octet-stream）。

## Verification

**Commands:**
- `cd petgo-backend && mvn -B test-compile` -- expected: BUILD SUCCESS
- `mvn -B test -Dtest='LarkContentSyncServiceTest,LarkRowParserTest'` -- expected: 全绿
- `bash scripts/ci/check-flyway-versions.sh origin/main` -- expected: 无冲突

**Manual checks (if no CLI):**
- stag 部署后 `~/.env.petgo-stag` 加 `LARK_CONTENT_MODE=live` 等 env，观察下一个整点+7分：表格 E/F 回写、App feed 出现新帖、图片可见。

## Suggested Review Order

**编排主流程（设计意图入口）**

- 轮级/行级失败分级、每轮一条、熔断——整个功能的骨架语义
  [`LarkContentSyncService.java:102`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentSyncService.java#L102)

- 发帖+状态机落库同一事务，杜绝「发成未记账」崩溃窗口
  [`LarkContentSyncService.java:172`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentSyncService.java#L172)

- 内容性校验前置到下载前，不给公开桶留孤儿对象
  [`LarkContentSyncService.java:209`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentSyncService.java#L209)

**免审发布（安全攸关）**

- 与 publish 唯一差异=跳过审核；校验/幂等/事件全保留，红字限定运营源
  [`ContentService.java:383`](../../petgo-backend/src/main/java/com/tailtopia/content/service/ContentService.java#L383)

**Lark 客户端（外部边界）**

- 下载魔数校验：错误体绝不冒充图片上公开桶
  [`LarkContentClient.java:192`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentClient.java#L192)

- 回写前按编号重定位行号，防运营插删行标错行
  [`LarkContentClient.java:114`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentClient.java#L114)

- 任何 API 失败即作废 token 缓存（密钥轮换自愈）
  [`LarkContentClient.java:228`](../../petgo-backend/src/main/java/com/tailtopia/content/larksync/LarkContentClient.java#L228)

**外围**

- 对象级 public-read ACL 上传重载（既有方法零改动）
  [`AliyunOssClient.java:124`](../../petgo-backend/src/main/java/com/tailtopia/shared/media/AliyunOssClient.java#L124)

- 去重状态机建表（时间戳迁移号）
  [`V20260824_1133__init_lark_content_publishes.sql:1`](../../petgo-backend/src/main/resources/db/migration/V20260824_1133__init_lark_content_publishes.sql#L1)

- 配置段：mode 默认 off、凭证 env 注入、row-limit 可调
  [`application.yml:89`](../../petgo-backend/src/main/resources/application.yml#L89)

- 编排 16 测 + 行解析 5 测 + publishTrusted 直测 7 个
  [`LarkContentSyncServiceTest.java:1`](../../petgo-backend/src/test/java/com/tailtopia/content/larksync/LarkContentSyncServiceTest.java#L1)
