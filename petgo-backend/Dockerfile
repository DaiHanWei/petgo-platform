# 多阶段构建：Maven(JDK 21) 构建 → temurin:21-jre 运行。
# 构建阶段复制 .mvn/（含项目级 settings.xml → 直连 Maven Central），与本地构建一致。
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/petgo-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
