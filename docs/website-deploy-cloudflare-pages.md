# 官网上线手册（tailtopia.id 静态官网 → Cloudflare Pages）

> 目标：把 `website/` 这份静态官网发布到 **Cloudflare Pages**，并把主域名 **`tailtopia.id`（含 `www`）** 从现在的 Hostinger 停泊页切换成官网。
> 现状（已核实 2026-07-02）：
> - 域名 NS 已在 Cloudflare（`ariella/darwin.ns.cloudflare.com`）✅——**不用再动注册商**。
> - 根域名 `tailtopia.id` 和 `www` 现在是 Cloudflare 橙云代理，但**回源指向 Hostinger 停泊页**，所以你看到「Parked Domain name on Hostinger DNS system」。
> - 子域名 `api.` / `legal.` 走的是服务器上的 cloudflared 隧道，**本手册不碰它们**。
>
> 静态站为什么用 Pages 不用隧道：官网是纯静态（HTML/CSS/图片），Pages 免费、全球 CDN、自动 HTTPS、和你服务器解耦（服务器挂了官网也在）。隧道方案留作备选（见 §6）。

---

## 0. 你要做的 3 件事（总览）

| 步 | 做什么 | 在哪做 | 耗时 |
|---|---|---|---|
| 1 | 把 `website/` 发布成一个 Pages 项目 | Cloudflare 控制台 或 本地 `wrangler` | 5 分钟 |
| 2 | 给 Pages 绑定自定义域名 `tailtopia.id` + `www`（顺带替换掉停泊记录） | Cloudflare Pages → Custom domains | 5 分钟 |
| 3 | 验证 + 加 `www → 根域名` 跳转 | 浏览器 + Redirect Rules | 5 分钟 |

准备：① Cloudflare 账号（就是管着 tailtopia.id 那个）；② 本机装了 Node（用 wrangler 才需要，走控制台上传则不用）。

---

## 1. Step 1 — 把官网发布到 Cloudflare Pages

两种方式选一种。**第一次上线推荐「方式 A 控制台拖拽」**，最省事、零命令。以后想要一条命令重发再用「方式 B」。

### 方式 A：控制台直接上传（Direct Upload，最简单）

1. 先把要上传的文件夹准备好。上传的是 `website/` 里的**内容**（`index.html` 要在压缩包根部，不是套一层 `website/`）：
   ```bash
   cd /Users/dai/work/petgo-platform/website
   zip -r ../tailtopia-site.zip . -x ".*"
   # 生成 /Users/dai/work/petgo-platform/tailtopia-site.zip
   ```
   > 打开 zip 检查一眼：根目录直接能看到 `index.html`、`styles.css`、`assets/`，**不要**是 `website/index.html`。
2. Cloudflare 控制台 → 左侧 **Workers & Pages** → **Create** → 选 **Pages** 页签 → **Upload assets**。
3. **Project name** 填 `tailtopia`（这会决定临时域名 `tailtopia.pages.dev`）→ Create project。
4. 把 §1 的 zip 拖进去（或选文件夹）→ **Deploy site**。
5. 几十秒后给你一个 `https://tailtopia.pages.dev`，点开确认官网正常显示 → 进 Step 2。

> 以后更新官网：进这个 Pages 项目 → **Create deployment** → 再传一次新 zip 即可。

### 方式 B：本地 wrangler 一条命令（可复现，适合以后频繁更新）

```bash
cd /Users/dai/work/petgo-platform
# 首次会让你浏览器登录授权 Cloudflare
npx wrangler login
# 首次部署会问要不要新建项目，项目名填 tailtopia，生产分支随意（如 main）
npx wrangler pages deploy website --project-name=tailtopia
```
- 输出会给出 `https://tailtopia.pages.dev`，打开确认无误 → 进 Step 2。
- 以后每次改完官网，重跑最后那条 `wrangler pages deploy website --project-name=tailtopia` 就重新上线。

> 说明：目录直接传 `website`（wrangler 把它当站点根），所以 `/styles.css`、`/assets/...` 这些绝对路径能正确解析——官网就是这么引用的，别改成相对路径。

---

## 2. Step 2 — 绑定自定义域名（并替换掉 Hostinger 停泊记录）

现在官网只在 `tailtopia.pages.dev`。这步把 `tailtopia.id` 指过来。

1. 进刚建的 **`tailtopia`** Pages 项目 → **Custom domains** 页签 → **Set up a custom domain**。
2. 输入 **`tailtopia.id`**（裸根域名）→ Continue。
   - Cloudflare 会检测到根域名**已有一条指向 Hostinger 停泊的旧记录冲突**，提示你它会**更新/接管**这条记录（apex 用 CNAME 展平指向 Pages）。按提示 **Activate / 确认替换**即可。
   - 若它没自动替换、报「conflicting record」：去 **DNS → Records**，把根域名 `tailtopia.id` 那条指向 Hostinger 的 **A 记录（两条，104.21/172.67 之外那种真·Hostinger IP，或标注 parking 的）先删掉**，回来重试绑定。删的时候**只删根域名和 www 的停泊记录，别碰 `api` / `legal` 的记录**。
