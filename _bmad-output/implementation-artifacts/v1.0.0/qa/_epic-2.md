# Epic 2：成长档案与宠物名片分享 — QA 手动测试用例集

> 编写日期：2026-06-10 | 基于 Story 2.1–2.8 最终 review 状态 + CROSS-STORY-DECISIONS 横切决策

---

## 范围说明

Epic 2 包含以下 8 个 Story，覆盖媒体基建、档案生命周期、内容发布、时间线浏览和名片分享：

| Story | 标题 | 主要页面 / 路由 |
|---|---|---|
| 2.1 | 媒体基建（OSS 双桶 / STS 直传 / 签名 URL / EXIF 剥离） | `shared/utils/` 工具层，无独立页面 |
| 2.2 | 宠物档案创建（单账号单宠物） | `/profile/create` · `pet_profile_create_page.dart` |
| 2.3 | 统一「+」发布入口与内容发布 | `/publish` · `publish_compose_page.dart` / `publish_landing_page.dart` |
| 2.4 | 成长档案 Tab 时间线与状态快捷编辑 | `/profile` · `growth_archive_page.dart` / `day_detail_page.dart` / `widgets/archive_calendar.dart` / `widgets/timeline_tiles.dart` |
| 2.5 | 问诊记录存档承接与健康事件时间线 | `archive_prompt_dialog.dart`（弹窗，被 Epic4/5 触发） |
| 2.6 | 宠物名片 H5 对外分享页 | `GET /p/{cardToken}` · `card.html` / `card_gone.html` |
| 2.7 | 档案页动效分享 FAB | `growth_archive_page.dart` 内 `share_fab.dart` |
| 2.8 | 宠物档案编辑 | `/profile/edit` · `pet_profile_edit_page.dart` |

**验证分层说明：**
- **L0**：静态编译 / widget 测试 / MockMvc，无需 Docker 或外部服务
- **L1**：需 Docker（postgres + redis）真实运行栈
- **L2**：需真实第三方凭证 / 真机 / 部署环境（OSS/STS/真机权限/H5 浏览器）

---

## Story 2.1：媒体基建——OSS 双桶、STS 直传、签名 URL 与 EXIF 剥离

### 2.1.1 STS 端点鉴权与信封字段

#### TC-2.001 未登录请求 STS 凭证被拒
- **关联**：Story 2.1 · AC1 · B4
- **页面/入口**：`POST /api/v1/media/sts-credentials`
- **前置**：无有效 JWT Token
- **步骤**：
  1. 直接调用 `POST /api/v1/media/sts-credentials`，不带 Authorization 头
- **预期**：HTTP 401，响应体为 ProblemDetail（type/title/status/detail），**不暴露堆栈**
- **层级**：L0

#### TC-2.002 有效 JWT + scope=PUBLIC 返回完整信封
- **关联**：Story 2.1 · AC1 · B3/B4
- **页面/入口**：`POST /api/v1/media/sts-credentials`
- **前置**：状态 A 登录用户，有效 JWT；环境变量 OSS/STS 已配置占位（L0 mock）
- **步骤**：
  1. 携带有效 JWT，请求体 `{"scope":"PUBLIC","contentType":"image/jpeg","count":1}`
  2. 验证响应字段
- **预期**：HTTP 200，响应含 `accessKeyId` / `accessKeySecret` / `securityToken` / `expiration`（ISO-8601 UTC）/ `bucket` / `region` / `endpoint` / `cdnBaseUrl` / `uploadDir`；**不含 null 字段**（NON_NULL）；`expiration` 距当前时间约 900 秒内
- **层级**：L0

#### TC-2.003 scope=PRIVATE 信封不含 CDN 地址
- **关联**：Story 2.1 · AC1/AC3 · B2/B3
- **页面/入口**：`POST /api/v1/media/sts-credentials`
- **前置**：同 TC-2.002
- **步骤**：
  1. 请求体 `{"scope":"PRIVATE","contentType":"image/jpeg","count":1}`
- **预期**：响应 bucket 为私密桶名；响应中**不出现** `cdnBaseUrl`（私密桶不走 CDN，NON_NULL 省略）
- **层级**：L0

#### TC-2.004 STS Policy scope 最小化——只允许上传到该用户前缀
- **关联**：Story 2.1 · AC1 · B3（scope 收窄）
- **页面/入口**：`StsService` 单元测试
- **前置**：L0 单测
- **步骤**：
  1. 构造 userId=42，scope=PUBLIC
  2. 调用 `StsService.issueUploadCredential`，提取生成的 STS Policy JSON
  3. 检查 Policy Resource 字段
- **预期**：Policy 中 Action 仅含 `oss:PutObject`（无 `GetObject`/`ListBucket`）；Resource 前缀仅允许 `acs:oss:*:*:<公开桶>/public/content/42/*`，**不允许其他 userId 前缀或私密桶**
- **层级**：L0

#### TC-2.005 STS 端点限流生效（10 次后 429）
- **关联**：Story 2.1 · AC1 · B4（RedisRateLimiter）
- **页面/入口**：`POST /api/v1/media/sts-credentials`
- **前置**：L1 环境，Docker redis 启动
- **步骤**：
  1. 用同一 JWT 连续快速请求 STS 端点超过令牌桶阈值
- **预期**：超出速率限制后返回 HTTP 429；未超时正常请求仍返回 200
- **层级**：L1

### 2.1.2 客户端压缩与 EXIF 剥离

#### TC-2.006 含 GPS EXIF 图片经处理后 GPS 段被移除
- **关联**：Story 2.1 · AC2 · F3
- **页面/入口**：`shared/utils/image_processor.dart` 单元测试
- **前置**：测试图：带 GPS EXIF 的 JPEG（可用工具生成）
- **步骤**：
  1. 调用 `ImageProcessor.processForUpload(testImageBytes)`
  2. 对输出 bytes 用 EXIF 解析工具检查
- **预期**：输出图像中**不含 GPS EXIF 段**（纬度/经度/海拔均无）；像素内容与原图一致（尺寸无损失）；文件大小 ≤ 10MB
- **层级**：L0

#### TC-2.007 超过 10MB 的图片被压缩到 10MB 以下
- **关联**：Story 2.1 · AC1 · F3
- **页面/入口**：`shared/utils/image_processor.dart`
- **前置**：测试用大图（>10MB）
- **步骤**：
  1. 调用 `ImageProcessor.processForUpload(largImageBytes)`
- **预期**：返回的 bytes 大小 ≤ 10MB；图片仍可正常解码（非损坏）
- **层级**：L0

#### TC-2.008 服务端去 EXIF 兜底 URL 带 x-oss-process 参数（E4）
- **关联**：Story 2.1 · AC2 · B2（E4 服务端兜底）
- **页面/入口**：`AliyunOssClient.publicExifStrippedUrl` 单元测试
- **前置**：L0 单测
- **步骤**：
  1. 调用 `AliyunOssClient.publicExifStrippedUrl("public/content/42/photo.jpg")`
  2. 检查返回 URL
- **预期**：URL 包含 `x-oss-process=image/format,jpg`（或等效 OSS IMG 样式参数）；URL 使用 CDN base URL
- **层级**：L0

### 2.1.3 签名 URL 私密访问

#### TC-2.009 签名 URL 含正确参数和 TTL
- **关联**：Story 2.1 · AC3 · B5
- **页面/入口**：`SignedUrlService` 单元测试
- **前置**：L0 单测
- **步骤**：
  1. 调用 `SignedUrlService.sign("private/health/42/img.jpg")`
  2. 解析返回 URL
- **预期**：URL 含 `Expires` 参数（TTL=300s）；URL 含 `Signature` 参数；URL 指向私密桶 endpoint；**日志层不打印此 URL（INFO 级）**
- **层级**：L0

#### TC-2.010 私密桶对象无签名 GET 返回 403，带签名 GET 返回 200
- **关联**：Story 2.1 · AC3 · J2
- **页面/入口**：真实私密桶端点
- **前置**：L2 环境，真实阿里 OSS 私密桶 + 凭证（env 注入，绝不入库）；私密桶中存有测试对象
- **步骤**：
  1. 直接 GET 私密对象原始 URL（无签名）
  2. 调用 `SignedUrlService.sign` 获取签名 URL，再 GET
  3. 等待 TTL（300s）过期后再 GET 同签名 URL
- **预期**：步骤 1 → HTTP 403；步骤 2 → HTTP 200，返回对象内容；步骤 3 → HTTP 403（签名过期）
- **层级**：L2

