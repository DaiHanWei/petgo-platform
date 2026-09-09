# GemPay 收款网关对接 · 后端技术设计

> 分支 `v1.1-epic1-fund` · 2026-07-15 · 面向 Epic 1（PawCoin 钱包与充值 / 资金地基）
> 上游文档：GemPay ReDoc（`https://development.gempay.online/redoc-static.html`，API v2.0.15，OpenAPI 3.1.0）
> **接入范围（2026-07-15 用户定）**：仅 **payin（`/direct`）+ 回调（paymentCallback）+ 查询结果（`/history`）**。
> **暂无 payout 需求** —— 放款 `/inquiry`+`/transfer`+`/status_query` 不接（`disburse()` fail-fast 防误用）。§9 保留为未来参考。

---

## 0. TL;DR / 决策摘要

**GemPay 替换 Midtrans 作为真实（`live`）收款网关。** 不是从零起 —— **Story 1.1（已 done）建好的 `PaymentGateway` 抽象、`payment_intents` 表（V60）、`PaymentIntentService` 幂等/双通道去重收口，全部原样复用。** 改动集中在"网关实现 + 回调正文格式适配"这一层。

改动面（文件级见 §11）：
1. **新增** `GemPayGateway implements PaymentGateway`（form-urlencoded + md5 签名）。
2. **新增** GemPay 专用回调归一化（`CallbackParser` 加 GemPay 分支 / `GatewayStatus.fromGemPay`）。
3. **改** `PayCallbackController`：GemPay 回调是 `application/x-www-form-urlencoded` —— 当前 `@RequestBody Map`（绑 JSON）**收不到**，必须改绑 form 参数；且应答体从 `{"status":"OK"}` 改成 GemPay 约定的 `{"status":true,"error_code":"00","error_desc":""}`。
4. **改** `PayProperties` / `PayConfig`：加 `provider` + GemPay 凭证字段（`merchantId`/`projectNo`/`merchantSecret`/`callbackUrl`）。
5. **改** `.env.example` + `application.yml`：加 GemPay 占位块。
6. **不动** `payment_intents` schema（V60 已够用，无新迁移）、不动 `PaymentIntentService` 收口逻辑、不动 `PaymentIntent` 状态机。

> ⛔ **阻塞项（先解决再上 live）**：GemPay 文档里 **收款回调 `paymentCallback` 的 `signature` 验签公式缺失**（其它 6 个接口 + 放款回调都给了公式，唯独收款回调没写）。没有它 → live 回调无法安全验真伪。**必须找 GemPay 商务/技术确认公式**。临时策略见 §6.3。

---

## 1. 现状盘点（Story 1.1 已建，复用勿重造）

| 组件 | 位置 | 复用方式 |
|---|---|---|
| 网关抽象接口 | `shared/pay/PaymentGateway`（`createCharge`/`disburse`/`verifyCallback`/`parseCallback`） | **接口不变**，新增一个实现 |
| 桩网关 | `shared/pay/StubPaymentGateway`（`mode=stub` 默认，L0/L1 免凭证） | 保留，仍是默认 |
| Midtrans 实现 | `shared/pay/MidtransGateway` | **保留可编译，`live` 不再选它**（见 D-1） |
| 装配 | `shared/pay/PayConfig`（按 `mode` 选 bean，fail-closed 启动护栏） | 加 `provider` 维度 |
| 配置 | `shared/pay/PayProperties`（`petgo.pay.*`） | 加 GemPay 字段 |
| 收款 DTO | `ChargeRequest(orderId,amount,currency,channel,purpose)` / `ChargeResult(gatewayRef,payload,rawMeta)` | **不变** |
| 回调归一化 | `PaymentCallback(orderId,gatewayRef,status,rawMeta)` / `CallbackParser` / `GatewayStatus` | 加 GemPay 分支 |
| 意图表 | `payment_intents`（V60；`public_token` 唯一、`gateway_ref` 唯一去重、`@Version` 乐观锁、`gateway_meta` JSONB） | **不动，无新迁移** |
| 意图状态机 service | `pay/service/PaymentIntentService`（`createIntent` 幂等 + `applyCallback` 三闸去重 + `attachCharge` + `statusOf` 轮询） | **不动** |
| 回调 Controller | `pay/web/PayCallbackController`（`POST /pay/callback`，SecurityConfig permitAll） | 改绑定 + 应答体 |
| 到账事件 | `pay/event/PaymentIntentPaidEvent`（同事务发布，供 1.2/1.3 入账 hook） | **不动** |

