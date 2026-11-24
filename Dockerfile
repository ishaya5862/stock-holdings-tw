# 用 Docker 啟動整個專案（程式＋MySQL），電腦不用安裝 JDK：
#   docker compose --profile app up --build
# 說明見 README「用 Docker 啟動」。
#
# 多階段建置（multi-stage build）：
#   第 1 階段用 JDK 17 編譯、打包 jar；第 2 階段只放「執行」需要的 JRE 17 和 jar。
#   好處：最後的映像檔不含編譯工具與原始碼，比較小；而且固定用 Java 17 編譯（README 寫的最低版本）。

# ===== 第 1 階段：編譯 =====
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 先只複製 Maven 的設定檔：原始碼改了、pom.xml 沒改時，Docker 可以沿用這一層之前的結果
COPY mvnw pom.xml ./
COPY .mvn .mvn

# 再複製原始碼並打包（測試在本機用 mvnw test 跑，這裡略過，避免建置映像檔時需要 Docker 裡再開 Docker）
COPY src src
# --mount=type=cache：把下載過的 Maven 套件存在 Docker 的快取，第二次建置不用重新下載
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# ===== 第 2 階段：執行 =====
FROM eclipse-temurin:17-jre
WORKDIR /app

# 用一般使用者執行，不用 root：萬一程式被攻擊，能做的事比較少
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/target/stock-holdings-tw-*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