3. 再点一次 **Set up a custom domain**，输入 **`www.tailtopia.id`**，同样确认替换掉 www 的旧停泊记录。
4. 两个域名状态都变 **Active** 后，DNS 层就切到官网了（Pages 自动签发证书，几十秒~几分钟）。

> ⚠️ 只动 `tailtopia.id` 和 `www.tailtopia.id` 两条。`api.tailtopia.id`、`legal.tailtopia.id`、以及将来 `s.tailtopia.id` 都不要碰——那些是隧道/其他服务。

---

## 3. Step 3 — 验证 + www 跳根域名

### 3.1 验证

```bash
# 根域名应返回官网（title 是 TailTopia，不再是 Parked Domain）
curl -s https://tailtopia.id | grep -ioE "<title>[^<]*</title>"
# 期望：<title>TailTopia — The all-in-one app for pet lovers</title>

# 资源可达
curl -s -o /dev/null -w "css:%{http_code}\n" https://tailtopia.id/styles.css
curl -s -o /dev/null -w "logo:%{http_code}\n" https://tailtopia.id/assets/logo.svg
```
浏览器开 `https://tailtopia.id` 看到官网、小锁正常，即成功 ✅。

> 若还是停泊页：多半是浏览器/CDN 缓存或 DNS 还没全生效。等几分钟、强刷（Cmd+Shift+R）、或换手机流量测；`dig tailtopia.id +short` 看解析是否已切。

### 3.2 加 `www → 根域名` 跳转（推荐，统一到不带 www）

绑定后 `www.tailtopia.id` 也会显示官网，但为 SEO/统一最好 301 跳到根域名：

1. Cloudflare 控制台 → 选中 `tailtopia.id` 这个 zone → **Rules → Redirect Rules → Create rule**。
2. 规则：
   - **When incoming requests match**：`Hostname` `equals` `www.tailtopia.id`
   - **Then**：Type = **Dynamic**，Expression = `concat("https://tailtopia.id", http.request.uri.path)`，Status = **301**，勾 **Preserve query string**。
3. Deploy。之后访问 `www.` 会自动跳到不带 www 的官网。

---

## 4. 上线后要补的真实信息（改完重新部署一次）

官网里目前有几处占位，确认后在 `website/index.html` 改掉，再重跑 Step 1 部署：

| 位置 | 现在是 | 换成 |
|---|---|---|
| 客服 WhatsApp | `https://wa.me/`（空） | `https://wa.me/62xxxxxxxxxx`（你的客服号，含国码 62） |
| 支持邮箱 | `hello@tailtopia.id` | 你真实收信的邮箱 |
| 商店按钮 | Coming Soon 占位 | 上架后换 App Store / Play 链接（`store--soon` 类去掉，`<span>` 改 `<a href>`） |
| 手机预览 | HTML/CSS 还原的界面 | （可选）有真机截图后替换成 `assets/` 里的图 |

---

## 5. 常见坑

| 症状 | 原因 | 解决 |
|---|---|---|
| Pages 打开是目录列表/404 | zip 多套了一层 `website/` | 重压：`cd website && zip -r ../x.zip .`，保证 `index.html` 在 zip 根 |
| 样式全丢、纯文字 | 上传的是相对路径根不对 | 官网用绝对路径 `/styles.css`；用 Pages 根部署就对，别改成 `./` 再套子目录 |
| 绑定域名报 conflicting record | 根/www 还有旧 Hostinger 记录 | DNS 里删掉根和 www 的旧记录再绑（§2.2），**别删 api/legal** |
| 根域名还是停泊页 | 缓存 / DNS 未生效 | 强刷、等几分钟、`dig tailtopia.id +short`、换网络测 |
| 字体没加载（字重不对） | 断网/被墙 Google Fonts | 官网用 Google Fonts CDN；如需完全自托管可把 Poppins 放进 `assets/fonts` 再改 `@font-face`（可选优化，不影响上线） |
| `www` 不跳转 | Redirect Rule 没配/表达式错 | 见 §3.2；确认 rule 是 enabled |

---

## 6. 备选方案：走现有 cloudflared 隧道（一般不用）

如果你坚持不想用 Pages、想和 `legal.` 一样从服务器发官网：把 `website/` 拷到服务器某目录，用 nginx/静态服务器跑在某本地端口，再在现有 tunnel 上加一条 Public Hostname `tailtopia.id → HTTP 127.0.0.1:<port>`（步骤同 `domain-setup-cloudflare.md` §4）。
**不推荐**：纯静态站这么做等于让官网依赖你那台服务器活着，还多一层运维；Pages 全球 CDN + 零运维更合适。

---

## 7. checklist

- [ ] `website/` 已发布成 Pages 项目 `tailtopia`，`https://tailtopia.pages.dev` 正常
- [ ] Pages 绑定 `tailtopia.id`（根）+ `www.tailtopia.id`，状态均 **Active**
- [ ] 根/www 的旧 Hostinger 停泊记录已替换（`api`/`legal` 未动）
- [ ] `https://tailtopia.id` 返回官网、证书正常、资源 200
- [ ] `www.` → 根域名 301 跳转已配
- [ ] WhatsApp / 邮箱 / 商店链接等占位已换成真实值并重新部署
