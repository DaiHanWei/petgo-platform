# 部署留档 · GemPay 收款接入 → Staging（8085）

> 日期：2026-07-15 · 分支：`v1.1-epic1-fund`（commit `1edc0f2`）· 目标：staging `petgo-server-stag`
> 目的：把 Epic1 资金后端（含 GemPay 收款）部署到 staging，用 GemPay **sandbox** 验证 payin / 回调 / 查询。
> 状态：**⛔ 部署前有一个 Flyway 阻塞项待决（见 §5）**；GemPay 集成本身已本地验通（§3）。

---

## 1. 目标环境（已只读核查确认，2026-07-15）

| 项 | 值 | 备注 |
|---|---|---|
| 主机 | `62.146.239.156`（`dai@`，与生产同机） | Ubuntu + Docker + Cloudflare Tunnel |
| 容器 | `petgo-server-stag` | 已存在并在跑（127.0.0.1:**8085**→容器 8080） |
| 生产容器（勿动） | `petgo-server`（8084） | 与 staging 完全隔离 |
| 数据库 | `petgo_stag`（同一 `petgo-postgres` 容器内，独立 DB） | 生产是 `petgo`，隔离 |
| Redis | 共享 `redis` 容器，**`REDIS_DB=3`** | 生产用 DB2，隔离 |
| env 文件 | `~/.env.petgo-stag`（chmod 600） | `SPRING_PROFILES_ACTIVE=prod` / `DB_NAME=petgo_stag` / `REDIS_DB=3` / `GEMINI_MODE=live` / `IM_MODE=live`；**当前无任何 PAY_/GEMPAY_ 键** |
| 公网入口 | `https://api-stag.tailtopia.id`（Cloudflare Tunnel → 127.0.0.1:8085） | App debug 包默认打这里（`dio_client.dart` `kApiBaseUrlStag`） |
| 部署脚本 | `scripts/deploy-backend-stag.sh` | ⚠️ **只在 `stag` 分支**，fund 分支上没有；且有 `EXPECTED_BRANCH=stag` 护栏（非 stag 分支需 `ALLOW_BRANCH=1`） |
| 镜像 tag 约定 | `petgo-server:stag` / `:stag-<ts>` / `:stag-previous` | 与生产的 `:latest`/`:previous` 隔离，绝不互串 |

---

## 2. GemPay 接入范围与配置

**范围**：仅 payin（`/direct`）+ 回调（paymentCallback）+ 查询结果（`/history`）。**无 payout**（`/inquiry`+`/transfer`+`/status_query` 不接）。

**要追加到 `~/.env.petgo-stag` 的块**（凭证不入库，真实 secret 只在服务器 env 文件）：

```bash
# === 支付网关 GemPay (sandbox) — 2026-07-15 追加 ===
PAY_MODE=live
PAY_PROVIDER=gempay
GEMPAY_BASE_URL=https://sandbox-api.gempay.online/v1
GEMPAY_MERCHANT_ID=KMB0064
GEMPAY_PROJECT_NO=NO8989
GEMPAY_MERCHANT_SECRET=<sandbox secret，见密钥保管，不写入本文档/仓库>
GEMPAY_CALLBACK_URL=https://api-stag.tailtopia.id/pay/callback
```

> ⚠️ **顺序铁律**：staging 是 `prod` profile，`PayConfig` fail-closed —— prod 下禁 stub，且 `provider=gempay` 时三个凭证缺一即**拒绝启动**。所以**必须先把上面这块写进 `~/.env.petgo-stag`，再部署 fund 代码**，否则容器起不来。
> Project Name「tailtopia」不是 API 字段，无需配置。

---

## 3. GemPay sandbox 联调结果（本地实测，2026-07-15）✅

用真实 sandbox 凭证（KMB0064 / NO8989）本地直连 `sandbox-api.gempay.online` 验证，**代码无需改动**：

| 接口 | 结果 | 证据 |
|---|---|---|
| **鉴权探测** `/history` | ✅ 通 | `error_code=04`（Data not found，非鉴权错）→ 签名 `md5(mid+sec+pno)` 被接受 |
| **payin** `/direct`（MBayar_QR） | ✅ **P00** | `ref_id=363997524853264384`、`amount=10000`、`admin_fee=20`、`total_amount=9980`、`qrcode_url=https://qr-8us.pages.dev/?qrcode=mock-...` |
| **查询** `/history`（按 ref_id） | ✅ 通 | `total_rows=1`、`status=pending`（未扫码，正确）、字段 request_id/ref_id/amount 全对，`normalizeHistoryStatus` 归一正确 |
| 签名公式 `/direct` | ✅ 锁死 | 官方向量 `cd4ce010c1c3f459f116678b90b20b6d` L0 单测通过 |

> 期间发现过 `P04 Merchant not setup properly`（GemPay 侧商户未开通），已由 GemPay 修复后复测 P00。
> **业务数字**：admin_fee=20（0.2%），发币按用户支付额 `amount` 而非 `total_amount`，与 `applyCallback` 逻辑一致。

**尚未验**：回调 paymentCallback —— 需真实完成一笔支付触发 GemPay 回调，且回调要能打到公网 `api-stag.tailtopia.id/pay/callback`（**依赖本次部署**）。且**收款回调验签公式 GemPay 文档缺失**，现用 UNCONFIRMED 猜测（fail-closed）；计划：回调首次打进来后用其真实字段反推公式并锁死。

---

## 4. 部署步骤（待 §5 阻塞项解决后执行）

