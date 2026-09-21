# 域名配置流程手册（Cloudflare Tunnel）

> 目标：把你**新买的域名**接到 Cloudflare，给 TailTopia 后端开一个 **HTTPS 公网入口**（如 `https://api.tailtopia.id`），供 App 真实接口调用。
> 适用：后端已部署在 `62.146.239.156` 的 `127.0.0.1:8084`，外网走 **Cloudflare Tunnel**（非 nginx）。
> 配套：后端部署见 [`deployment-guide-backend.md`](./deployment-guide-backend.md) §2.5；本手册是它的「域名篇」展开版。

---

## 0. 先搞清楚：要不要买证书？—— 不用

**HTTPS 证书 Cloudflare 免费自动签发、自动续期，你不用买、不用手动配。**

链路是这样的，TLS 在 Cloudflare 边缘就终结了：

```
手机 ──https（Cloudflare 免费证书）──> Cloudflare 边缘 ──加密隧道──> 服务器 127.0.0.1:8084（明文，隧道内）
```

你服务器那头只跑明文 8084，隧道内部已加密；对外的 `https://` 由 Cloudflare 的 **Universal SSL** 兜底。只有「不用 Cloudflare、自己直接暴露服务器」才需要操心证书（那也用免费的 Let's Encrypt，基本没人花钱买）。你这套用不上。

---

## 1. 总览：你要做 4 步

| 步 | 做什么 | 在哪做 | 大约耗时 |
|---|---|---|---|
| 1 | 把域名托管到 Cloudflare（改 nameserver） | Cloudflare 控制台 + 你买域名的注册商 | 改 5 分钟，生效 几分钟~24h |
| 2 | 确认服务器上 `cloudflared` 隧道在跑 | SSH 到服务器 | 2 分钟 |
| 3 | 加一条 Public Hostname 路由（域名 → 8084） | Cloudflare Zero Trust 控制台 | 3 分钟 |
| 4 | 验证 HTTPS 通 + 填进 App 打包 | 浏览器/curl + 本地 | 5 分钟 |

需要准备：① 你的域名；② Cloudflare 账号（免费）；③ 服务器 SSH 权限（`dai@62.146.239.156`）。

---

## 2. Step 1 — 把域名托管到 Cloudflare

Cloudflare 要能管你的域名（签证书、做隧道路由），前提是域名的 **DNS 解析交给 Cloudflare**。做法是把注册商处的 **nameserver（NS）** 改成 Cloudflare 给的那两个。