#### TC-2.011 STS 越权写操作被 OSS 拒绝
- **关联**：Story 2.1 · AC1 · J1（越权验证）
- **页面/入口**：阿里 OSS 直接 API
- **前置**：L2 环境，PUBLIC scope STS 凭证已获取
- **步骤**：
  1. 使用 PUBLIC scope STS 凭证，尝试 PutObject 到 `private/health/42/` 前缀下
  2. 使用 PUBLIC scope STS 凭证，尝试 PutObject 到 `public/content/999/`（他人 userId）
- **预期**：两次操作均返回 OSS 403 AccessDenied；当前用户自己的正常路径正常写入
- **层级**：L2

### 2.1.4 相机/相册权限申请

#### TC-2.012 首次上传触发权限申请弹窗
- **关联**：Story 2.1 · AC4 · F4
- **页面/入口**：任意含「上传图片」操作的页面（如档案创建/内容发布）
- **前置**：L2 真机，相册权限从未被请求
- **步骤**：
  1. 点击上传图片按钮（首次）
- **预期**：系统弹出权限授权弹窗（iOS：「TailTopia 想访问您的照片」；Android：相册权限请求）
- **层级**：L2

#### TC-2.013 相册权限被永久拒绝后出现引导对话框
- **关联**：Story 2.1 · AC4 · F4
- **页面/入口**：任意上传入口
- **前置**：L2 真机，相册权限已被永久拒绝（permanentlyDenied）
- **步骤**：
  1. 点击上传图片按钮
  2. 观察弹出内容
  3. 点击「去设置」
- **预期**：出现应用内引导对话框，**文案为「需要相册权限才能上传，请前往设置开启」**（i18n id key 对应文本）；点击「去设置」跳转系统设置页（`openAppSettings()`）；**不出现中文文案**（App 双语 en/id）
- **层级**：L2

#### TC-2.014 权限引导文案双语校验
- **关联**：Story 2.1 · AC4 · F4（i18n）
- **页面/入口**：权限引导对话框 widget 测试
- **前置**：L0，设备 locale 分别设为 en、id
- **步骤**：
  1. locale=en：触发权限拒绝场景，读取按钮/标题文案
  2. locale=id：同上
- **预期**：locale=en 显示英文文案；locale=id 显示印尼语文案；**两者均不出现中文字符**
- **层级**：L0

---

## Story 2.2：宠物档案创建（单账号单宠物）

### 2.2.1 创建表单与字段校验

#### TC-2.015 必填字段未填时「立即创建」按钮置灰
- **关联**：Story 2.2 · AC3 · F1/R2-F1
- **页面/入口**：`/profile/create` · `pet_profile_create_page.dart`
- **前置**：状态 A 用户，无既有档案，locale=en 或 id
- **步骤**：
  1. 进入档案创建页面
  2. 不填写任何内容，检查「立即创建」/提交按钮状态
  3. 仅填写名字，不选宠物类型和生日，检查按钮状态
  4. 填写名字 + 选宠物类型，不选生日，检查按钮状态
  5. 三项全填，检查按钮状态
- **预期**：步骤 2/3/4：提交按钮置灰（`_canSubmit=false`）；步骤 5：按钮启用（`_canSubmit=true`）；**宠物类型选择器显示「猫/狗/其他」三个 ChoiceChip，必选一个**
- **层级**：L0

#### TC-2.016 宠物类型选择器三选一、必选
- **关联**：Story 2.2 · AC1（F6）· F1
- **页面/入口**：`/profile/create` · `pet_profile_create_page.dart`
- **前置**：状态 A 用户，locale=id
- **步骤**：
  1. 进入创建页面
  2. 观察宠物类型区域（三枚 ChoiceChip）
  3. 点击「Kucing」（猫）
  4. 再点击「Anjing」（狗）
  5. 提交时验证传入 API 的值
- **预期**：三枚 ChoiceChip 只能选中一个；选中 Anjing 后 Kucing 取消选中；提交时 petType 字段值为 `DOG`
- **层级**：L0