**关键设计资产**：意图对外只暴露不可枚举 `public_token`（`CardTokenGenerator` = SecureRandom base62）；回调/轮询双通道由 `applyCallback` 单一收口，靠 `gateway_ref` 唯一约束 + Redis 前置 + 终态守卫，**只推进一次**。GemPay 完全长在这套骨架上。

---

## 2. GemPay ↔ 现有（Midtrans 形状）抽象的差异

| 维度 | Midtrans（现有代码假设） | GemPay（新） | 影响 |
|---|---|---|---|
| Content-Type | 收款 JSON；回调 JSON | **全部 `application/x-www-form-urlencoded; charset=UTF-8`** | createCharge 发 form；Controller 改绑 form |
| 鉴权 | `Authorization: Basic base64(serverKey:)` 头 | **无 header token**；`merchant_id`+`project_no` 明送，`merchant_secret` 只拼进 md5 签名 | 无 Basic 头；每请求带 signature |
| 签名算法 | SHA-512 | **md5**，且**每个接口拼接公式不同** | 新签名工具，公式表见 §4 |
| 收款端点 | `/v2/charge` | **`POST {base}/direct`**（base=`.../v1`） | 新 URL + 字段 |
| 回调定位键 | `order_id`（=public_token）+ `transaction_id`（=ref） | **`request_id`（=我方 public_token）+ `ref_id`（=网关 ref）** | 字段名映射，见 §3 |
| 回调状态 | `transaction_status`（settlement/pending/expire/deny…） | **`status` = `success` \| `failure`**（回调只这两态） | 新状态映射 |
| 回调应答 | 200 任意体 | **必须回 JSON `{"status":true,"error_code":"00","error_desc":""}`**（失败回 `false`+错误码） | Controller 应答体改 |
| 回调地址配置 | 网关后台/固定 | **每次 `/direct` 请求带 `callback_url` 字段** | createCharge 传入回调 URL |
| Base URL | 沙箱/生产 midtrans | 收款 沙箱 `https://sandbox-api.gempay.online/v1` · 生产 `https://api.gempay.online/v1` | 配置改 |

---

## 3. 字段映射（核心 —— 别搞错 request_id/ref_id）

| 我方内部概念 | `payment_intents` 列 | GemPay `/direct` 请求字段 | GemPay 回调字段 |
|---|---|---|---|
| 对外订单号（不可枚举 token） | `public_token` | **`request_id`**（≤128 字符，仅字母数字下划线连字符 —— base62 token 天然满足） | **`request_id`** |
| 网关订单号（去重权威键） | `gateway_ref` | —（响应回 `ref_id`） | **`ref_id`** |
| 金额（IDR 无小数，整型） | `amount` | `amount`（整数） | `amount` / `total_amount`(扣手续费后) |
| 渠道 | `channel`（`QRIS`\|`PAWCOIN`） | `channel`（GemPay Code，见 §5.1） | `channel` |
| 用途 | `purpose` | `description`（人读文案，**绝不含 PII/特殊字符**） | — |
| 结果状态 | `status` | — | `status`(success/failure) |

> **要点**：我方 `public_token` 直接当 GemPay 的幂等 `request_id`（`ChargeRequest.orderId` 已经是它）。GemPay 侧 `ref_id` 回填到 `gateway_ref`（`attachCharge` 已有这条路径 —— `/direct` 响应拿到 `ref_id` 即 `attachCharge(publicToken, ref_id, meta)`）。回调按 `ref_id`→`gateway_ref` 优先定位、回退 `request_id`→`public_token`（`PaymentIntentService.resolve` 现成逻辑，字段名对上即可）。

