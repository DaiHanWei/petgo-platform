# petgo-backend

PetGo 后端 —— Spring Boot 4.0.6 / Java 25 / Maven，PostgreSQL + Redis + Flyway，模块化单体（8 业务模块 + shared）。

## 前置

- JDK **25**（本仓库用 jenv 钉定，根目录 `.java-version=25`）
- Docker daemon（起 PostgreSQL + Redis）
- Maven 3.9+（或用自带 `./mvnw`）

> 依赖解析走项目级 `.mvn/settings.xml`（直连 Maven Central，自动经 `.mvn/maven.config` 生效），不依赖任何私有 Nexus。

## 本地起步

```bash
cd petgo-backend

# 1) 起 PostgreSQL + Redis（德国单机配置占位）
docker compose up -d postgres redis

# 2) 跑后端（dev profile）
./mvnw spring-boot:run        # 或 mvn spring-boot:run

# 3) 验证
curl localhost:8080/actuator/health      # {"status":"UP"} 含 db/redis
curl localhost:8080/v3/api-docs          # OpenAPI 3.1 JSON
open  http://localhost:8080/swagger-ui.html
```

整栈（含 app 容器）：`docker compose up --build`。

## 测试

```bash
./mvnw test       # contextLoads(L1，需 DB) + ProblemDetail 信封(L0)
./mvnw package    # 出可执行 jar：target/petgo-backend-*.jar
```

## 约定

命名链 / 分层 / 错误规范（RFC 9457 ProblemDetail）/ 强制护栏见仓库根 `CLAUDE.md` 与
`_bmad-output/planning-artifacts/architecture.md`。凭证全部走 `.env`（见 `.env.example`），**绝不入库**。