#### TC-2.017 生日必须填写完整年月日，不允许只选月日
- **关联**：Story 2.2 · AC3（R2）
- **页面/入口**：`/profile/create` · `pet_profile_create_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 点击生日字段，打开日期选择器
  2. 检查选择器模式（年/月/日 三级，不提供只月日模式）
  3. 选择完整日期（例：2023-05-15）后检查表单状态
- **预期**：日期选择器**不提供「只月日」模式**；选择完整日期后，`_canSubmit` 条件满足（三必填均有值）
- **层级**：L0

#### TC-2.018 名字超 20 字符被前端拦截
- **关联**：Story 2.2 · AC3 · F1
- **页面/入口**：`/profile/create`
- **前置**：L0 widget 测试
- **步骤**：
  1. 在名字输入框输入 21 个字符
- **预期**：输入框最多接受 20 个字符（硬限制或截断提示）；字符计数器显示当前/最大
- **层级**：L0

#### TC-2.019 一句话介绍超 30 字符被前端拦截
- **关联**：Story 2.2 · AC3 · F1
- **页面/入口**：`/profile/create`
- **前置**：L0 widget 测试
- **步骤**：
  1. 在介绍输入框输入 31 个字符
- **预期**：最多 30 字符；实时计数器显示
- **层级**：L0

#### TC-2.020 头像未填时展示默认占位 widget
- **关联**：Story 2.2 · AC3（R2）· R2-F1
- **页面/入口**：`/profile/create`
- **前置**：L0 widget 测试
- **步骤**：
  1. 进入创建页面，不上传头像
  2. 检查头像区域 widget
- **预期**：头像区域渲染默认占位图（`add_a_photo` 图标 widget）；不阻塞提交（头像为选填）；提交时 `avatarUrl` 为 null
- **层级**：L0

### 2.2.2 创建成功流程

#### TC-2.021 首次创建档案成功写库，含 pet_type 和 card_token
- **关联**：Story 2.2 · AC1 · J1
- **页面/入口**：`POST /api/v1/pet-profiles`（后端接口）
- **前置**：L1 环境，Docker postgres+redis 启动，V3 + V25 迁移已应用；状态 A 用户 JWT
- **步骤**：
  1. 提交档案创建请求：`name="小橘"，petType="CAT"，birthday="2022-03-10"，breed="橘猫"，intro="爱吃饭"`
  2. 查询 DB `pet_profiles` 表对应记录
- **预期**：HTTP 201；DB 中存在该记录，`name="小橘"，pet_type="CAT"，birthday=2022-03-10`；`card_token` 非空且**不是顺序自增数字**（base62 随机）；`owner_id` 匹配当前 JWT 用户 ID
- **层级**：L1

#### TC-2.022 card_token 不可枚举——不暴露顺序 ID
- **关联**：Story 2.2 · AC1 · B3（不可枚举 token）
- **页面/入口**：`CardTokenGenerator` 单元测试
- **前置**：L0 单测
- **步骤**：
  1. 生成 100 个不同的 card_token
  2. 检查是否为纯数字自增序列
- **预期**：100 个 token 均不相同；token 不是自增整数序列；长度 ≥16 字符；字符集为 base62（`[a-zA-Z0-9]`）
- **层级**：L0

#### TC-2.023 单账号单宠物约束——重复创建被 409 拦截
- **关联**：Story 2.2 · AC1 · J2
- **页面/入口**：`POST /api/v1/pet-profiles`
- **前置**：L1 环境，当前用户已有一条档案记录
- **步骤**：
  1. 同用户再次发送档案创建请求
- **预期**：HTTP 409，ProblemDetail type 含 `profile-exists`；**DB 中不新增记录**
- **层级**：L1

#### TC-2.024 非法 pet_type 值返回 422 字段级错误
- **关联**：Story 2.2 · AC1（F6）· B4
- **页面/入口**：`POST /api/v1/pet-profiles`（MockMvc L0）
- **前置**：L0 MockMvc
- **步骤**：
  1. 请求体 `{"petType":"RABBIT","name":"test","birthday":"2022-01-01"}`
- **预期**：HTTP 422，ProblemDetail 含字段级错误信息，指向 `petType` 字段；**不暴露堆栈**
- **层级**：L0

#### TC-2.025 缺少生日返回 422
- **关联**：Story 2.2 · AC3（R2）· R2-B1
- **页面/入口**：`POST /api/v1/pet-profiles`（MockMvc L0）
- **前置**：L0 MockMvc
- **步骤**：
  1. 请求体缺失 `birthday` 字段（`{"petType":"CAT","name":"test"}`）
- **预期**：HTTP 422，错误指向 `birthday` 字段
- **层级**：L0

### 2.2.3 已有档案直达

#### TC-2.026 已有档案用户再次进入创建入口，直达档案 Tab
- **关联**：Story 2.2 · AC2
- **页面/入口**：`/profile/create` · `pet_profile_create_page.dart`
- **前置**：L1 环境，当前用户已有档案
- **步骤**：
  1. 导航到 `/profile/create`（或点击成长档案 Tab 入口）
- **预期**：**不显示创建表单**；直接路由到档案 Tab（`/profile`）；`petProfileProvider` 非空时守卫已激活
- **层级**：L1

---

## Story 2.3：统一「+」发布入口与内容发布

### 2.3.1 Publish Compose 页面基础交互

#### TC-2.027 点击「+」打开 Publish Compose 全屏 bottom sheet
- **关联**：Story 2.3 · AC1 · F1
- **页面/入口**：Tab Bar 凸起「+」→ `publish_compose_page.dart`
- **前置**：L0 widget 测试，状态 A 登录用户
- **步骤**：
  1. 点击底部 Tab Bar 中央「+」按钮
- **预期**：打开全屏 bottom sheet；页面内含四 Segment（「全部/日常分享/成长日历/科普」或 id 对应译文）；图片上传区、文字输入区、发布按钮在同一页面；**不跳转独立新页面**
- **层级**：L0

#### TC-2.028 文字实时计数，超 1000 字符禁用发布按钮
- **关联**：Story 2.3 · AC1 · F3
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 在文字区输入 999 个字符，观察计数器和发布按钮
  2. 再输入 1 个字符（到 1000 个），检查
  3. 再输入 1 个字符，检查发布按钮状态
- **预期**：步骤 1：计数显示「剩余 1 字符」，发布按钮启用；步骤 2：剩余 0，按钮仍启用；步骤 3：文字超出禁止输入或按钮置灰（不可提交）
- **层级**：L0

#### TC-2.029 文字与图片皆空时发布按钮置灰
- **关联**：Story 2.3 · AC6（R2）· R2-F2
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 打开 Publish Compose，不输入任何内容
  2. 检查发布按钮状态
  3. 输入一个字符，再检查
- **预期**：步骤 2：发布按钮置灰，不可点击；步骤 3：按钮启用
- **层级**：L0

#### TC-2.030 图片上传区限制 9 张，单张 ≤10MB
- **关联**：Story 2.3 · AC1 · F3
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 mock 上传测试
- **步骤**：
  1. 依次选择 9 张图片，验证全部可选
  2. 尝试选第 10 张
  3. 尝试选一张超过 10MB 的图片
- **预期**：步骤 1：9 张均显示在上传区；步骤 2：选择被阻止（toast 提示或按钮禁用）；步骤 3：图片经压缩处理后 ≤10MB，或提示无法压缩时弹出错误 toast
- **层级**：L0

#### TC-2.031 发布确认提示「发布后内容将对所有用户公开展示」
- **关联**：Story 2.3 · AC4 · F5
- **页面/入口**：`publish_compose_page.dart` 发布流程
- **前置**：L0 widget 测试
- **步骤**：
  1. 填写内容后点击发布按钮
  2. 观察确认提示
- **预期**：出现公开提示文案（id 译：「Konten ini akan dilihat semua pengguna setelah diterbitkan」或类似）；**不出现中文文案**
- **层级**：L0

### 2.3.2 内容类型与 B/C 状态处理

#### TC-2.032 状态 B/C 用户「成长日历」Segment 置灰
- **关联**：Story 2.3 · AC2 · F2
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 widget 测试，用户状态为 B（PLANNING）
- **步骤**：
  1. 状态 B 用户打开 Publish Compose
  2. 检查「成长日历」Segment 状态
  3. 点击该灰置 Segment
- **预期**：「成长日历」Segment 视觉灰置（disabled 样式）；点击后显示悬浮提示「需要先创建宠物档案」（id：「Perlu membuat profil hewan dulu」）；同时有跳转建档入口；**不直接切换到成长日历 tab**
- **层级**：L0

#### TC-2.033 B/C 用户灰选「成长日历」→ 建档完成跳过庆祝页返回发布页（F15）
- **关联**：Story 2.3 · AC7（R2/F15）
- **页面/入口**：`publish_compose_page.dart` → `/profile/create` → 返回
- **前置**：L0 widget 测试串接
- **步骤**：
  1. 状态 B 用户点击灰置的「成长日历」→ 触发 FR-0G 切状态为 A → 进 FR-11 建档（`/profile/create?origin=graySelectPublish`）
  2. 完成建档
  3. 观察路由跳转
- **预期**：建档完成后**不进庆祝页**（`showsBuildCelebration=false`）；直接导航回 `/publish?preset=growth-calendar`；发布页**预选「成长日历」类型**
- **层级**：L0

### 2.3.3 成长日历发布与事件日期

#### TC-2.034 成长日历内容从「+」入口进入默认事件日期为今天
- **关联**：Story 2.3 · AC5（F9）· R2-F1
- **页面/入口**：`publish_compose_page.dart`（从「+」入口）
- **前置**：L0 widget 测试，当前日期 2026-06-10
- **步骤**：
  1. 状态 A 有档案用户，从「+」入口选择「成长日历」类型
  2. 检查「事件日期」字段的默认值
- **预期**：事件日期默认值 = 今天（2026-06-10）；日期选择器的 `lastDate=今天`，不可选未来日期（未来日期置灰）
- **层级**：L0

#### TC-2.035 成长日历从日历格子入口进入默认事件日期为该格子日期
- **关联**：Story 2.3 · AC5（F9）+ Story 2.4 · AC6 联动
- **页面/入口**：`growth_archive_page.dart` 日历视图 → `/publish?preset=growth-calendar&date=2026-05-20`
- **前置**：L0 widget 测试
- **步骤**：
  1. 在成长档案日历视图，点击 2026-05-20 空格子的「+」
  2. 检查 Publish Compose 中事件日期
- **预期**：事件日期默认值 = 2026-05-20（格子日期，非今天）；可选任意过去日期，**不可选未来**
- **层级**：L0

#### TC-2.036 服务端拒绝未来事件日期
- **关联**：Story 2.3 · AC5（F9）· R2-B1
- **页面/入口**：`POST /api/v1/content-posts`（MockMvc L0）
- **前置**：L0 MockMvc
- **步骤**：
  1. 提交成长日历内容，`eventDate=2099-12-31`（未来日期）
- **预期**：HTTP 422，ProblemDetail 指向 `eventDate` 字段或通用错误信息；**不落库**
- **层级**：L0

#### TC-2.037 日常/科普内容的 event_date 在 DB 中为 null
- **关联**：Story 2.3 · AC5（F9）
- **页面/入口**：`POST /api/v1/content-posts` → DB 查验
- **前置**：L1 环境
- **步骤**：
  1. 发布日常分享内容（type=DAILY），不传 eventDate
  2. 查询 DB `content_posts` 表
- **预期**：DB 中该记录 `event_date` 列为 null；`type=DAILY`；`status=PUBLISHED`
- **层级**：L1

### 2.3.4 发布审核与幂等

#### TC-2.038 文字含违规词被审核拦截，不落库（F10）
- **关联**：Story 2.3 · AC8（R2/F10）
- **页面/入口**：`publish_compose_page.dart`，提交审核流程
- **前置**：L0（stub 关键词列表包含「judi」）
- **步骤**：
  1. 在文字框输入含「judi」的内容
  2. 点击发布
- **预期**：停留在 Publish Compose 页面；显示提示「内容包含不当词汇，请修改后重试」（id：`publishTextBlocked` key 对应文案）；**不进入人工审核队列**；修改文字后可再次发布
- **层级**：L0

#### TC-2.039 文字拦截后图片内容保留在编辑页（内存草稿）
- **关联**：Story 2.3 · AC8 + AC3
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 添加图片，输入含违规词文字，点击发布
  2. 被拦截后检查页面状态
- **预期**：已上传图片仍在上传区（未清空）；文字保留在输入框（可直接修改）；拦截提示可关闭后继续编辑
- **层级**：L0

#### TC-2.040 同 Idempotency-Key 重复提交只落一条记录
- **关联**：Story 2.3 · AC4 · J4
- **页面/入口**：`POST /api/v1/content-posts`（带 Idempotency-Key 头）
- **前置**：L1 环境，Docker redis 启动
- **步骤**：
  1. 用同一 `Idempotency-Key` 头提交发布请求两次
  2. 查询 DB `content_posts` 表
- **预期**：DB 中只有一条对应记录；第二次请求返回相同响应（幂等）
- **层级**：L1

### 2.3.5 网络重试与草稿

#### TC-2.041 上传部分图片失败后「重试」仅重传失败件
- **关联**：Story 2.3 · AC3 · J3
- **页面/入口**：`publish_compose_page.dart`（mock 上传器）
- **前置**：L0 mock 测试（部分图片上传 mock 失败）
- **步骤**：
  1. 添加 3 张图片，mock 第 2 张上传失败
  2. 发布流程执行，第 2 张显示失败状态
  3. 点击「重试」
- **预期**：重试仅重新上传第 2 张（已成功的第 1、3 张不重传）；文字内容保留；`PublishController` 只对失败件触发 `retry`
- **层级**：L0

#### TC-2.042 关闭 Publish Compose sheet 后草稿内容清空
- **关联**：Story 2.3 · AC3
- **页面/入口**：`publish_compose_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 输入文字和图片
  2. 关闭 bottom sheet（下滑关闭或点返回）
  3. 重新打开「+」入口