---

## 4. 签名工具设计（md5，逐接口公式）

新增 `shared/pay/GemPaySignature`（package-private 工具类，纯 JDK `MessageDigest("MD5")`，照 `MidtransGateway.sha512Hex` 的十六进制小写实现；**常量时间比对用 `MessageDigest.isEqual`**）。

**拼接=各字段原样字符串直接相连，无分隔符，再 md5 取十六进制小写。** `merchant_secret` 只进摘要、绝不作为独立字段上送、绝不落库/日志。

| 接口 | 公式 |
|---|---|
| **收款 `/direct`** | `md5(request_id + amount + merchant_id + channel + merchant_secret + project_no)` |
| 放款 `/inquiry` | `md5(request_id + merchant_id + merchant_secret + 'inquiry')` |
| 放款 `/transfer` | `md5(request_id + merchant_id + merchant_secret + 'transfer')` |
| `/balance_query` | `md5(request_id + merchant_id + merchant_secret + 'balance_query')` |
| `/status_query` | `md5(request_id + merchant_id + merchant_secret + 'status_query')` |
| `/history` | `md5(merchant_id + merchant_secret + project_no)` |
| 放款回调 | `md5(partner_ref_id + merchant_id + merchant_secret + 'callback')` |
| **收款回调** | ⛔ **文档缺失，待确认**（§6.3） |

**官方 `/direct` 验算向量（写进 L0 单测锁死）**：
`request_id=R19K251220_DE4DA303A8AD` `amount=50000` `merchant_id=KMB0000` `channel=MBayar_QR` `merchant_secret=f3c53530fc444b3afa63d2c406dd7438` `project_no=PROJECT001`
→ 明文 `R19K251220_DE4DA303A8AD50000KMB0000MBayar_QRf3c53530fc444b3afa63d2c406dd7438PROJECT001`
→ **signature `cd4ce010c1c3f459f116678b90b20b6d`**

---

## 5. `createCharge` 设计（`GemPayGateway`）

`POST {base}/direct`，`application/x-www-form-urlencoded`。用 `RestClient` + `SimpleClientHttpRequestFactory` timeout（照 `MidtransGateway` 骨架），body 用 `MultiValueMap<String,String>` + `MediaType.APPLICATION_FORM_URLENCODED`。

请求字段（必填带\*）：
- `merchant_id`\* / `project_no`\* ← 配置
- `request_id`\* ← `ChargeRequest.orderId`（=public_token）
- `amount`\* ← `ChargeRequest.amount`
- `channel`\* ← §5.1 映射
- `signature`\* ← §4 md5
- `description`\* ← 安全文案（如 `"PawCoin Topup"`，**不放 PII、不放特殊字符**，否则被 WAF 拦）
- `callback_url`\* ← 配置 `petgo.pay.gempay.callback-url`（我方 `https://api.tailtopia.id/pay/callback`）
- `redirect_url`（E-Wallet 必填）/ `payer`（可选）/ `response_qr=url`（QRIS 要返回二维码图片链接时）

响应解析 → `ChargeResult(gatewayRef, payload, rawMeta)`：
- **先判 `status`（bool）+ `error_code`**：非 `P00` 抛 `PayException`（只带安全文案 + error_code，不外泄 body）。错误码：`P01`参数不全 `P02`request_id 重复 `P03`鉴权失败 `P04`商户未配好 `P05`金额非整数 `P10`~`P13`…
- `gatewayRef` ← `ref_id`
- `payload`（前端付款载荷）按渠道取：QRIS → `qrcode`（或 `qrcode_url`）；E-Wallet → `ewallet_url`；VA → `virtual_account`
- `rawMeta` ← 脱敏快照（**剔除 `signature`**，照 `CallbackParser.sanitize`；`admin_fee`/`total_amount`/`kyc_name` 可留，无凭证）

