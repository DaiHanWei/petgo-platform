# 后端待部署变更记录（DEPLOY NOTES）

> 记录每次会改变**生产行为**的后端变更，以及部署时需要的额外动作（新 env / 新 Flyway / profile 等）。
> 部署流程本身见 `docs/deployment-guide-backend.md`（`./scripts/deploy-backend.sh`）。新条目放最上面。

---

## 2026-06-30 — Apple 登录后端链路（FR-44）+ 登录响应返回真实 hasPetProfile

### 加了什么
1. **Apple 登录 `POST /api/v1/auth/apple`**（与 Google 同构）：校验 Apple identity token → 按 `apple_sub` 取号/首登建号 → 签发自签 JWT。
   - 新增：`AppleTokenVerifier` / `NimbusAppleTokenVerifier`（Apple JWKS 验签 + iss/aud/exp）/ `DevAppleTokenVerifier`（仅 dev）/ `AppleIdentity` / `AppleLoginRequest`；`AuthService.loginWithApple`；`UserRepository.findByAppleSub`。
2. **`users` 表加 `apple_sub` 列**（Flyway `V31__add_apple_auth.sql`）：`google_sub` 放宽为可空，新增 `apple_sub VARCHAR(255)` + 唯一约束。
3. **登录响应返回真实 `hasPetProfile`**：`AuthService.loginWithGoogle/loginWithApple` 不再硬编码 `false`，老用户按 `pet_profiles` 实查（与 `/me` 同源）。修复「老用户登录后 /me 仍催建档、首页先建档提示条/发布门控误显」。

### 部署时必须做的额外动作
- [ ] **新增 env**：在服务器 `~/.env.petgo` 加一行（Apple 真机登录的 aud 校验；留空则跳过 aud 严格校验，仍可验签）：
      ```
      # Apple Sign-In client id（通常 = iOS bundle id com.tailtopia.app）
      APPLE_CLIENT_ID=
      ```
- [ ] **Flyway 自动执行**：`V31` 在容器启动 `/actuator/health` 前随迁移全量执行，无需手动跑 SQL。回滚时注意 V31 已改表。
- [ ] **profile 确认**：`SPRING_PROFILES_ACTIVE=prod`（已是默认）才走真实 `NimbusAppleTokenVerifier`；dev 桩 `DevApple/GoogleTokenVerifier` 在 prod 下不注册。

### 验证（部署后）
- `curl -s http://127.0.0.1:8084/actuator/health` = UP（含 V31 迁移成功）。
- 老用户登录响应 `profile.hasPetProfile` 反映真实档案（前端首页提示条/发布门控/`/me` 一致）。
- Apple 真机登录属 L2：需 iOS 端「Sign in with Apple」能力 + 真实 `APPLE_CLIENT_ID`，本地真机验收。

### 关联
- L0 全绿：`AuthServiceTest` 12/12、`AuthControllerEndpointTest`（L1，含 apple 用例，本地 Docker 跑）。
- 前端 Apple 链路 + iOS entitlements 见前端改动；前端 `/me` 已用真实档案兜底（不依赖本次后端部署即生效）。
