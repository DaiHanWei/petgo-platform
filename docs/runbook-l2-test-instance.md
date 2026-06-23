# Runbook — L2 测试实例（方案 A，dev profile 旁路）

> 在 `62.146.239.156` 上与生产 `petgo-server` **并列**起一个 dev-profile 测试实例，复用现有 `DevGoogleTokenVerifier`+`DevUserSeeder`，接真实 live 凭证 + 独立测试库 + 独立隧道 hostname `api-test.tailtopia.id`。
> **零改 prod 鉴权**。详见 [`spec-l2-test-login-channel.md`](./spec-l2-test-login-channel.md)。
> ⚠️ 占位 `<...>` 自行替换；密码/密钥走 env，绝不入库。共享 redis **禁 FLUSHALL**。

---

## 端口/资源分配（避开已占用）

| 资源 | 生产 | 测试（本 runbook 新建） |
|---|---|---|
| 应用端口（本机） | `127.0.0.1:8084` | `127.0.0.1:8085` |
| Postgres | `petgo-postgres` :5432 | `petgo-postgres-test` :5433 |
| Redis 逻辑 DB | `2` | `3` |
| 隧道 hostname | `api.tailtopia.id` | `api-test.tailtopia.id` |
| profile | `prod` | `dev` |

---

## Step 1 — 测试库容器

```bash
ssh dai@62.146.239.156
TEST_DB_PW='<测试库强密码>'      # 与生产库不同

docker run -d --name petgo-postgres-test --network jbp-net \
  -p 127.0.0.1:5433:5432 \
  -v petgo_pgdata_test:/var/lib/postgresql/data \
  -e POSTGRES_DB=petgo -e POSTGRES_USER=petgo -e POSTGRES_PASSWORD="$TEST_DB_PW" \
  --restart unless-stopped postgres:16-alpine

sleep 5 && docker exec petgo-postgres-test pg_isready -U petgo -d petgo
```

## Step 2 — `~/.env.petgo-test`（基于生产 env 改 4 处）

```bash
# 用生产 env 打底，再覆盖关键项
cp ~/.env.petgo ~/.env.petgo-test
# 关键覆盖（dev profile + 独立库 + 独立 redis keyspace）
sed -i \
  -e 's/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=dev/' \
  -e 's/^DB_HOST=.*/DB_HOST=petgo-postgres-test/' \
  -e 's/^REDIS_DB=.*/REDIS_DB=3/' \
  ~/.env.petgo-test
# DB 密码改成 Step1 的（sed 单独处理，避免特殊字符）
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$TEST_DB_PW|" ~/.env.petgo-test
chmod 600 ~/.env.petgo-test

# 核对（不打印密码）：应看到 dev / petgo-postgres-test / 3
grep -E '^(SPRING_PROFILES_ACTIVE|DB_HOST|REDIS_DB)=' ~/.env.petgo-test
```

> Gemini/OSS/Google 凭证沿用生产同一套真实 live key（验真实第三方）。`GEMINI_MODE` 保持 `live`。

## Step 3 — 起测试应用容器（复用现有镜像）

```bash
docker run -d --name petgo-server-test --network jbp-net \
  -p 127.0.0.1:8085:8080 --restart unless-stopped \
  --env-file ~/.env.petgo-test \
  petgo-server:latest

# 等就绪 + 看 dev 桩日志
sleep 12
curl -fs http://127.0.0.1:8085/actuator/health && echo
docker logs petgo-server-test 2>&1 | grep -E 'DEV Google 校验桩|DEV seed' | tail -5
```
期望：health `UP`，日志含 `⚠️ DEV Google 校验桩已启用` + `DEV seed：已创建测试用户`。

## Step 4 — Cloudflare 隧道加 hostname

> Zero Trust → Networks → Tunnels → 现有 tunnel → Public Hostname → **Add a public hostname**
> - Subdomain：`api-test`　Domain：`tailtopia.id`　Path：留空
> - Type：`HTTP`　URL：`127.0.0.1:8085`
> Save。

## Step 5 — 验证（本机 + 公网）

```bash
# 服务器本机
curl -s http://127.0.0.1:8085/actuator/health

# 公网（任意机器；几十秒生效）
curl -s https://api-test.tailtopia.id/actuator/health    # 期望 UP

# 关键：dev 桩登录换真 JWT（任意假 idToken 都接受）
curl -s -X POST https://api-test.tailtopia.id/api/v1/auth/google \
  -H 'Content-Type: application/json' \
  -d '{"idToken":"dev-stub"}'
# 期望返回 LoginResponse（含 accessToken / 用户视图）
```

## Step 6 — 回归确认生产未被削弱（安全攸关）

```bash
# 生产入口对假 token 必须仍拒绝
curl -s -X POST https://api.tailtopia.id/api/v1/auth/google \
  -H 'Content-Type: application/json' -d '{"idToken":"dev-stub"}'
# 期望 401 / 校验失败 RFC9457（绝不能返回 LoginResponse）
```

---

## 打包 L2 测试 APK（实例就绪后）

```bash
cd petgo_app
flutter build apk --release \
  --dart-define=PETGO_MOCK=false \
  --dart-define=PETGO_API_BASE_URL=https://api-test.tailtopia.id \
  --dart-define=PETGO_DEV_STUB_LOGIN=true
adb install -r build/app/outputs/flutter-apk/app-release.apk
# 模拟器点 Google 登录 → 直接落 seed 测试账号 → 跑验收文档 §4 需登录态用例
```

---

## 销毁（测试完回收）

```bash
ssh dai@62.146.239.156 'docker rm -f petgo-server-test petgo-postgres-test; docker volume rm petgo_pgdata_test'
# Cloudflare 删除 api-test 的 Public Hostname
```

---

## 排障

| 症状 | 排查 |
|---|---|
| `petgo-server-test` 起不来 | `docker logs petgo-server-test`：dev profile 下 `ddl-auto` 是否 validate、测试库连得上吗、Flyway 是否在新库跑完 V1–V28 |
| 没有 dev 桩日志 | env 里 `SPRING_PROFILES_ACTIVE` 是否真是 `dev`；`docker exec petgo-server-test env \| grep PROFILE` |
| `api-test` 公网 502/523 | 容器在跑吗（`docker ps`）；隧道 URL 是否 `127.0.0.1:8085`（不是 8084） |
| 登录返回 401 而非 LoginResponse | 实例没跑 dev profile（在用真 Nimbus 校验）→ 查 Step 2 env |
| 怕污染生产数据 | 确认 `DB_HOST=petgo-postgres-test`、`REDIS_DB=3`；测试写入只该进测试库 |