### 5.1 渠道映射（我方 `PayChannel` → GemPay Code）

V1 Epic1 只需 QRIS：`QRIS → MBayar_QR`（✅ 开通，无最低额）。
可选放开：`DANA_EWALLET`（✅，min 10k，需传 `redirect_url`）、VA 系（`BNI_VA`/`Mandiri_VA`/`Permata_VA`/`BRI_VA`/`CIMB_VA`，min 10k~15k）。
**关闭中（勿用）**：`BCA_VA`、`OVO_EWALLET`、`ShopeePay_EWALLET`。

> 映射建议放 `GemPayGateway` 内私有 `switch`，非法/未开通 channel → `PayException`。最低额校验建议在**下单 service（1.3）**前置（避免网关 `P13` 金额未达下限）。

---

## 6. 回调设计

### 6.1 Controller 改造（`PayCallbackController`）

```java
// 之前：@RequestBody(required=false) Map<String,Object> body  —— 绑 JSON，收不到 GemPay 的 form
// 之后：绑 form 参数
@PostMapping(path = "/pay/callback",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
public Map<String,Object> callback(@RequestParam MultiValueMap<String,String> form) {
    Map<String,Object> body = form.toSingleValueMap();  // 归一成既有 Map 契约
    if (!gateway.verifyCallback(body)) throw AppException.forbidden("非法回调");
    paymentIntentService.applyCallback(gateway.parseCallback(body));
    return Map.of("status", true, "error_code", "00", "error_desc", "");  // ← GemPay 约定应答
}
```

> `verifyCallback`/`parseCallback` 入参仍是 `Map<String,Object>`（接口不变）。仅 Controller 层把 form 转 Map。**仍绝不打印 body/签名/凭证。** 失败路径（验签失败已 403；若要按 GemPay 语义回 `{"status":false,...}` 而非 403，可在 4.x rework 再定；V1 保持 403 更安全，GemPay 侧会重试）。

### 6.2 归一化（`GemPayGateway.parseCallback` / `CallbackParser` GemPay 分支）

- `orderId` ← `request_id`
- `gatewayRef` ← `ref_id`
- `status` ← `GatewayStatus.fromGemPay(str(body.status))`：`success→PAID`、`failure→FAILED`（GemPay 收款回调无 pending/expire；过期/未支付走轮询 `/history` 或不回调）
- `rawMeta` ← 脱敏（剔除 `signature`）；保留 `amount`/`total_amount`/`payment_datetime`/`channel`/`rrn`/`issuer_name`

新增 `GatewayStatus.fromGemPay(String)`：
```java
public static GatewayStatus fromGemPay(String s) {
    if (s == null) return PENDING;
    return switch (s.trim().toLowerCase()) {
        case "success" -> PAID;
        case "failure" -> FAILED;
        case "expired" -> EXPIRED;              // 轮询 /history 才有
        default -> PENDING;                      // pending/未知 → 不推进
    };
}
```

### 6.3 ✅ 验签公式已确认（2026-07-15 sandbox 实测反推）

文档未给收款回调签名公式，经真实 sandbox 回调反推确认：**收款回调 = `/direct` 完全同式**

```
md5(request_id + amount + merchant_id + channel + merchant_secret + project_no)
```

实测向量：`STAGCB1784102287 + 10000 + KMB0064 + MBayar_QR + <secret> + NO8989 → 808e13c586ef44893c9d86d98a08e00e`（已写进 `GemPaySignatureTest.callbackMatchesRealSandboxVector` L0 锁死）。

- `verifyCallback` 用配置的 `merchant_id`/`project_no`（权威，不信正文）+ 回调正文的 `request_id`/`amount`/`channel` + 配置 `secret` 计算，常量时间比对。
- 反推方法留档：一笔 `/direct` 的 `callback_url` 指向抓包端点（webhook.site），浏览器完成 sandbox mock 付款 → 抓到真实回调字段 + `signature` → 暴力比对各字段拼接的 md5 命中。

### 6.4 金额核对（补 Story 1.1 Review 遗留 P2）

