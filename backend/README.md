# 直播自动切片工具 · 单机版后端（Spring Boot）

阶段一（M0）完整流程骨架 + 模拟执行：六环节管线（监控→录制→分析→成片→审核→发布）真实可跑，
API 契约与 `mock-server/server.mjs` 完全一致（snake_case JSON），前端零改动即可切换。

## 技术栈

| 项 | 值 |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| ORM | MyBatis-Plus 3.5.5（spring-boot3 starter） |
| 数据库 | H2 文件库 `./data/aiqiepian.mv.db`（AUTO_SERVER=TRUE）——**相对路径，工作目录决定用哪个库**，脚本启动时是 `backend/data/` |
| 端口 | 8090（`--server.port` 覆盖），前端已内嵌进 jar，UI+API 同一进程 |
| CORS | 仅允许 `http://localhost:5173`（只为 vite dev 保留） |

## 环境安装

见根目录 [`README.md`](../README.md) 的「换一台电脑要装什么」：JDK 17 + ffmpeg 是必须，Maven/Node 只在打包时需要，ASR / 斗鱼弹幕 / 本地大模型三样按需。工具链位置由根目录 `env.bat` 自动探测，不要在本机脚本里写死绝对路径。

## 启动

```bash
# 开发：只起后端（真实引擎请带 --engine.mode=real）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8090 --engine.mode=real"

# 换端口（系统属性优先级高于 application.yml）
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8081"

# 打包运行（注意：mvn package 不会重建前端，改过 .vue 请用根目录 build-package.bat）
mvn clean package
java -jar target/aiqiepian-backend-1.0.0.jar --server.port=8090 --engine.mode=real
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

- 两套实现并存，按启动参数条件装配：mock 为 `@ConditionalOnProperty(name = "engine.mode", havingValue = "mock", matchIfMissing = true)`，真实实现见 `engine/real/`（`RealStreamMonitor` / `RealRecorder` / `RealAnalyzer` / `RealRenderer` / `RealPublisher` …），`--engine.mode=real` 即整体切换，**controller / service / 前端零改动**。
- 平台支持范围以代码为准：可录平台判断在 `RealRecorder.supportsRealRecording()`（当前 B站 / 斗鱼 / 虎牙），开播检测在 `RealStreamMonitor`，弹幕通道在 `DanmakuCollector`（B站 Java TCP、斗鱼本机 Python 旁路、虎牙暂无实现）。
- mock 的进度推进与 4% 模拟失败仍内嵌在 `PipelineOrchestrator` 的 tick 里（对照 server.mjs）；真实引擎由引擎自行上报进度。

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