- **预期**：再次打开的 Publish Compose **为空白状态**（无任何残留草稿）；`PublishController` autoDispose 已执行
- **层级**：L0

---

## Story 2.4：成长档案 Tab 时间线与状态快捷编辑

### 2.4.1 三态分支渲染

#### TC-2.043 状态 A 有档案：显示信息卡 + FAB + 时间线
- **关联**：Story 2.4 · AC1
- **页面/入口**：`/profile` · `growth_archive_page.dart`
- **前置**：L1 环境，状态 A 用户，已有档案，存在至少 1 条成长日历内容
- **步骤**：
  1. 进入成长档案 Tab
- **预期**：从上至下显示：宠物信息卡（头像/名字/品种/年龄/介绍）→ 统计栏 → 里程碑入口 → 分享 FAB → 垂直时间线；**不出现「立即创建」空态按钮**
- **层级**：L1

#### TC-2.044 状态 A 无档案：显示空状态「立即创建」入口
- **关联**：Story 2.4 · AC2
- **页面/入口**：`/profile` · `growth_archive_page.dart`
- **前置**：L0 widget 测试，状态 A，`GET /pet-profiles/me` 返回 404
- **步骤**：
  1. 状态 A 用户（无档案）进入成长档案 Tab
- **预期**：显示空状态（UX-DR8）；文案含「立即创建」（id：「Buat Sekarang」或类似）；点击跳转 `/profile/create`；**不显示时间线或信息卡**
- **层级**：L0

#### TC-2.045 状态 B/C：显示专属提示和修改状态入口
- **关联**：Story 2.4 · AC3
- **页面/入口**：`/profile`
- **前置**：L0 widget 测试，用户状态为 C（ENTHUSIAST）
- **步骤**：
  1. 状态 C 用户进入成长档案 Tab
- **预期**：显示「成长档案为有宠用户专属，更换宠物状态即可开启」（id 译文）；有「修改状态」入口；**不显示创建入口/时间线/信息卡**
- **层级**：L0

### 2.4.2 信息卡与统计栏

#### TC-2.046 宠物年龄由生日正确计算
- **关联**：Story 2.4 · AC1 · B2（年龄前端计算）
- **页面/入口**：`pet_info_card.dart` · `computePetAge` 纯函数测试
- **前置**：L0 单测
- **步骤**：
  1. birthday=2023-06-10，today=2026-06-10 → 期望 3 岁
  2. birthday=2024-12-01，today=2026-06-10 → 期望 1 岁
  3. birthday=2026-06-10，today=2026-06-10 → 期望 0 岁（刚出生）
- **预期**：三种情况分别返回正确年龄；`computePetAge` 纯函数 L0 可测
- **层级**：L0

#### TC-2.047 统计栏显示快乐时刻数和问诊数
- **关联**：Story 2.4 · AC5（F2）· A5-1
- **页面/入口**：`growth_archive_page.dart` + `GET /api/v1/pet-profiles/me/archive-stats`
- **前置**：L1 环境，DB 中存在 5 条 GROWTH_MOMENT + 2 条 health_events（ARCHIVED）
- **步骤**：
  1. 进入成长档案 Tab
  2. 观察统计栏
- **预期**：统计栏显示「5 Momen Bahagia · 2 Konsultasi」（数字准确）
- **层级**：L1

#### TC-2.048 里程碑入口零态降级——N 按 pet_type 动态取值
- **关联**：Story 2.4 · AC5（F2）· A5-1
- **页面/入口**：`growth_archive_page.dart`
- **前置**：L0 widget 测试；档案 `pet_type=CAT`（完成数 0，总数 30）
- **步骤**：
  1. 打开档案 Tab，查看里程碑区块
- **预期**：显示「0 / 30 个里程碑」或进度条 0/30；`pet_type=OTHER` 时显示 0/15；**不抛出异常，不阻塞页面渲染**
- **层级**：L0

### 2.4.3 时间线与日历双视图

#### TC-2.049 时间线默认视图按 event_date 倒序排列
- **关联**：Story 2.4 · AC6（F9）· R2-B1
- **页面/入口**：`growth_archive_page.dart` 时间线视图
- **前置**：L1 环境，存在 event_date=2026-05-01 和 event_date=2026-04-01 的两条成长日历内容
- **步骤**：
  1. 进入档案 Tab，默认时间线视图
  2. 检查条目顺序
- **预期**：`event_date=2026-05-01` 的记录在上方（更新），`2026-04-01` 在下方；**按 `event_date` 倒序，非 `created_at`**
- **层级**：L1

#### TC-2.050 日历视图切换，session 内保持
- **关联**：Story 2.4 · AC5（F2）· A5-2
- **页面/入口**：`growth_archive_page.dart` 右上角视图切换图标
- **前置**：L0 widget 测试
- **步骤**：
  1. 点击右上角图标切换为「日历视图」
  2. 切换到其他 Tab，再回来
  3. 关闭应用重新进入（或模拟 Widget 重建）
- **预期**：步骤 2：仍在日历视图（session 内保持）；步骤 3：恢复默认时间线视图（session 外不持久化）
- **层级**：L0

#### TC-2.051 日历视图格子四态样式正确
- **关联**：Story 2.4 · AC5/AC6 · A5-3
- **页面/入口**：`archive_calendar.dart`
- **前置**：L0 widget 测试，mock 数据：2026-06-05 有快乐时刻+健康事件，2026-06-06 仅健康事件，2026-06-07 无记录，2026-06-11 未来
- **步骤**：
  1. 在日历视图观察各格子样式
- **预期**：06-05：显示首图缩略图 + 右下角 🏥 角标；06-06：显示 🏥 图标（无缩略图）；06-07：显示日期数字 + 淡色「+」；06-11（未来）：灰色不可点
- **层级**：L0

#### TC-2.052 未来日期格子不可点击
- **关联**：Story 2.4 · AC6（F9）· R2-F1
- **页面/入口**：`archive_calendar.dart`
- **前置**：L0 widget 测试，today=2026-06-10
- **步骤**：
  1. 点击 2026-06-15 日历格子（未来日期）
- **预期**：点击无响应（`GestureDetector` 不触发）；格子样式为灰色
- **层级**：L0

#### TC-2.053 点击有记录格子进入当天详情页
- **关联**：Story 2.4 · AC6（F9）· R2-F1
- **页面/入口**：`archive_calendar.dart` → `day_detail_page.dart`
- **前置**：L1 环境，2026-06-05 有 2 条快乐时刻（created_at 顺序：A早，B晚）
- **步骤**：
  1. 点击 2026-06-05 格子
  2. 观察当天详情页内容和条目顺序
- **预期**：进入当天详情页，顶部标题显示「2026年6月5日」（或「5 Juni 2026」）；当天快乐时刻**按 created_at 正序排列**（A在前B在后）；**页面内无「+」发布按钮、无删除入口**
- **层级**：L1

#### TC-2.054 点击无记录格子「+」→ 跳转发布页预填日期
- **关联**：Story 2.4 · AC6（F9）
- **页面/入口**：`archive_calendar.dart` → `/publish?preset=growth-calendar&date=`
- **前置**：L0 widget 测试，2026-06-08 无记录
- **步骤**：
  1. 点击 2026-06-08 空格子的「+」
- **预期**：跳转到 Publish Compose，预选「成长日历」类型，事件日期默认值 = 2026-06-08（格子日期）
- **层级**：L0

#### TC-2.055 时间线第一条快乐时刻显示 🌟 永久标签
- **关联**：Story 2.4 · AC5（F2）· A5-4
- **页面/入口**：`timeline_tiles.dart`
- **前置**：L0 widget 测试，mock 3 条快乐时刻（按 event_date 倒序）
- **步骤**：
  1. 渲染时间线
  2. 检查第一条（最新 event_date）和其余条目的标签