`applyCallback` 现在不核对回调金额。GemPay 收款回调带 `amount`。建议在 `GemPayGateway.parseCallback` 或 service 层：**回调 `amount` ≠ 意图 `amount` → 判 FAILED + 记对账异常告警，绝不发满额 koin**（少付/部分结算防线）。这条与 Story 1.1 Review 的 P2/P3 findings 合并处理。

---

## 7. 配置与凭证

`PayProperties` 新增（`petgo.pay.*`）：
```yaml
petgo:
  pay:
    mode: ${PAY_MODE:stub}                 # stub | live（保留：prod fail-closed 启动护栏）
    provider: ${PAY_PROVIDER:gempay}       # gempay | midtrans（新；live 时选哪家实现，见 D-1）
    timeout-seconds: ${PAY_TIMEOUT_SECONDS:10}
    gempay:
      base-url:       ${GEMPAY_BASE_URL:https://sandbox-api.gempay.online/v1}
      disburse-url:   ${GEMPAY_DISBURSE_URL:https://sandbox-api.gempay.online/api}
      merchant-id:    ${GEMPAY_MERCHANT_ID:}
      project-no:     ${GEMPAY_PROJECT_NO:}
      merchant-secret:${GEMPAY_MERCHANT_SECRET:}   # 只进 md5，绝不入库/日志
      callback-url:   ${GEMPAY_CALLBACK_URL:https://api.tailtopia.id/pay/callback}
```
`.env.example` 加对应占位块（凭证留空 + 注释「stub 默认免凭证 / secret 绝不入库不落日志」）。

`PayConfig` 选 bean（保留 fail-closed）：
```java
if ("live".equalsIgnoreCase(mode)) {
    if ("gempay".equalsIgnoreCase(provider)) {
        // merchantId/projectNo/merchantSecret 缺任一 → 拒启动
        return new GemPayGateway(props);
    }
    return new MidtransGateway(props);       // 保留旧路径
}
if (prod) throw ...;                          // prod 禁 stub（既有护栏）
return new StubPaymentGateway(props);
```

---

## 8. 端到端时序（收款，QRIS 充值为例）

1. App `POST /api/v1/...topup`（Story 1.3，JWT + `Idempotency-Key` + 写限流）→ `createIntent`（建 PENDING 意图，返回 `public_token`）。
2. Service 调 `gateway.createCharge(ChargeRequest(publicToken, amount, "IDR", "QRIS", "PAWCOIN_TOPUP"))` → GemPay `/direct` → 拿 `ref_id` + `qrcode` → `attachCharge(publicToken, ref_id, meta)` 回填 `gateway_ref`。
3. App 渲染二维码（`payload`）；用户扫码付款。
4. GemPay 付款完成 → `POST /pay/callback`（form-urlencoded）→ 验签 → `applyCallback` → `markPaid` + 同事务发 `PaymentIntentPaidEvent` → 1.2/1.3 入账 hook（双分录 + PawCoin 到账）。
5. App 侧 `statusOf` 轮询（Story 1.5）读意图状态，只读不主动刷网关。
6. 双通道去重：回调 + 轮询各到一次，`applyCallback` 三闸只推进一次。

---

## 8.5 查询结果接口（`/history`，轮询通道 —— 已接）

回调缺失/迟到时的对账通道。**只查收款，不涉及 payout。**

