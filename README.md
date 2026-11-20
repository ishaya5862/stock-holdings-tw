# 專案名稱（待填）

## 環境
- Java 25
- Spring Boot 4.1.2
- 前端：Thymeleaf + Bootstrap 5
- 資料庫：（待填，目前為 H2 記憶體資料庫）

## 啟動方式
不需要另外安裝 Maven，使用專案內附的 Maven Wrapper：

```bash
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

啟動後開啟瀏覽器：http://localhost:8080

## 資料庫設定
（待填：資料庫種類、建表方式、帳號密碼要改哪裡）

## 題目 A
- 需求：
- 完成項目：

## 題目 B
- 需求：
- 完成項目：

## 專案結構
```
src/main/java/io/qiao/
├── controller/   接收網頁請求，決定回傳哪個畫面
├── service/      商業邏輯
├── repository/   存取資料庫（Spring Data JPA）
└── entity/       對應資料表的類別
src/main/resources/
├── templates/    Thymeleaf 頁面（.html）
├── static/       CSS、JS、圖片
└── application.properties
```