- **预期**：时间线顶部（最新）快乐时刻显示 🌟「第一条快乐时刻」（id：「Momen Bahagia Pertama」）永久标签；第 2、3 条**不显示**该标签
- **层级**：L0

### 2.4.4 加载失败态（F13）

#### TC-2.056 时间线内容区加载失败——信息卡和统计栏仍显示
- **关联**：Story 2.4 · AC7（F13）
- **页面/入口**：`growth_archive_page.dart`
- **前置**：L0 widget 测试，mock 时间线端点返回网络错误，信息卡已有缓存数据
- **步骤**：
  1. 时间线/日历内容区发生网络错误
  2. 观察页面整体状态
- **预期**：内容区显示「加载失败，下拉重试」（id：`archiveLoadFailed` key）+ 重试按钮；**宠物信息卡保持可见**；**统计栏保持可见**（独立 provider 未被失败态覆盖）
- **层级**：L0

#### TC-2.057 用户从 A 切换为 B 后档案 Tab 显示专属提示
- **关联**：Story 2.4 · AC7（F13）+ AC3
- **页面/入口**：`growth_archive_page.dart`
- **前置**：L0 widget 测试，用户状态从 A 切换为 B
- **步骤**：
  1. 模拟全局状态 provider 从 A 变为 B
  2. 观察档案 Tab
- **预期**：切换后显示「成长档案为有宠用户专属」提示，与 AC3 表现一致
- **层级**：L0

### 2.4.5 状态快捷编辑

#### TC-2.058 档案 Tab 修改宠物状态后「我的」Tab 即时同步
- **关联**：Story 2.4 · AC4（FR-21）
- **页面/入口**：`growth_archive_page.dart` 状态编辑入口 → FR-0F 组件
- **前置**：L1 环境，状态 A 用户
- **步骤**：
  1. 在档案 Tab 点击状态快捷编辑入口
  2. 修改宠物状态
  3. 切换到「我的」Tab
- **预期**：「我的」Tab 即时反映新状态（读同一全局 provider）；首页 Feed 按新状态刷新
- **层级**：L1

---

## Story 2.5：问诊记录存档承接与健康事件时间线

### 2.5.1 存档弹窗三态

#### TC-2.059 状态 A 已建档——弹「存入/跳过」弹窗
- **关联**：Story 2.5 · AC1（三态①）
- **页面/入口**：`archive_prompt_dialog.dart`（由 Epic4/5 触发）
- **前置**：L0 widget 测试，用户状态 A，`hasPetProfile=true`，宠物名「小橘」
- **步骤**：
  1. 触发 `showArchivePrompt(context, ref, args)` (state=A, hasPet=true, petName="小橘")
- **预期**：弹出对话框，文案「是否将本次咨询存入 小橘 的档案？」（插值宠物名）；按钮「存入」和「跳过」；**不出现中文**（双语 en/id）
- **层级**：L0

#### TC-2.060 状态 A 未建档——弹「立即创建/跳过」弹窗
- **关联**：Story 2.5 · AC1（三态②）
- **页面/入口**：`archive_prompt_dialog.dart`
- **前置**：L0 widget 测试，用户状态 A，`hasPetProfile=false`
- **步骤**：
  1. 触发三态②场景
- **预期**：文案「创建档案后可保存本次记录」（id key：`archivePromptCreate`）；按钮「立即创建」和「跳过」
- **层级**：L0

#### TC-2.061 状态 B/C——弹「去创建/跳过」弹窗
- **关联**：Story 2.5 · AC1（三态③）
- **页面/入口**：`archive_prompt_dialog.dart`
- **前置**：L0 widget 测试，用户状态 C
- **步骤**：
  1. 触发三态③场景
- **预期**：文案「切换为有宠物并创建档案后可保存」（id key：`archivePromptSwitch`）；按钮「去创建」和「跳过」；点击「去创建」先执行 FR-0G 切状态为 A，再跳建档
- **层级**：L0

### 2.5.2 存档幂等与延迟回灌

#### TC-2.062 同 source_ref 存档只触发一次——弹窗不重复
- **关联**：Story 2.5 · AC1 · J2
- **页面/入口**：`ArchivePromptGuard` + `POST /api/v1/health-events/archive-decisions`
- **前置**：L1 环境，`source_ref="triage_abc123"` 已有决策记录
- **步骤**：
  1. 再次为同一 source_ref 调用 `showArchivePromptIfNeeded`
- **预期**：**不显示弹窗**（后端 `hasDecision=true` + 本地标记已置位）；幂等生效
- **层级**：L1

#### TC-2.063 A 未建档选「立即创建」→ 建档成功 → 同 sourceRef 回灌健康事件
- **关联**：Story 2.5 · AC3（延迟回灌）
- **页面/入口**：`archive_prompt_dialog.dart` → `/profile/create` → 回灌
- **前置**：L0 widget 测试串接
- **步骤**：
  1. 触发三态②，点击「立即创建」，pending 存档意图挂起 `sourceRef="triage_xyz"`
  2. 跳 `/profile/create?origin=triageArchive`，完成建档
  3. 建档完成回调执行 `_backfillPendingArchive`
- **预期**：建档完成**跳过庆祝页**（`showsBuildCelebration=false`）；`recordDecision(ARCHIVED, sourceRef="triage_xyz")` 被调用；pending 清空；成长档案时间线出现新健康事件条目；同一 sourceRef 重复回调**不重复落库**（幂等键保证）
- **层级**：L0

#### TC-2.064 用户放弃建档，挂起存档意图不落库
- **关联**：Story 2.5 · AC3
- **页面/入口**：`archive_prompt_dialog.dart` → 放弃
- **前置**：L0 widget 测试
- **步骤**：
  1. 触发三态②，点击「立即创建」，pending 挂起
  2. 进入建档页后，返回取消（不完成建档）
- **预期**：`pendingArchiveProvider` 中的意图未被调用 `recordDecision`；`health_events` **不增加任何记录**
- **层级**：L0

### 2.5.3 健康事件写入与时间线

#### TC-2.065 存入后健康事件写库并在 2.4 时间线倒序显示
- **关联**：Story 2.5 · AC2 · J1
- **页面/入口**：存档服务 → `health_events` 表 → 时间线
- **前置**：L1 环境，已有档案用户，触发存档选择「存入」
- **步骤**：
  1. 模拟问诊结束，调用 `recordDecision(ARCHIVED, ...)`
  2. 查询 `health_events` 表
  3. 进入档案 Tab 时间线
- **预期**：DB 中存在对应记录，含 `event_date`/`symptom_summary`/`ai_level`；时间线出现「🏥 问诊记录」条目，位置按 event_date 倒序正确；**health_events 中 `image_keys` 存私密桶自有 key，非 IM URL**
- **层级**：L1

#### TC-2.066 健康事件图片只通过签名 URL 展示（私密桶隔离）
- **关联**：Story 2.5 · AC2 · J4（签名访问）
- **页面/入口**：`HealthEventTile` 含图渲染
- **前置**：L2 环境，存档含图
- **步骤**：
  1. 存入含聊天图的健康事件
  2. 在时间线 HealthEventTile 查看图片加载 URL
- **预期**：图片 URL 包含签名参数（`Expires`/`Signature`）；**不含 IM 原始 URL**（`im.tencent.com` 或类似域）；无签名直接 GET 该对象返回 403
- **层级**：L2

---

## Story 2.6：宠物名片 H5 对外分享页

### 2.6.1 正常名片渲染

#### TC-2.067 有效 cardToken 直出 6 区块完整 HTML
- **关联**：Story 2.6 · AC1 · J1
- **页面/入口**：`GET /p/{cardToken}`（浏览器访问）
- **前置**：L1 环境，有效 cardToken 对应档案：宠物名「Mochi」，昵称「Dai」，创建于 2025-12-01，3 条快乐时刻（含不同 event_date），0 个里程碑完成，0 次问诊
- **步骤**：
  1. 浏览器或 MockMvc 请求 `GET /p/{validToken}`
  2. 检查响应 HTML 结构