- **GemPay 端点**：`POST {base}/history`（form-urlencoded），签名 `md5(merchant_id + merchant_secret + project_no)`（`GemPaySignature.history`）。按 `ref_id`（=我方 `gateway_ref`）过滤、`length=1` 查单笔。
- **响应 `status` 归一化**（`GemPayGateway.normalizeHistoryStatus`）：`Success` / **`Failure => Success`** / **`Expired => Success`**（复合态=最终已付）→ PAID；`Failure`→FAILED；`Expired`→EXPIRED；`Pending`/`Initial`→PENDING。
- **网关能力**：`PaymentGateway.queryCharge(gatewayRef): Optional<PaymentCallback>`（default `empty`，仅 GemPay 覆盖；stub/midtrans 不支持主动查询）。归一化成 `PaymentCallback` 后交**同一 `applyCallback` 单一收口** → 与回调形成双通道、只推进一次（Redis 前置 + `gateway_ref` 唯一 + 终态守卫）。
- **对账 service**：`PaymentReconciliationService.reconcile(publicToken)`（单笔）/ `reconcilePending(limit)`（批量）。**系统/运维级**，非 App 面向——App 状态查询仍走 `statusOf`（只读本地库，不刷网关，延续 Story 1.5 契约）。消费方（定时任务 or 运维端点）为**待定的小follow-up**，能力已就绪。

## 9. 放款/退款前瞻（Story 4.6，当前无 payout 需求，方向性存档）

GemPay 放款是**两步**，与 Midtrans Iris 单步 `disburse()` 不同：
1. `POST /inquiry`（`remit_type`=bank/wallet、`account_no`、`bank_code`、`amount`、`partner_ref_id`）→ 返回 `inquiry_id` + **`account_name`（银行权威户名，可做打款前核对）**。
2. `POST /transfer`（凭 `inquiry_id`）→ 返回 `ref_id` + `status`(success/pending)。
3. 结果：`disbursementCallback`（有验签公式）或主动 `POST /status_query` 轮询；`POST /balance_query` 查商户余额。

**适配方向（4.6 rework 定稿）**：`GemPayGateway.disburse()` 内部串 inquiry→transfer（对上保持单步 `DisburseRequest`→`DisburseResult` 契约最省改动）；`account_name` 核对是否作为硬闸留 4.6 决策（退款已是 admin 两段审批，可在 inquiry 阶段surfaces 户名给审批人）。银行 Code 用 GemPay「List of Banks」（BCA=014、BRI=002、Mandiri=008…），钱包 Code 用「List of Wallets」（DANA=915、OVO=912、GOPAY=914…）。

---

## 10. 安全护栏落点（对齐 CLAUDE.md §Enforcement）

- **凭证 env 注入不入库**：`merchant_secret` 只进 md5，`.env.example` 仅占位。
- **签名常量时间比对**：`MessageDigest.isEqual`，md5 十六进制小写。
- **脱敏**：`signature`/`merchant_secret` 绝不进 `gateway_meta`、绝不漂日志；异常只 log 类名 + error_code（照 `PayException`）。放款 PII（`account_no`/`account_name`）绝不 log。
- **fail-closed 启动 + 回调**：`PayConfig` 缺凭证拒启动；收款回调验签公式未定前默认拒（§6.3）。
- **不可枚举 token**：对外 `public_token`=`request_id`，不外露自增 id。
- **幂等/去重**：`request_id` 幂等建单 + `ref_id`→`gateway_ref` 唯一约束双通道去重（既有）。
- **金额核对**：回调金额 ≠ 意图金额 → 拒发 koin + 告警（§6.4）。
- **红色态零变现**：充值入口的红色态门控在业务层（1.5/4.2），本网关层不涉及。
- **异步纪律**：到账入账走**同事务** `PaymentIntentPaidEvent`（禁 AFTER_COMMIT，照 1.1/1.3 血泪）；禁引 MQ。

---

## 11. 改动清单（文件级）

**新增（后端 main）**
- `shared/pay/GemPayGateway.java`（createCharge/disburse/verifyCallback/parseCallback，form-urlencoded + md5）
- `shared/pay/GemPaySignature.java`（md5 逐接口签名工具，纯 JDK）

**修改（后端 main）**
- `shared/pay/GatewayStatus.java`（加 `fromGemPay`）
- `shared/pay/CallbackParser.java`（加 GemPay 字段分支，或由 `GemPayGateway` 自带解析）
- `shared/pay/PayProperties.java`（加 `provider` + `gempay.*` 字段 + getter/setter）
- `shared/pay/PayConfig.java`（`live` 时按 `provider` 选 GemPay/Midtrans，保留 fail-closed）
- `pay/web/PayCallbackController.java`（form 绑定 + GemPay 应答体）
- `src/main/resources/application.yml`（`petgo.pay.gempay` 块）
- `.env.example`（GemPay 占位块）

