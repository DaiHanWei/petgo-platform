---
title: 'APP 全面 Mock 模式——无后端离线全流程假数据展示'
type: 'feature'
created: '2026-06-03'
status: 'done'
baseline_commit: 'bccdfd5'
context:
  - '{project-root}/petgo_app/lib/core/network/api_paths.dart'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 现状 `PETGO_DEV_STUB_LOGIN` 只跳过 Google OAuth、仍真打后端;无网/无后端时所有 list 页转圈或报错,无法离线演示全部页面。需让 APP 端可彻底掐断后端、用假数据填充每个页面、无网跑通全流程,且创建类操作即时在 list 回显(重启 APP 后可消失=内存态)。

**Approach:** 在 Dio 单一咽喉点装一个 **MockInterceptor**,mock 开启时 `handler.resolve` 短路所有请求、由内存 **MockBackend** 按端点返回假数据,永不触网;写入类端点改 MockBackend 内存态,后续读端点反映变更。开关 `kMockMode = kDebugMode && bool.fromEnvironment('PETGO_MOCK', defaultValue:true)`——**debug 构建默认开启 mock**(无需 dart-define 即可离线演示);连真后端用 `--dart-define=PETGO_MOCK=false`;**release 恒不 mock**(kDebugMode 护栏)。唯一不走 dio 的 OSS 直传:mock 下 override `mediaUploadUseCaseProvider` 直接返回假 URL。

## Boundaries & Constraints

**Always:** mock 关闭(`--dart-define=PETGO_MOCK=false`)时行为与现状**完全一致**(拦截器只在 `kMockMode` 为真时介入);**debug 默认开启 mock**(用户决策);响应 JSON 形状严格匹配各 model 的 `fromJson`(字段名/可空/分页信封/时间 ISO-8601);写入→读取联动按既定关系生效;内存态仅进程内(重启重置)。

**Ask First:** (已决)mock 默认开启 = debug 是,release 否(kDebugMode 护栏);(已决)不持久化,重启消失。

**Never:** 不改各 repository/页面业务代码(mock 在 dio 拦截层+1 个 provider override 完成);不引第三方 mock 框架/新中间件;不动后端;**release 恒不 mock**(kDebugMode 护栏,即便 PETGO_MOCK=true 也不生效);不破坏 mock 关闭时的真实链路。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| mock 关闭 | `--dart-define=PETGO_MOCK=false`(或 release) | 拦截器不注册,真打后端(现状) | N/A |
| mock 默认开 | debug 构建无 dart-define | 拦截器生效,离线假数据 | N/A |
| 读端点 | mock 开,GET /content-posts | 返回内存种子分页信封 {items,nextCursor,hasMore} | N/A |
| 写端点即时回显 | mock 开,POST 发帖 | 返回 {id};随后 GET feed/me-posts 含该新帖(置顶) | N/A |
| 204/null 语义 | GET /consult-sessions/active 无会话 | 返回 204(前端判无进行中) | N/A |
| 裸数组 | GET /vet/.../waiting | 返回 JSON 数组(非信封) | N/A |
| 登录链路 | dev 桩 POST /auth/google | 返回 token + 全解锁 profile(petStatus=A,hasPetProfile,onboardingCompleted) | N/A |
| 未建 mock 的端点 | mock 开,命中未覆盖路径 | 返回合理空成功(空信封/200),并 log 警告 | 不抛错、不触网 |
| 媒体上传 | mock 开,上传图片 | mediaUploadUseCase 返回假 URL,不打 OSS | N/A |
| 重启 | mock 开,重启 APP | 内存态重置回初始种子(创建项消失) | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/lib/core/network/api_paths.dart` -- 端点真源;mock 路由匹配以此为准(39 端点)
- `petgo_app/lib/core/network/dio_client.dart` -- `dioProvider`;mock 开时挂 `MockInterceptor`(置于 AuthInterceptor 之前)
- `petgo_app/lib/core/mock/mock_backend.dart`(新增) -- 内存 store + 种子数据 + 每端点 handler(读/写/联动)
- `petgo_app/lib/core/mock/mock_interceptor.dart`(新增) -- Dio Interceptor:`onRequest` 经 MockBackend 解析 → `handler.resolve(Response)`;含 204/404/裸数组/延迟
- `petgo_app/lib/core/mock/mock_config.dart`(新增) -- `const kMockMode = bool.fromEnvironment('PETGO_MOCK')`
- `petgo_app/lib/features/media/...`(media use case provider) -- mock 开时 override 返回假 URL
- `petgo_app/lib/app.dart` -- ProviderScope:mock 开时加 media override(其余靠拦截器)

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/lib/core/mock/mock_config.dart` -- `kMockMode = kDebugMode && bool.fromEnvironment('PETGO_MOCK', defaultValue: true)`(debug 默认开,release 恒关)
- [x] `petgo_app/lib/core/mock/mock_backend.dart` -- 内存 store + 种子;为 ApiPaths 全部端点实现 handler,返回与 `fromJson` 匹配的 JSON;实现写入→list 联动(发帖/评论/点赞/改昵称/改状态/建档/分诊/会话/通知已读…);覆盖特例(204 的 active/pending-rating、裸数组 waiting、404 的 pet-profiles/me、body 字段名);种子覆盖 feed(分类+分页)、详情+评论、我的发布、宠物档案+时间线、问诊历史(AI+兽医,绿/黄/红)、通知(读/未读)、可演示绿黄红分诊结果
- [x] `petgo_app/lib/core/mock/mock_interceptor.dart` -- `onRequest` 匹配 method+path → MockBackend → `handler.resolve`;未覆盖端点返回空成功+warning;模拟 ~150ms 延迟使加载态可见;不触网
- [x] `petgo_app/lib/core/network/dio_client.dart` -- `if (kMockMode) dio.interceptors.add(MockInterceptor(...))`(早于 AuthInterceptor)
- [x] media use case provider override(mock 开)-- 返回假 URL,跳过 OSS 直传
- [x] `petgo_app/lib/app.dart` -- ProviderScope `overrides`(mock 开时含 media override)
- [x] 登录种子:mock /auth/google 返回 petStatus=A + hasPetProfile=true + onboardingCompleted=true,使两道门控(router redirect + requireLogin)全通,解锁所有用户侧页面