- **预期**：
  - HTTP 200
  - ① Hero 区块：头像 img + 「Mochi」名字 + 「Bersama Dai selama 192 hari」（陪伴天数）
  - ② 里程碑徽章条：`th:if="${hasMilestones}"` = false，**该区块不出现**（零态降级 AC5）
  - ③ 故事数字：`3 Momen Bahagia · 0 Konsultasi`；无「Tonggak」项（里程碑为 0）
  - ④ 里程碑动态：不出现（零态降级）
  - ⑤ 快乐时刻照片流：3 张图片，按 event_date 倒序
  - ⑥ 双 CTA：「Buat arsip untuk hewanku」(主) + 「Lihat cerita lengkap Mochi」(副)
  - OG 标题：`Mochi 的成长故事`（或「Kisah Pertumbuhan Mochi」）
  - `<meta name="robots" content="noindex,nofollow">`
- **层级**：L1

#### TC-2.068 快乐时刻照片流按 event_date 倒序（F9）
- **关联**：Story 2.6 · AC7（R2/F9）
- **页面/入口**：`GET /p/{cardToken}`
- **前置**：L1 环境，档案存在 event_date=2026-05-01 和 event_date=2026-04-01 的快乐时刻
- **步骤**：
  1. 请求名片 HTML，提取 `moments` 区块中图片顺序
- **预期**：`event_date=2026-05-01` 的内容在前，`2026-04-01` 在后；按 `event_date` 倒序（与 AC1 区块⑤口径一致，**非 created_at**）
- **层级**：L1

#### TC-2.069 所有对外图片经 OSS EXIF 剥离兜底（E4 隐私）
- **关联**：Story 2.6 · AC1 · B2（决策 E4）
- **页面/入口**：`card.html` 渲染
- **前置**：L0 MockMvc
- **步骤**：
  1. MockMvc 请求名片 HTML
  2. 提取头像 img src 和快乐时刻 img src
- **预期**：所有图片 URL 包含 `x-oss-process=image/format,jpg`（`AliyunOssClient.exifStrippedDeliveryUrl` 兜底）；**不出现无 OSS 处理参数的原始 CDN URL**
- **层级**：L0

#### TC-2.070 名片不含日常/科普内容和健康事件详情（隐私边界）
- **关联**：Story 2.6 · AC1（隐私边界）
- **页面/入口**：`GET /p/{cardToken}`
- **前置**：L1 环境，档案关联用户有 DAILY/KNOWLEDGE 类型 content_post，也有 health_events
- **步骤**：
  1. 请求名片 HTML
  2. 检查页面内容是否包含日常/科普文字或健康事件症状描述
- **预期**：日常/科普内容的文字/图片**不出现**在名片中；健康事件的症状描述/AI 评级**不出现**在名片中（问诊计数仅数字）
- **层级**：L1

#### TC-2.071 陪伴天数计算——纯函数正确性
- **关联**：Story 2.6 · AC1（companionDays 纯函数）
- **页面/入口**：`CardPageController.companionDays` 或对应工具类
- **前置**：L0 单测
- **步骤**：
  1. createdAt=2025-06-10，today=2026-06-10 → 期望 365
  2. createdAt=2026-06-10，today=2026-06-10 → 期望 0（当天创建）
  3. createdAt=null → 期望非负数或 0（null 安全）
- **预期**：三种情况返回正确值；**不抛出异常**
- **层级**：L0

### 2.6.2 零态降级

#### TC-2.072 快乐时刻为 0 时照片流区块显示占位文案
- **关联**：Story 2.6 · AC5（F2 零态）
- **页面/入口**：`GET /p/{cardToken}`（MockMvc L0）
- **前置**：L0 MockMvc，快乐时刻数 = 0
- **步骤**：
  1. Mock `ContentService.findRecentGrowthMomentsByEventDate` 返回空列表
  2. 请求名片 HTML
- **预期**：照片流区块（`empty-moments`）显示「Unduh App untuk lihat lebih banyak konten」（匹配 `card.html` 中的占位文案）；**页面正常渲染，不报错**
- **层级**：L0

#### TC-2.073 里程碑为 0 时徽章条、动态区块、故事数字 Tonggak 均不出现
- **关联**：Story 2.6 · AC5（F2 零态）
- **页面/入口**：`GET /p/{cardToken}`（MockMvc L0）
- **前置**：L0 MockMvc，`milestoneCompleted=0`
- **步骤**：
  1. Mock getStats 返回 `milestoneCompleted=0`
  2. 请求名片 HTML
- **预期**：HTML 中不含徽章条（`section.badges` 的 `th:if="${hasMilestones}"` 为 false）；不含「milestone-news」区块；故事数字中无「Tonggak」字样；其余区块（Hero/统计/照片/CTA）正常渲染
- **层级**：L0

#### TC-2.074 里程碑 service 接口返回异常时名片仍能正常直出
- **关联**：Story 2.6 · AC5（零态不硬依赖）
- **页面/入口**：`GET /p/{cardToken}`
- **前置**：L0 MockMvc，Mock 里程碑 service 抛出 RuntimeException
- **步骤**：
  1. 配置里程碑 service 接口异常
  2. 请求名片 HTML
- **预期**：HTTP 200，名片正常渲染；里程碑相关区块降级为零态；**不出现 500 错误页**；**不暴露异常堆栈**
- **层级**：L0

### 2.6.3 多态失效页（防枚举 + noindex）

#### TC-2.075 Token 不存在 → 统一 404 失效页
- **关联**：Story 2.6 · AC4 · B4
- **页面/入口**：`GET /p/nonexistentToken123`
- **前置**：L0 MockMvc 或 L1 真实 DB
- **步骤**：
  1. 访问不存在的 cardToken 对应名片 URL
- **预期**：HTTP 404；响应为 `card_gone.html` 友好失效页；含「This card is no longer available」（或 `card_gone.html` 实际文案）；含 `<meta name="robots" content="noindex,nofollow">`；含 App 下载链接；**不包含任何宠物数据**
- **层级**：L0

#### TC-2.076 账号已注销 → 同一 404 失效页（防枚举）
- **关联**：Story 2.6 · AC4/AC6（F14）
- **页面/入口**：`GET /p/{validToken}`（但 owner 已注销）
- **前置**：L1 环境，token 有效但对应 owner 账号已注销（`AccountDeletionJob` 已执行）
- **步骤**：
  1. 访问该 cardToken 对应名片 URL
- **预期**：HTTP 404；**与 TC-2.075 返回完全相同的** `card_gone.html`；状态码/文案/内容完全一致（不区分「不存在」vs「已注销」，防枚举）；不含 owner 的任何 PII
- **层级**：L1

#### TC-2.077 三种失效情况（不存在/注销/链接失效）文案和状态码无差异（防枚举核心）
- **关联**：Story 2.6 · AC4 · B4（防枚举）
- **页面/入口**：`CardPageController` MockMvc
- **前置**：L0 MockMvc，分别 mock 三种情况
- **步骤**：
  1. Case A：token 不存在
  2. Case B：owner 已注销
  3. 对比两次响应
- **预期**：两次响应 HTTP 状态码均为 404；响应体完全相同（`card_gone.html` 同一模板）；**无任何差异**（不泄漏 token 是否曾存在）
- **层级**：L0

#### TC-2.078 失效页含 App 下载引导
- **关联**：Story 2.6 · AC4
- **页面/入口**：`card_gone.html`
- **前置**：L0
- **步骤**：
  1. 请求任意无效 cardToken
  2. 检查响应 HTML
- **预期**：含「Discover pets on TailTopia」链接按钮（匹配 `card_gone.html` 实际文案）；链接 `th:href="${downloadUrl}"` 非空
- **层级**：L0

### 2.6.4 双 CTA 平台分流与 OG meta

#### TC-2.079 OG meta 字段完整（WhatsApp/Line 预览支持）
- **关联**：Story 2.6 · AC1/AC2
- **页面/入口**：`GET /p/{cardToken}`（MockMvc L0）
- **前置**：L0 MockMvc
- **步骤**：
  1. 请求名片 HTML，检查 `<head>` 区域
- **预期**：含 `og:title`（=「[宠物名] 的成长故事」）/ `og:description` / `og:image`（CDN/OSS 预渲染 URL）/ `og:type=profile` / `og:url`；含 Twitter Card `summary_large_image`；含 `<meta name="robots" content="noindex,nofollow">`
- **层级**：L0

#### TC-2.080 已装 App 点击 CTA 尝试深链 Deep Link（L2 真机）
- **关联**：Story 2.6 · AC6（F13）· R2-F1
- **页面/入口**：`card.html` 双 CTA，在 iOS/Android 已装 App 设备上
- **前置**：L2 真机，App 已安装
- **步骤**：
  1. 打开名片 H5 页面
  2. 点击「查看 [宠物名] 完整故事」CTA
- **预期**：800ms 内触发 `petgo://card/{token}` Deep Link，App 打开该宠物成长档案（只读）；若未登录触发 FR-0C 登录流
- **层级**：L2

