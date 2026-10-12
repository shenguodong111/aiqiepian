# 直播自动切片工具 · 单机版后端（Spring Boot）

阶段一（M0）完整流程骨架 + 模拟执行：六环节管线（监控→录制→分析→成片→审核→发布）真实可跑，
API 契约与 `mock-server/server.mjs` 完全一致（snake_case JSON），前端零改动即可切换。

## 技术栈

| 项 | 值 |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| ORM | MyBatis-Plus 3.5.5（spring-boot3 starter） |
| 数据库 | H2 文件库 `./data/aiqiepian.mv.db`（AUTO_SERVER=TRUE） |
| 端口 | 8080（可 `-Dserver.port` 覆盖） |
| CORS | 仅允许 `http://localhost:5173` |

## 环境安装

1. **JDK 17**：下载 Temurin 17（https://adoptium.net），安装后 `java -version` 应输出 17.x。
2. **Maven 3.8+**：https://maven.apache.org/download.cgi，`mvn -v` 可正常输出且识别到 JDK 17。

## 启动

```bash
cd backend
mvn spring-boot:run                       # http://localhost:8080/api/**

# 换端口（系统属性优先级高于 application.yml）
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8081"

# 打包运行
mvn clean package
java -jar target/aiqiepian-backend-1.0.0.jar
```

首次启动自动执行 `schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，幂等），并向 `app_setting`
播种与 mock-server 相同的全套默认设置。数据文件在 `./data/`，删除该目录即全新状态。

## 配合前端

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173（vite 代理 /api → 8080）
```

端到端演示链路（加速值与 mock 相同：录制一场约 30–90s）：
新增直播间 → 开启监控 → 自动出现 RECORDING 会话 → 会话结束自动产出 3–8 个片段 →
评分 ≥ `auto_work_min_score` 自动成片 → 按阈值 AUTO_PASS / MANUAL / AUTO_REJECT 分流 →
绑定 B 站账号并放行后自动发布 → 发布记录可见。

## 引擎模式：mock ↔ 真实

- 当前所有引擎为 `engine/mock/` 下的 Mock 实现，条件装配：
  `@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)`。
- 切换真实引擎（M1–M3）：
  1. 实现 `StreamMonitor / Recorder / Analyzer / Renderer / Reviewer / Publisher`（接口见需求文档 §7）；
  2. 标注 `@ConditionalOnProperty(name = "engine.mode", havingValue = "real")` 并注册为 Bean；
  3. `application.yml` 改 `engine.mode: real`（或 `-Dengine.mode=real`）。
  **controller / service / 前端零改动。**
- 注意：job 进度推进与 4% 模拟失败目前内嵌在 `PipelineOrchestrator` 的 mock 节奏中
  （对照 server.mjs tick）；真实引擎接入时由引擎自行上报进度。

## 与 mock-server 的差异说明

| 差异 | 原因 |
|---|---|
| `POST /media/cleanup`、`POST /jobs/{id}/retry` 本后端已实现 | mock-server 未实现这两个路由（会 404），此处按需求文档 §6 #13/#15 补齐 |
| 时间戳为本地 ISO-8601（无毫秒 Z 后缀），如 `2026-10-09T15:04:05.123` | LocalDateTime 序列化；前端 dayjs/Date 解析无影响 |
| `publish_account` 存于 `app_setting` 表而非内存 | 单机持久化要求；不出现在 `GET /settings` 响应中，与 mock 行为一致 |
| CORS 仅允许 `http://localhost:5173`（mock 为 `*`） | 需求文档 §2 明确要求 |
| `disk_free_gb` 取实际磁盘余量（mock 固定 256） | 文档 FR-P1-05 要求真实值 |

## 目录结构

```
src/main/java/com/aiqiepian/
├── AiqiepianApplication.java     @EnableScheduling + @MapperScan
├── common/                       ApiResponse / PageResult / BizException / 全局异常 / body 取值工具
├── config/                       CorsConfig / MybatisPlusConfig
├── entity/                       LiveRoom RecordingSession Clip Work ReviewRecord PublishTask PipelineJob AppSetting
├── mapper/                       每实体一个 BaseMapper
├── controller/                   Dashboard Room Recording Clip Work Review Publish Media Settings Job（§6 的 15 组接口）
├── service/ + service/impl/      业务 + PipelineService（管线核心状态迁移，controller 与编排器共用）
├── engine/                       §7 六接口 + engine/mock Mock 实现 + engine/model 输入输出类型
└── pipeline/                     PipelineOrchestrator（@Scheduled fixedRate=3000 的 tick 状态机）
```