```bash
# 0) 【服务器】先补 GemPay env 块到 ~/.env.petgo-stag（见 §2），再继续。
#    校验：ssh dai@62.146.239.156 "grep -c '^GEMPAY_' ~/.env.petgo-stag"  # 期望 4

# 1) 【本地】fund 分支上没有 stag 部署脚本，从 stag 分支取一份（不提交，或按需提交到 fund）：
git show origin/stag:scripts/deploy-backend-stag.sh > scripts/deploy-backend-stag.sh
chmod +x scripts/deploy-backend-stag.sh

# 2) 【本地】在 fund 分支部署 staging（非 stag 分支，需放行分支护栏）：
ALLOW_BRANCH=1 ./scripts/deploy-backend-stag.sh
#    脚本：本地 mvn package → scp → 服务器 docker build :stag → 重启 petgo-server-stag(8085) → 等 /actuator/health UP

# 3) 【验证】健康 + Flyway
ssh dai@62.146.239.156 'curl -s http://127.0.0.1:8085/actuator/health'   # 期望 {"status":"UP"}
```

---

## 5. ⛔ 阻塞项：Flyway 迁移历史分叉（部署前必须先决）

**现象**：
- `petgo_stag` 库已 apply 到 **V56**，其中 **V47–V56 全是 stag 独有的 content-moderation 迁移**（init_moderation_keyword_rules … extend_comment_status）。
- **fund 分支没有 V47–V59**（从 main 切，V46 直接跳 V60；V1.1 资金迁移刻意从 V60 起就是为避开 stag 的 V47–56 占号）。

**后果**：fund 代码连 `petgo_stag` 时，Flyway 发现库里 V47–V56 是「已应用但本地 classpath 找不到的迁移」→ **validate 失败、容器启动即崩**。（同「共享 dev 库污染 / 并行 Flyway 撞号」历史教训。）

**解法选项**：

| 选项 | 做法 | 代价 | 适用 |
|---|---|---|---|
| **A. 重建 `petgo_stag`（推荐用于纯 GemPay 验证）** | drop + 重建 `petgo_stag`，让 fund `V1→V83` 全新 apply | 清掉现有 stag(moderation) staging 数据；staging 事实上从 stag 线切到 fund 线 | staging 数据可弃、当前就是要用 staging 测 fund |
| **B. 独立第二套 staging 库/容器** | 新建 `petgo_stag_fund` 库 + 新 env(`~/.env.petgo-fund`) + 新容器 `petgo-server-fund` + 新端口(如 8086) + CF 新路由 | 多一套基建 + 一条 CF 路由 | 要保留现有 stag(moderation) 不动、两线并存 |
| **C. 先把 fund 合并进 stag** | 统一迁移历史后再部署 | 大合并、Flyway 重排、冲突处理（用户此前已否决） | 不推荐 |

> 备份优先（选 A 前）：`ssh dai@62.146.239.156 'docker exec petgo-postgres pg_dump -U petgo petgo_stag' > petgo_stag-backup-$(date +%Y%m%d-%H%M%S).sql`
> ⚠️ 共享 redis 铁律：任何运维**禁止** `FLUSHALL`（会清掉生产/logistic/jbp 数据）；重建 staging 只清 `REDIS_DB=3` 的 keyspace（`redis-cli -n 3 FLUSHDB`）。

**当前决策：待用户选 A / B。** 选定后按 §4 部署。

---

## 6. 部署后验证清单

- [ ] `/actuator/health` = UP（含 DB+Redis 连通、Flyway 全量迁移成功）
- [ ] Flyway 历史到 V83：`docker exec petgo-postgres psql -U petgo -d petgo_stag -c "SELECT max(version) FROM flyway_schema_history"`
- [ ] GemPay env 生效：`docker exec petgo-server-stag env | grep -c '^GEMPAY_'`（期望 4；**不打印 secret 值**）
- [ ] payin 冒烟：登录取 JWT → `POST /api/v1/me/pawcoin/topups`（QRIS 档）→ 返回 `intentToken` + 二维码 payload
- [ ] 查询：`PaymentReconciliationService` / `/history` 能查到刚建订单（status=pending）
- [ ] **回调**：完成一笔 sandbox 支付 → GemPay POST `api-stag.tailtopia.id/pay/callback` → 意图转 PAID + 到账
      - 若回调被 403：说明 UNCONFIRMED 验签公式与实际不符 → **抓回调真实字段反推公式**并锁 L0 向量，再复部署
- [ ] 双通道去重：回调 + 查询各推进一次，只到账一次

---

## 7. 回滚

```bash
# 应用回滚（换回上一个 staging 镜像）
ssh dai@62.146.239.156 bash -se <<'EOF'
docker stop petgo-server-stag && docker rm petgo-server-stag
docker run -d --name petgo-server-stag --network jbp-net \
  -p 127.0.0.1:8085:8080 --restart unless-stopped \
  --env-file ~/.env.petgo-stag petgo-server:stag-previous
sleep 10 && curl -fs http://127.0.0.1:8085/actuator/health
EOF
# DB 回滚：Flyway 无自动 down；选 A 重建前的 pg_dump 即回滚点。
```

---

## 8. 待办 / 依赖

- [ ] §5 Flyway 分叉解法：用户选 A / B。
- [ ] `~/.env.petgo-stag` 追加 GemPay 块（§2），部署前完成。
- [ ] 回调验签公式：向 GemPay 确认，或部署后抓真实回调反推。
- [ ] （可选）把 `scripts/deploy-backend-stag.sh` 补进 fund 分支，避免每次 `git show` 取。
- [ ] 对账消费方（定时任务 / 运维端点）调用 `PaymentReconciliationService`，本次未接，属后续。