#### TC-2.081 未装 App 点击 CTA 跳应用商店（iOS/Android 分流）
- **关联**：Story 2.6 · AC6（F13）
- **页面/入口**：`card.html` 双 CTA，在未装 App 设备
- **前置**：L2 真机，App 未安装；分别在 iOS Safari 和 Android Chrome 测试
- **步骤**：
  1. iOS 设备打开名片 H5，点 CTA
  2. Android 设备打开名片 H5，点 CTA
- **预期**：iOS → 跳 App Store 页；Android → 跳 Google Play 页；**不同平台跳转地址不同**（UA 分流正确）
- **层级**：L2

#### TC-2.082 H5 加载性能（≤3s，CDN 命中）
- **关联**：Story 2.6 · AC2 · J2
- **页面/入口**：`GET /p/{cardToken}`
- **前置**：L2 真实部署环境 + CDN
- **步骤**：
  1. 浏览器开发者工具 Network 面板，访问名片 URL
  2. 测量首屏加载时间
- **预期**：首屏（DOMContentLoaded）≤3s；`og:image` 经 CDN 命中（Response Headers 含 `X-Cache: HIT` 或类似）
- **层级**：L2

---

## Story 2.7：档案页动效分享 FAB

### 2.7.1 FAB 显隐逻辑

#### TC-2.083 状态 A 有档案且有 cardToken 时显示 FAB
- **关联**：Story 2.7 · AC1/AC3
- **页面/入口**：`growth_archive_page.dart`（集成 `share_fab.dart`）
- **前置**：L0 widget 测试，状态 A，有档案，`cardToken` 非空
- **步骤**：
  1. 渲染成长档案 Tab
- **预期**：右下角出现分享 FAB widget；FAB 挂于 Scaffold.floatingActionButton 层（pinned）
- **层级**：L0

#### TC-2.084 状态 B 用户不显示 FAB
- **关联**：Story 2.7 · AC3
- **页面/入口**：`growth_archive_page.dart`
- **前置**：L0 widget 测试，用户状态 B
- **步骤**：
  1. 状态 B 用户渲染档案 Tab
- **预期**：页面中**不渲染** `ShareFab` widget
- **层级**：L0

#### TC-2.085 状态 A 无档案不显示 FAB
- **关联**：Story 2.7 · AC3
- **页面/入口**：`growth_archive_page.dart`
- **前置**：L0 widget 测试，状态 A，`petProfileProvider=null`
- **步骤**：
  1. 无档案状态 A 渲染档案 Tab
- **预期**：**不渲染** ShareFab；仅显示空态立即创建界面
- **层级**：L0

### 2.7.2 FAB 动效（首访/复访）

#### TC-2.086 首次进入触发 scale pulse + ring ripple 动效（一次性）
- **关联**：Story 2.7 · AC1
- **页面/入口**：`share_fab.dart` widget 测试
- **前置**：L0 widget 测试，prefs 中 `shareFabAnimatedShown=false`（首次）
- **步骤**：
  1. 首次渲染 ShareFab（`shareFabAnimatedShown=false`）
  2. 检查 `onAnimationShown` 回调是否被触发
  3. 模拟第二次渲染（`shareFabAnimatedShown=true`）
- **预期**：首次渲染触发动效，`onAnimationShown` 被调用一次；第二次渲染**不触发动效**（静态状态）
- **层级**：L0

#### TC-2.087 FAB 滚动不消失（pinned）
- **关联**：Story 2.7 · AC1
- **页面/入口**：`growth_archive_page.dart` + ScrollView
- **前置**：L0 widget 测试，存在足够长时间线可以滚动
- **步骤**：
  1. 渲染含长时间线的档案 Tab
  2. 模拟滚动（scrollTo 底部）
- **预期**：滚动后 FAB 仍存在于 widget tree 中（`Scaffold.floatingActionButton` 不受 ListView 滚动影响）
- **层级**：L0

### 2.7.3 分享调起

#### TC-2.088 点击 FAB 传递正确的名片 URL（不含内部 ID）
- **关联**：Story 2.7 · AC2
- **页面/入口**：`share_fab.dart` 点击事件
- **前置**：L0 mock 测试，mock `shareServiceProvider`
- **步骤**：
  1. 设置 mock cardToken = `AbCdEf123`
  2. 点击 ShareFab
  3. 捕获 `Share.share()` 调用参数
- **预期**：`Share.share` 被调用一次；传入 URL = `https://<host>/p/AbCdEf123`；URL 中**不含顺序数字 ID**
- **层级**：L0

#### TC-2.089 真机点击 FAB 弹出系统分享菜单
- **关联**：Story 2.7 · AC2 · J2
- **页面/入口**：真机系统分享
- **前置**：L2 真机（iOS 或 Android），状态 A 有档案登录用户
- **步骤**：
  1. 进入成长档案 Tab，点击右下角分享 FAB
- **预期**：系统分享菜单弹出（含 WhatsApp / Instagram / 复制链接等选项）；分享内容包含正确的名片链接 `/p/{cardToken}`
- **层级**：L2

#### TC-2.090 FAB 无障碍语义标签正确，无中文
- **关联**：Story 2.7 · AC1 · F4（i18n/无障碍）
- **页面/入口**：`share_fab.dart`
- **前置**：L0，locale=en 和 locale=id
- **步骤**：
  1. locale=en 渲染 FAB，检查 Semantics label
  2. locale=id 渲染，检查
- **预期**：locale=en：label 为英文（如「Share pet card」）；locale=id：label 为印尼语（如「Bagikan kartu hewan」）；**均不出现中文**
- **层级**：L0

---

## Story 2.8：宠物档案编辑

### 2.8.1 编辑页入口与预填

#### TC-2.091 两入口路由到同一编辑页
- **关联**：Story 2.8 · AC1 · F2
- **页面/入口**：`growth_archive_page.dart` 信息卡右上角「编辑」→ `/profile/edit`
- **前置**：L0 widget 测试，状态 A 有档案
- **步骤**：
  1. 从档案 Tab 信息卡右上角「编辑」入口进入
  2. 检查路由和页面组件类型
- **预期**：路由为 `/profile/edit`；渲染的是 `PetProfileEditPage` widget（同一编辑页）；「我的」Tab 入口（Epic 7 对接后）应指向同一路由
- **层级**：L0

#### TC-2.092 编辑页表单预填既有档案数据
- **关联**：Story 2.8 · AC1 · F1
- **页面/入口**：`/profile/edit` · `pet_profile_edit_page.dart`
- **前置**：L0 widget 测试，既有档案数据：`name="Mochi"，breed="Persia"，birthday=2022-05-15，intro="Suka main"，pet_type=CAT`
- **步骤**：
  1. 进入编辑页
  2. 检查各字段值
- **预期**：名字输入框预填「Mochi」；品种「Persia」；生日选中 2022-05-15；介绍「Suka main」；**宠物类型 ChoiceChip 「Kucing」选中但全部置灰（不可点）**；🔒 图标 + 「Can't be changed」（en）/「Tidak bisa diubah」（id）提示
- **层级**：L0

### 2.8.2 pet_type 硬禁修改（F6）

#### TC-2.093 编辑页宠物类型全部置灰，onSelected=null
- **关联**：Story 2.8 · AC1（F6）· F1
- **页面/入口**：`pet_profile_edit_page.dart` `_petTypeReadonly` 区块
- **前置**：L0 widget 测试，`pet_type=DOG`
- **步骤**：
  1. 进入编辑页
  2. 尝试点击「Kucing」（猫）ChoiceChip
  3. 尝试点击已选中的「Anjing」（狗）ChoiceChip
- **预期**：所有三个 ChoiceChip 的 `onSelected` 均为 null（Flutter 置灰不可交互）；点击无响应；选中状态（DOG）保持不变
- **层级**：L0

#### TC-2.094 服务端 PATCH 请求不含 petType 字段（双重不可改）
- **关联**：Story 2.8 · AC1（F6）· B1
- **页面/入口**：`ProfileRepository.update` → `PATCH /api/v1/pet-profiles/me`
- **前置**：L0 widget 测试，捕获网络请求
- **步骤**：
  1. 修改名字并提交编辑
  2. 捕获发出的 PATCH 请求体
- **预期**：请求体中**不含 `petType` 字段**（`PetProfileUpdateRequest` 结构上无该参数）
- **层级**：L0