**Acceptance Criteria:**
- Given `--dart-define=PETGO_MOCK=true` 构建且模拟器**断网**,when 点 dev 桩登录,then 进首页且 feed 有假数据,无转圈/报错
- Given mock 开,when 逐一进 home/profile(成长档案)/triage/me/详情/通知/问诊各页,then 每页均有假数据展示(无空转)
- Given mock 开,when 发帖并返回,then 首页 feed 顶部立即出现该帖(重启后消失)
- Given mock 开,when 发评论,then 详情页评论区立即出现该评论
- Given mock 开,when 走 AI 分诊上传→结果,then 可见绿/黄/红结果且问诊历史新增一条
- Given `--dart-define=PETGO_MOCK=false`,when 构建运行,then 行为与现状一致(真打后端),`flutter test` 不回归
- Given release 构建,when 运行,then 即便无 dart-define 也**不** mock(kDebugMode 护栏)
- Given `flutter analyze`,then 无 error

## Design Notes

**拦截层而非 repo override**:每个页面的数据都经 `dioProvider` 的 Dio,故在 Dio 加一个 Interceptor 即可全覆盖,无需为 ~15 个 repo 各写 mock 类。`onRequest(options, handler)` 中:
```dart
final res = MockBackend.instance.handle(options); // 返回 Response 或 null
if (res != null) { handler.resolve(res); return; } // 短路,不 next()
```
**写入→读取联动(内存态)**:MockBackend 持有可变 `List`(feedPosts/commentsByPost/notifications/consultHistory/myPosts/timeline…)。发帖 POST 生成新 id 并 unshift 到 feedPosts+myPosts(有 petId 再加 timeline);评论 POST 加入 commentsByPost[id] 并 detail.commentCount+1;点赞改 likeCount;改昵称/状态改 currentProfile 并反映到作者投影/feed 过滤。
**特例**:`active`/`pending-rating` 无数据返回 `Response(statusCode:204,data:null)`;`/vet/...waiting` 返回裸 `List`;`GET /pet-profiles/me` 无档案返回 404(前端转 null,但默认种子给一份非空以便展示);`/me/posts` item 用 `body` 字段。
**时间**:种子用相对 `DateTime.now().subtract(...)` 生成 ISO 字符串(注意脚本环境禁 Date.now,但运行期 APP 内 DateTime 正常)。
**兽医侧**:用户/兽医态互斥;mock 同样拦截 `/auth/vet/login` 与 `/vet/*`,登出后走 `/vet/login` 即可演示兽医侧。

## Verification

**Commands:**
- `cd petgo_app && flutter analyze` -- expected: No issues
- `cd petgo_app && flutter test` -- expected: 既有用例全绿(mock 默认关闭不影响)
- `cd petgo_app && flutter build apk --debug --dart-define=PETGO_MOCK=true` -- expected: 构建成功

**Manual checks(每页检查,模拟器可断网):**
- 逐屏 screencap:home/profile/triage/me/content detail/notifications/triage upload+result(绿黄红)/consult entry/publish/onboarding/vet login+workbench,确认均有假数据且操作即时回显