1. 登录 [dash.cloudflare.com](https://dash.cloudflare.com) → **Add a site / 添加站点** → 输入你的裸域名（如 `tailtopia.id`，**不带** `http://`、不带 `www`）。
2. 选 **Free（免费）** 套餐 → Continue。
3. Cloudflare 扫描现有 DNS 记录（新域名通常没有，跳过即可）→ 它给你 **2 个 nameserver**，形如：
   ```
   xxx.ns.cloudflare.com
   yyy.ns.cloudflare.com
   ```
4. 去**你买域名的那家注册商**后台，找 **「Nameserver / DNS 服务器 / 域名服务器」** 设置，把原来的两条 **替换成** Cloudflare 给的这两条，保存。
   - 📍 **你的域名在 Hostinger**（当前 NS 是 `nebula/aurora.dns-parking.com`，那是 Hostinger 的停泊 NS）：登录 hpanel → **Domains → tailtopia.id → DNS / Nameservers** → 选 **Change nameservers / Use custom nameservers** → 把 ns1/ns2 填成 Cloudflare 给的两条 → Save。
   - ⚠️ 是改 **nameserver**，不是加 A 记录。改完 Hostinger 的 DNS 面板就不管用了，以后 DNS 都在 Cloudflare 改。
5. 回 Cloudflare 点 **Done / 检查 nameserver**。等状态变成 **Active（已激活）**——快则几分钟，慢则数小时（NS 全球生效有延迟）。激活前后面步骤先别做。

> 验证是否生效（本地终端）：
> ```bash
> dig NS tailtopia.id +short
> # 输出里出现 xxx.ns.cloudflare.com / yyy.ns.cloudflare.com 即已生效
> ```

---

## 3. Step 2 — 确认服务器上隧道在跑

后端文档说 `cloudflared` 已作为 systemd 服务常驻。先确认它活着（否则路由配了也不通）：

```bash
ssh dai@62.146.239.156
systemctl status cloudflared    # Active: active (running) 即 OK，按 q 退出
```

- 若 **active (running)** → 直接进 Step 3。
- 若没装/没跑 → 这台是和 Logistic 共用的隧道，**别自己乱起**；先确认用的是哪个 tunnel（Logistic 那边可能已建好同一个），再到 Cloudflare 控制台对应 tunnel 上加路由即可。隧道本身一台机一个就够，多个项目共用、各加各的 hostname。

同时确认后端在本机通：
```bash
ssh dai@62.146.239.156 'curl -s http://127.0.0.1:8084/actuator/health'
# 期望 {"status":"UP",...}；若不是 UP，先按 deployment-guide-backend.md 把后端跑起来
```

---

## 4. Step 3 — 加一条 Public Hostname 路由

这步把「公网域名」映射到「服务器本机 8084」。

1. Cloudflare 控制台 → 左侧 **Zero Trust** → **Networks → Tunnels**。
2. 点你那台服务器对应的 **现有 tunnel**（和 Logistic 共用的那个）→ 进去选 **Public Hostname** 标签 → **Add a public hostname**。
3. 填：
   | 字段 | 填什么 |
   |---|---|
   | **Subdomain** | `api`（建议；最终就是 `api.tailtopia.id`） |
   | **Domain** | 下拉选你刚接入的 `tailtopia.id` |
   | **Path** | 留空 |
   | **Type** | `HTTP` |
   | **URL** | `127.0.0.1:8084` |
4. **Save hostname**。

> 注意：Type 选 **HTTP**、URL 写 `127.0.0.1:8084`——因为隧道是从服务器本机连后端，后端本机只跑明文。对外那层 HTTPS 是 Cloudflare 自动加的，不用你管。

保存后 Cloudflare 会自动建好对应 DNS 记录（一条橙色云朵 proxied 的 CNAME），并自动签发该子域名的证书（几十秒内生效）。

---

## 5. Step 4 — 验证 + 接入 App

### 5.1 验证 HTTPS 公网通

浏览器或终端访问（注意是 https，且走你刚配的子域名）：
```bash
curl -s https://api.tailtopia.id/actuator/health
# 期望 {"status":"UP",...}，且证书是 Cloudflare 签的（浏览器小锁正常）
```
- 通 → 域名配置完成 ✅。
- 502/523 → 后端没跑或路由 URL 写错（见 §6）。
- 证书警告/不安全 → 多半 NS 还没完全生效或刚配好没等够，等几分钟重试。

### 5.2 填进 App 打包

这个 `https://api.tailtopia.id` 就是打包时的 **`PETGO_API_BASE_URL`**：

```bash
flutter build apk --release \
  --dart-define=PETGO_MOCK=false \
  --dart-define=PETGO_API_BASE_URL=https://api.tailtopia.id \
  --dart-define=PETGO_DEV_STUB_LOGIN=false \
  --dart-define=GOOGLE_SERVER_CLIENT_ID=<你的 server client id>
```

> - **只填到域名根**，不要带 `/api/v1`——代码请求路径自带 `/api/v1`。
> - 真实 Google 登录还需 `GOOGLE_SERVER_CLIENT_ID`，且要在 Google 控制台为包名 `com.tailtopia.app` + APK 签名 SHA-1 注册 Android OAuth client（详见打包说明）。
> - HTTPS 域名天然规避了 Android release 禁明文 HTTP 的限制，不用改 cleartext 配置。

---

## 6. 常见坑

| 症状 | 原因 | 解决 |
|---|---|---|
| Cloudflare 一直 "Pending Nameservers" | 注册商 NS 没改对/没生效 | 核对两条 NS 与 Cloudflare 给的完全一致；`dig NS tailtopia.id` 看是否切换；等最长 24h |
| `https://api.…` 打不开 / DNS 找不到 | 路由没建或子域名拼错 | Step 3 重查；确认 DNS 里有 `api` 的 proxied CNAME（橙云） |
| 访问返回 **502 Bad Gateway** | 后端没跑 | `ssh … docker ps` 看 petgo-server；`curl 127.0.0.1:8084/actuator/health` 在服务器本机是否 UP |
| 访问返回 **523 Origin Unreachable** | 隧道 URL 写错/隧道没跑 | 路由 URL 必须 `127.0.0.1:8084`（HTTP）；`systemctl status cloudflared` |
| 证书报错 / 不安全 | NS 刚生效证书还没签好 | 等几分钟；确认走的是子域名而非裸 IP |
| App 连不上但浏览器能开 | base URL 带了多余路径/末尾斜杠 | `PETGO_API_BASE_URL` 只到域名根，别带 `/api/v1`、别带结尾 `/` |
| 手机能开网页但 App 报跨域/超时 | 后端 CORS / 健康但接口 401 | 接口需登录态属正常；CORS 看后端 prod 配置是否放行 |

---

## 7. checklist

- [ ] 域名已在 Cloudflare，状态 **Active**
- [ ] 注册商 nameserver 已改成 Cloudflare 两条，`dig NS` 已验证
- [ ] 服务器 `cloudflared` **running**，`127.0.0.1:8084/actuator/health` 本机 UP
- [ ] Cloudflare Tunnel 加了 Public Hostname：`api.tailtopia.id` → `HTTP 127.0.0.1:8084`
- [ ] 公网 `https://api.tailtopia.id/actuator/health` 返回 `UP`、证书正常
- [ ] 域名根（不带 `/api/v1`）作为 `PETGO_API_BASE_URL` 传给 `flutter build apk`
- [ ] （真实 Google 登录）`GOOGLE_SERVER_CLIENT_ID` 已备 + Android OAuth client 已用 `com.tailtopia.app` + SHA-1 注册