#### TC-2.095 服务端 PATCH 若强行传 petType，落库值不变
- **关联**：Story 2.8 · AC1（F6）· B1（服务端硬禁）
- **页面/入口**：`PATCH /api/v1/pet-profiles/me`（MockMvc L0）
- **前置**：L0 MockMvc，档案 `pet_type=CAT`
- **步骤**：
  1. 手动构造含 `petType=DOG` 的 PATCH 请求体
  2. 查验响应和 mock 服务行为
- **预期**：HTTP 200（不拒绝，只忽略）；服务端落库时 `pet_type` 仍为 `CAT`（未被修改）；或返回 422/400 说明字段不可改——两种实现均可，关键是**DB 值不变**
- **层级**：L0

### 2.8.3 编辑提交与联动

#### TC-2.096 编辑提交成功，名片实时更新
- **关联**：Story 2.8 · AC1 · J1/J3
- **页面/入口**：`PATCH /api/v1/pet-profiles/me` → DB → `GET /p/{cardToken}`
- **前置**：L1 环境
- **步骤**：
  1. 修改宠物名字为「Mochi Pro」并提交
  2. 访问 `GET /p/{cardToken}`
- **预期**：DB 中 `name=Mochi Pro`，`updated_at` 刷新，`card_token` **不变**（编辑不重生成 token）；名片 H5 再次访问显示「Mochi Pro」
- **层级**：L1

#### TC-2.097 名片 OG 静态图重渲染被异步触发
- **关联**：Story 2.8 · AC1 · B2（@Async 重渲染）
- **页面/入口**：`PATCH /api/v1/pet-profiles/me` → `CardRerenderService`
- **前置**：L1 环境（或 L0 验证 @Async 调用）
- **步骤**：
  1. 提交编辑（改名字或头像）
  2. 检查 PATCH 请求是否即时返回 200（不被 OG 生成阻塞）
  3. 异步完成后查验 `og_image_url` 列是否更新
- **预期**：PATCH 立即返回 200（主流程不阻塞）；`CardRerenderService.@Async` 触发 `OgImageService.regenerate`；即便重渲染失败**不影响 PATCH 成功**
- **层级**：L1

#### TC-2.098 越权编辑他人档案返回 404（ownership 验证）
- **关联**：Story 2.8 · AC1（安全，owner 自 JWT）
- **页面/入口**：`PATCH /api/v1/pet-profiles/me`（MockMvc L0）
- **前置**：L0 MockMvc，JWT userId=1，但 DB 中该档案 owner_id=2
- **步骤**：
  1. 用户 1 的 JWT 发送 PATCH 请求
- **预期**：HTTP 404（档案不属于当前用户，按 ownership 安全原则返回 404 而非 403，防信息泄露）；**不更新 DB 数据**
- **层级**：L0

#### TC-2.099 名字超 20 字符被服务端 422 拦截
- **关联**：Story 2.8 · AC1 · B1/F3
- **页面/入口**：`PATCH /api/v1/pet-profiles/me`（MockMvc L0）
- **前置**：L0 MockMvc
- **步骤**：
  1. PATCH 请求体 `{"name":"超二十字符的名字超二十字符的名字超超超超超超超超超超超超超超"}`
- **预期**：HTTP 422，ProblemDetail 指向 `name` 字段；**不暴露堆栈**
- **层级**：L0

#### TC-2.100 编辑成功后档案 Tab 信息卡即时更新
- **关联**：Story 2.8 · AC1 · F1（前端刷新 petProfileProvider）
- **页面/入口**：编辑页提交 → `growth_archive_page.dart`
- **前置**：L0 widget 测试
- **步骤**：
  1. 提交编辑成功（mock 后端返回 200）
  2. 返回档案 Tab 观察信息卡
- **预期**：信息卡中名字即时更新为新值（`petProfileProvider` 被 invalidate）；不需要手动刷新
- **层级**：L0

---

## 横切关注点测试

### 多语言（i18n）

#### TC-2.101 Epic 2 全页面 locale=id 不出现中文字符
- **关联**：全 Story · i18n 约定（MEMORY.md 双语模型）
- **页面/入口**：全部前端页面
- **前置**：L0 widget 测试 + 真机，locale=id
- **步骤**：
  1. locale=id，依次进入：档案创建页、Publish Compose、成长档案 Tab（时间线/日历/详情）、存档弹窗、分享 FAB
  2. 扫描所有可见文本
- **预期**：所有用户可见文案均为印尼语或英语；**无任何中文字符**；已知印尼语硬编码处（「Linimasa Tumbuh Kembang」/「Pratinjau kartu publik」）按现状记录，不计为 bug（记于遗留债）
- **层级**：L0/L2

#### TC-2.102 locale=en 下 Epic 2 主要操作文案为英文
- **关联**：全 Story · i18n
- **页面/入口**：同上，locale=en
- **前置**：L0，locale=en
- **步骤**：
  1. locale=en 下核查主要按钮/提示文案
- **预期**：「Create Now」/「Share pet card」/「Can't be changed」/「Upload failed」等使用英文 arb 文案；**无硬编码中文**
- **层级**：L0

### 隐私护栏

#### TC-2.103 健康事件日志不含症状/评级/建议明文
- **关联**：全 Story · 架构护栏（日志不落健康数据）
- **页面/入口**：运行时日志（L1 环境）
- **前置**：L1 环境，执行存档操作（存入健康事件）
- **步骤**：
  1. 触发存档，查看服务端 INFO 日志
  2. grep 日志文件中是否含 `symptomSummary`/`aiLevel`/`adviceSummary` 关键词
- **预期**：INFO 日志中**不出现** `symptomSummary`/`aiLevel`/`adviceSummary` 字段的实际值；只允许出现业务事件 ID（如 `sourceRef`）
- **层级**：L1

#### TC-2.104 签名 URL 不出现在 INFO 日志中
- **关联**：Story 2.1 · AC3 · B6（日志护栏）
- **页面/入口**：`SignedUrlService` 运行时日志
- **前置**：L1 环境
- **步骤**：
  1. 触发签名 URL 生成（展示健康事件含私密图）
  2. grep INFO 日志中 `Signature=` 或签名 URL 特征字符串
- **预期**：INFO 日志**不含**签名 URL；DEBUG 级别允许（但生产环境 DEBUG 应关闭）
- **层级**：L1

#### TC-2.105 STS response 不将 accessKeySecret/securityToken 落入 INFO 日志
- **关联**：Story 2.1 · AC1 · B4（凭证不进日志）
- **页面/入口**：`MediaController` 运行时日志
- **前置**：L1 环境，发起 STS 凭证请求
- **步骤**：
  1. 请求 STS 凭证
  2. 检查服务端 INFO 日志
- **预期**：日志中**不含** `accessKeySecret` 或 `securityToken` 的值
- **层级**：L1

---

## 本章遗留 / 盲区

以下 5 条在当前测试集中无法或难以被充分覆盖，需在 L2 验收阶段或后续补充：

1. **H5 加载失败的重试页**（AC6 盲区）：`card.html` 为 Thymeleaf 服务端直出，网络/服务器失败为浏览器原生层；story 2.6 Dev Notes 中明确「H5 专用重试页为 L2/部署层增强，本轮未做应用内页面」。QA 无法用 App widget 测试验证，需在真实部署环境用网络代理模拟断网后验证浏览器行为。

2. **EXIF 端到端真实验证**（AC2 J3 盲区）：需真机拍摄/选取含 GPS 照片 → 直传公开桶 → 从对外分发 URL 取回 → 用 EXIF 工具断言无 GPS。这是「服务端兜底对绕过客户端仍生效」的唯一验证路径（决策 E4），需 L2 真实 OSS + 凭证 + 真机。

3. **IM→OSS 图片桥接（ImToOssArchiver）真实验证**（AC2 J3/L2 盲区）：需真实腾讯 IM 凭证 + 含图聊天会话 + 真实私密桶，验证「IM URL 不落 DB，只落自有 key」。Epic 5 接入后方可完整验证（ImMediaFetcher bean 依赖 Epic 5 提供）。

4. **里程碑 mini-epic 真供数后的名片/统计联动**（F2/F16 盲区）：当前 Epic 8（里程碑）实现后，`archive-stats` 返回的 `milestoneCompleted` 应为真实值，名片的徽章条/动态/故事数字「Tonggak」应正常渲染（非零态）。需在 Epic 8 完成后追加 L1/L2 回归验证名片徽章条正确展示。

5. **Deep Link 跨场景覆盖**（AC6 盲区）：「已装 App + 未登录点 CTA → 触发 FR-0C 登录流 → 登录后跳回该宠物档案」的完整串接（Deep Link + 登录流 + 回跳）未被现有 L0 用例覆盖，需 L2 真机专项验证。