**新增（后端 test）**
- `shared/pay/GemPaySignatureTest.java`（官方 `/direct` 向量 `cd4ce0…` 锁死 + 逐接口公式）
- `shared/pay/GemPayGatewayTest.java`（createCharge form 编码 / 响应解析 / error_code→异常 / 状态映射 / 脱敏）
- 扩 `PaymentIntentIntegrationTest`（stub 下回调 form 通路 + 应答体 + 双通道去重回归）

**不动**
- `payment_intents`（V60，无新迁移）· `PaymentIntent`/`PaymentStatus`/`PaymentPurpose`/`PayChannel` · `PaymentIntentService` · `PaymentIntentPaidEvent` · `StubPaymentGateway` · `MidtransGateway`（保留可编译）

---

## 12. 验收分层（L0/L1/L2）

- **L0（云端可跑）**：`GemPaySignatureTest`（向量确定性）、`GemPayGatewayTest`（form 编码/响应解析/状态映射/脱敏 mock）、`GatewayStatus.fromGemPay`、`PayConfig` 选 bean + fail-closed。`mvn -B compile|package`。
- **L1（本地 docker pg+redis）**：`mode=stub` 全链 —— 建意图→回填 ref→回调 form 通路→双通道去重→到账事件；Flyway V60 validate（schema 无改动，回归即可）。用干净 scratch/`petgo_l1` 库（避共享库污染，照记忆库）。
- **L2（GemPay sandbox 凭证，留本地/带凭证环境）**：`mode=live provider=gempay` 打真实 sandbox 跑通一笔 QRIS 收款回调；**依赖 §13 阻塞项全部收敛**（尤其回调签名公式）。

---

## 13. 开放问题 / 待 GemPay 确认（上 live 前收敛）

1. ✅ **收款回调 `signature` 验签公式**（已 2026-07-15 sandbox 实测反推确认 = `/direct` 同式，见 §6.3；已锁 L0 向量并部署）。
2. **Sandbox 凭证**：`merchant_id` / `project_no` / `merchant_secret`（整条资金脊柱 lead time，建议即刻并行催）。
3. **回调重试与超时**：GemPay 回调失败重试策略？收款有无 pending/expired 回调，还是只能靠 `/history` 轮询判过期？（影响意图 EXPIRED 兜底）。
4. **`admin_fee` 承担方**：`total_amount = amount − admin_fee`（商户实收）。PawCoin 充值按 `amount`（用户支付额）发币还是按 `total_amount`？admin_fee 计入平台成本/浮存 → 关联 Story 9-2 定价与浮存门槛。
5. **V1 开放渠道集**：仅 QRIS(`MBayar_QR`)？是否放 DANA/VA？（决定 §5.1 映射范围与最低额校验）。
6. **回调失败应答语义**：验签失败回 403 还是 GemPay 约定的 `{"status":false,...}`（V1 建议 403，靠 GemPay 重试；4.x 再评估）。

---

## 决策记录

- **D-1（本设计采纳）**：GemPay 作为 `live` 收款/放款网关，Midtrans 实现保留可编译但不再被选中。引入 `petgo.pay.provider`（默认 `gempay`）作为 live 实现选择维度，`mode`（stub/live）继续承担 prod fail-closed 启动护栏。理由：Midtrans 从未拿到真实合约/凭证（Story 1.1 L2 一直阻塞），印尼落地实际网关为 GemPay；保留 Midtrans 代码零成本、不删以免动既有测试。
- **D-2（本设计采纳）**：不新增 Flyway 迁移。`payment_intents` 的 `gateway_ref`/`gateway_meta`/`public_token` 足以承载 GemPay 的 `ref_id`/快照/`request_id`，无需新列（决策 E2「已提交迁移冻结」下这是最省改动路径）。
