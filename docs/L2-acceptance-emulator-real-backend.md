# L2 验收文档 —— 安卓模拟器 × 真实生产后端（自动化可覆盖部分）

> **目的**：在安卓模拟器上以「真实接口」直打**生产** `https://api.tailtopia.id`（health=UP）跑一轮端到端验收，覆盖**约 80%** 可自动化的 L2，并产出截图 + 后端交叉印证报告。
> **登录态来源（已定）**：**不另起测试实例**。生产为 `prod` profile、拒收假 idToken（安全护栏，绝不削弱），故登录态由**真 Google 登录**在模拟器上**人工完成一次**（§1 P3），之后 agent 用 adb 驱动 + 截图 + 后端 `curl`/日志印证其余流程。
> **覆盖边界**：本文档只列「agent 能用 adb 驱动 + 截图 + 后端日志/`curl` 印证」的用例。真相机 / 推送 / 真机手感归 [§6 范围外清单](#6-范围外需真机真相机要你或后端补)。
> **关联**：域名配置见 [`domain-setup-cloudflare.md`](./domain-setup-cloudflare.md)；后端部署见 [`deployment-guide-backend.md`](./deployment-guide-backend.md)。
> **已废弃**：`spec-l2-test-login-channel.md` / `runbook-l2-test-instance.md`（api-test 测试实例方案）—— 已决定不部署测试实例，改为生产直验，两文档仅留作历史记录。

---

## 1. 前置条件（开跑前必须全绿）

| # | 前置 | 验证命令 / 方式 |
|---|---|---|
| P1 | 生产后端公网在线 | `curl -s https://api.tailtopia.id/actuator/health` → `{"status":"UP"}` |
| P2 | 后端 live 凭证已配 | `~/.env.petgo` 里 Gemini=`live`、OSS key、Google client id 已填（否则对应功能降级，相关 TC 跳过并标注） |
| P3 | **登录态：真 Google 登录（人工一次）** | 模拟器（API 36 Google Play 镜像）已登录一个 Google 账号；App 内点 Google 登录走真 OAuth → 拿到真实会话。**不绕鉴权、不用 stub**（prod 拒收假 idToken）。完成后该 session 供后续需登录态 TC 复用 |
| P4 | 安卓模拟器在跑 | `adb devices` 有一台 `device` |
| P5 | App 已装真接口包 | 见 §2 构建命令 |

### 2. 构建 & 启动（真接口，直打生产）

```bash
cd petgo_app
flutter build apk --release \
  --dart-define=PETGO_API_BASE_URL=https://api.tailtopia.id \
  --dart-define=GOOGLE_SERVER_CLIENT_ID=<server client id>
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

> **本分支（L2 测试分支）已物理删除全部 mock 子系统** —— app 恒连真后端，无需再传 `PETGO_MOCK=false`（开关已不存在）。
> 登录走真 Google：`PETGO_DEV_STUB_LOGIN` 在 release 构建恒不生效（kDebugMode 护栏），故 release 包无需传它。
> 若需 debug 包做截图直达，可用 `DEV_*` 真后端驱动 flag（`DEV_REAL_LOGIN` 已随 stub 一并失效于 prod，故 prod 验收以真 Google 登录为准）。

---

## 3. 验收方法论

每条用例统一三段印证，缺一不记通过：

1. **驱动**：`adb shell input tap/swipe/text` + `keyevent` 走到目标态。
2. **看**：`adb exec-out screencap -p` 截图，比对 `_bmad-output/pages/` 原型 + 检查数据是真实后端回的（非 mock 假数据特征）。
3. **印证**：对关键写操作，SSH 后端 `docker logs petgo-server` 或直接 `curl` 对应 `/api/v1/...` 看落库/状态机正确。

**判定**：✅ 通过 / ⚠️ 通过但有观察项 / ❌ 失败（附截图 + 日志）/ ⏭️ 跳过（附原因，如凭证未配/属范围外）。

---

## 4. 测试矩阵（可自动化的 80%）

### 4.1 鉴权 & 会话

| TC | 场景 | 步骤要点 | 期望 | 印证 |
|---|---|---|---|---|
| AUTH-01 | 测试通道登录成功 | 经 §P3 通道拿会话 | 进首页登录态，顶栏无「Sign in」 | `GET /api/v1/me` 返回真实用户 |
| AUTH-02 | 401 失效态 | 清/篡改 token 后调受保护接口 | 跳登录 gate，不白屏 | 响应体 RFC9457 ProblemDetail |
| AUTH-03 | 游客可读公开内容 | 不登录看 feed | feed 可浏览，互动触发登录 gate | `GET /api/v1/feed` 200 |

### 4.2 引导 & 建档（Profile）

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| ONB-01 | 昵称确认 1/2 | 改昵称→Continue 落库 | `PATCH /me` 昵称更新，回显一致 |
| ONB-02 | 宠物状态 2/2 三态 | A 建档 / B·C 分支正确 | 状态写入 |
| PET-01 | 建档表单提交 | 名称/类型/品种(下拉)/性别(下拉)/DOB/bio 提交成功 | `POST /api/v1/pet-profiles` 落库，护照回显 |
| PET-02 | **PET TYPE 锁定** | 编辑页类型不可改、显示锁 | 后端拒绝改 type（若前端绕过） |
| PET-03 | 编辑页下拉交互 | 品种/性别底部选择器可选可存 | `PATCH` 后值持久 |
| PET-04 | 护照 paspor 渲染 | 卡片+成就进度+时间线+问诊徽章为真实数据 | 计数与后端一致 |
| PET-05 | 时间线/里程碑 | Timeline/Calendar 切换、里程碑解锁 | 里程碑标题按 code 本地化（非后端串） |
| PET-06 | 宠物名片分享 | 生成不可枚举 token 链接 | token 非自增 id（护栏） |

### 4.3 内容（Feed / 详情 / 发布）

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| FEED-01 | 首页列表 | 真实帖子流、封面图、Story/Moment 标 | `GET /feed` 数据一致 |
| FEED-02 | 多图详情 | 详情横滑 pager + `N/M` 角标 + 点击全屏缩放 | 图来自 OSS 真链 |
| FEED-03 | 评论 | 列表加载 + 发评论落库 | `POST` 评论后回显 |
| PUB-01 | 发布编辑页 | 图文输入、字数限制、提交 | `POST` 内容进审核态 |
| PUB-02 | 发布结果三屏 | done / reviewing / rejected 三态正确 | 状态机与后端一致 |
| PUB-03 | 举报/删除 sheet | 举报落库、删除自有内容 | 对应接口 200 |

### 4.4 AI 分诊（Triage，真打 Gemini）

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| TRI-01 | 上传页 | 虚线加图框、症状 500 字、免责声明 | — |
| TRI-02 | 提交→真实结果 | 真打 Gemini，落绿/黄/红一态 | `triage_task` 状态机；后端日志无 PII 泄漏 |
| TRI-03 | 绿/黄结果卡 | 结果三态卡渲染 + 本地化语言正确 | `response_locale` 落库正确 |
| TRI-04 | **红色态护栏** | 红色**零变现**、不软化、交棒兽医 | 后端红色态无任何付费/转化入口（护栏 4.2） |
| TRI-05 | 超时整页 | 超时走 P-21b 整页 + 重提交复用不重传 | 不重复上传媒体 |
| TRI-06 | 异常降级 | 失败态软引导兽医 | RFC9457 错误体 |

### 4.5 问诊（Consult / Vet 侧）

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| CON-01 | Consult 首页 | AI/兽医双卡、历史列表真实 | `GET` 历史一致 |
| CON-02 | 兽医会话 | 进会话、收发消息（IM live 时） | IM 落库/回调；stub 则 ⏭️ |
| CON-03 | 评分 | 结束后评分落库 | `POST` rating |
| VET-01 | 兽医工作台 | 队列/待办/历史拉真实数据 | 新端点契约对齐 |
| VET-02 | 在线状态 sheet | 在线/忙/离线切换持久 | 状态写入 |
| VET-03 | 请求详情富化 | 宠物身份富化字段正确 | `PetIdentityView` 字段齐 |

> CON-02 依赖腾讯 IM live；未配则标 ⏭️。VET-* 需一个兽医角色会话——单模拟器可切换登录态分别验，跨双角色实时联动属半自动（尽力而为）。

### 4.6 我的 / 设置 / 注销（安全攸关）

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| ME-01 | Me 页 | 用户卡+宠物区+我的发布真实数据 | 计数一致 |
| ME-02 | 语言切换 | 切换持久、文案随之变 | 偏好落库/本地 |
| ME-03 | 设置项 | 通知/隐私开关、版本号 | — |
| DEL-01 | **注销整页** | 删除清单 + 30 天宽限 + **输入邮箱确认守卫** | 守卫拦截不匹配邮箱 |
| DEL-02 | **注销执行** | 进入 30 天删除期，可登录撤销 | **级联删除/匿名化按 D1/D2**（护栏 7.3）——后端验数据真删/匿名 |

### 4.7 通知 & 媒体

| TC | 场景 | 期望 | 印证 |
|---|---|---|---|
| NOTIF-01 | 通知列表 + deep-link | 点通知跳对应页 | 路由正确 |
| MED-01 | OSS 上传 | 选图→处理→预签名上传成功 | OSS 真存对象；列表/头像用缩略图 |
| MED-02 | 缩略图 | 头像 `thumbWidth:240`、详情 1080 | 取的是缩略图非原图 |

---

## 5. 跨切面护栏校验（每轮必查）

| GUARD | 校验点 | 方法 |
|---|---|---|
| G1 | **红色态零变现**（4.2） | 红色分诊结果页/路径无任何付费转化 |
| G2 | **安全规则只升不降不可绕过**（4.5） | 红色不被前端软化、不可跳过交棒 |
| G3 | **注销级联删除/匿名化**（7.3 · D1/D2） | DEL-02 后端核验数据真删/匿名 |
| G4 | **错误统一 RFC9457** | 各失败态响应体含 type/title/status/traceId，**不外泄堆栈** |
| G5 | **日志无 PII/健康/令牌/签名 URL** | `docker logs petgo-server` 抽查关键流程无敏感串 |
| G6 | **对外标识不可枚举** | 名片/资源 token 非自增 id |

---

## 6. 范围外（需真机/真相机，要你或后端补）

| 项 | 为什么自动化做不了 | 补法 |
|---|---|---|
| 真相机实拍 | 模拟器相机为合成画面 | 真机手测；模拟器用相册选图代偿 |
| 推送通知（FCM） | 模拟器真推送不可靠 | 真机验 |
| 真机手感/性能/iOS 视觉 | 模拟器≠真机；iOS 视觉需 teleport 本地 | 真机 + 本地 iOS 收尾 |
| 像素级视觉签字 | 截图比对≈原型级，非 1px | 设计走查 |

> **登录**：真 Google 登录交互（同意屏/账号密码）由**你在模拟器上人工完成一次**（P3），不绕鉴权。完成后会话供后续需登录态 TC 复用——故登录态用例**在本轮范围内**，仅「点登录那一下」是人工。已决定**不部署 api-test 测试实例**，原 `spec-l2-test-login-channel.md` / `runbook-l2-test-instance.md` 作废留档。

---

## 7. 产出物（每轮验收报告格式）

- **结果矩阵**：每个 TC 一行 → ✅/⚠️/❌/⏭️ + 截图链接 + 印证摘要。
- **缺陷清单**：❌/⚠️ 项 → 复现步骤 + 截图 + 后端日志片段 + 初判（前端/后端/契约）。
- **护栏结论**：G1–G6 逐条结论（安全攸关，单列）。
- **范围外移交单**：§6 各项当轮状态（待真机）。
- **通过率**：可自动化 TC 的通过比例 + 阻塞项根因。

---

## 8. 执行前确认清单

- [ ] P1 生产后端 `/actuator/health` = UP
- [ ] P3 模拟器已真 Google 登录拿到会话（否则仅能跑游客态 AUTH-03/FEED-01 等少数 TC）
- [ ] live 凭证状态已知（Gemini/OSS/IM/Google）→ 决定哪些 TC 跑/跳
- [ ] 真接口包已装（直打 `api.tailtopia.id`，本分支已无 mock）
- [ ] 截图/日志留存目录已建
