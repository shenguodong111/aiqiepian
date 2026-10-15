# 直播自动切片工具（单机版）

[English README](./README.en.md)

> **使用手册（操作向）见 [`使用手册.md`](./使用手册.md)** —— 六个环节怎么用、总开关（管线运行中/已暂停）意味着什么、如何播放录制/片段/成片、支持平台、本地大模型、常见问题，都在那里。下面这段偏开发者。

参考 aiqiepian.cn 设计的直播内容自动化生产工具：监控直播间 → 开播自动录制（ffmpeg）→ 音频/画面/弹幕分析出精彩片段 → 自动成片（剪切+封面标题）→ 规则审核（自动通过/人工/拒绝）→ 上传发布到 Bilibili。

## 目录结构
```
backend/       Spring Boot 3.2.5 + MyBatis-Plus + H2（真实引擎 + mock 引擎双实现）
frontend/      Vue3 + Vite 本地控制台（10 页面，暗色主题）
mock-server/   Node 单文件模拟后端（无 JDK 时的完整演示，同 API 契约）
media/         素材目录（录制/片段/字幕/封面/成片）——工作目录是 backend/，实际落在 backend/media/
```

## 一、真实管线（推荐，本机已验证）

### 怎么跑起来（日常使用，不用命令行）
1. **打包**：双击 `build-package.bat` —— 它做三件事：`npm run build` 构建前端 → 把 `frontend/dist` 拷进 `backend/src/main/resources/static`（前端嵌进 jar，单进程）→ `mvn clean package` 出 jar。
   **改过任何 .vue/.js 都必须重新跑它**：只跑 `mvn package` 不会重建前端，界面会静默停留在上一次的版本。
2. **启动**：双击 `launch-app.bat`（桌面的「AI 切片」图标就是它）—— 自动拉起本机 Ollama、以 real 模式启动后端（8090）、再用 Chrome/Edge 的应用窗口打开控制台。已经在跑时不会重复起第二个后端，只会把窗口再打开一次。
3. **停止**：`stop.bat`（优雅退出，会收尾正在进行的录制）。

工具链位置全部由 `env.bat` 自动探测（JDK / Maven / ffmpeg / Ollama 各按候选目录找第一个真实存在的），换机器不用改脚本；确实找不到时，在系统里先设 `AIQIPIAN_JDK` / `AIQIPIAN_MVN` / `AIQIPIAN_FFMPEG` 三个环境变量指过去即可。

### 怎么跑起来（开发调试，前端热更新）
```bash
# 后端：先加载 env.bat，再进 backend/ 跑（工作目录必须是 backend/，见下方注意）
call env.bat
cd backend
call "%AIQIPIAN_MVN%\bin\mvn.cmd" spring-boot:run ^
  -Dspring-boot.run.arguments="--server.port=8090 --engine.mode=real"

# 前端：代理指向真实后端
cd frontend && npm install
VITE_API_TARGET=http://localhost:8090 npm run dev   # http://localhost:5173
```
前提：`ffmpeg` 可执行（`env.bat` 会把它加进 PATH，录制与成片都靠它）。
**注意**：数据库是相对路径 `./data/aiqiepian.mv.db`，工作目录不同会打开不同的库。用脚本启动时工作目录是 `backend/`，也就是 `backend/data/`；别在仓库根目录裸跑 java，那会另开一个空库、看起来像"设置和房间全丢了"。

### 使用步骤
1. 设置 → 发布 → **扫码登录**：弹出二维码，用手机 B 站 App 扫一下确认，Cookie 自动写回本机并绑定账号（不再需要去 F12 手工复制三件套；程序每 30 分钟自动体检并尝试续期）。再填投稿分区 tid → 保存
2. 发布中心 → "校验 Cookie"（显示你的 B 站昵称即有效）
3. 直播间页 → 添加直播间（粘贴直播间链接，开关监控）
4. 保持"自动运行"开启：开播自动录制（上限默认 240 分钟，可在设置调整）→ 下播/手动停止后自动分析出片段 → 高分片段自动成片 → 按阈值自动/人工审核 → 通过后自动上传 B 站并回读稿件状态
5. 智能标题（自动生成，可改名）：比赛切片=直播标题解析对阵，成题为「赛事名【战队1】VS【战队2】决胜时刻」；主播日常切片=「主播精简名+高频弹幕汇总一句话」（如"小雅高能瞬间，弹幕齐刷「666」「好强」"）；剪辑工作台卡片"编辑"→"AI 生成标题"重新生成、直接改名后点"保存并快速发布"即快速上传；片段分析页可见每片的弹幕数/密度/峰值/人数/高频弹幕

### 真实引擎能力边界（以代码为准，已实测）
| 环节 | B 站 | 斗鱼 | 虎牙 | 抖音等其它 |
|---|---|---|---|---|
| 开播检测 | ✅ `get_info` | ✅ 官方开放接口，匿名可用 | ✅ 直播页 flv 探测 | 一律按未开播处理 |
| 真实录制 | ✅ 单进程连续 | ✅ 重连拼接 | ⚠️ 可录，平台约 2 秒切一次匿名连接，有秒级空隙 | ❌ |
| 弹幕采集 | ✅ 需完整浏览器 Cookie（过 -352 风控） | ✅ 本机 Python 旁路（`scripts/douyu-danmu.py`） | ❌ 无实现 | ❌ |
| 语音字幕 ASR | ✅ | ✅ | ✅ | ✅（对已录文件） |
| 上传发布 | ✅ 唯一支持的发布目标 | ❌ | ❌ | ❌ |

- 监控/录制/分析/成片：本机端到端验证通过（B 站官方直播间实录 150s → 3 片段 → 成片 30.000s 精确）
- 上传：协议链路已验证，成功路径取决于登录态是否有效（扫码登录后即有效）
- 弹幕增强分析：无弹幕时自动降级为"语音解说密集度 + 画面场景切换"判定，不影响出片
- 录制取流的平台判定在 `RealRecorder.supportsRealRecording()`，检测在 `RealStreamMonitor`，改支持范围以这两处为准

## 二、模拟演示（无 JDK 也能跑）
```bash
node mock-server/server.mjs            # 8081（8080 被占时）
cd frontend && VITE_API_TARGET=http://localhost:8081 npm run dev
```
六环节全模拟自动流转（加速节奏），用于演示与前端联调。

## 三、换一台电脑要装什么

按"必须 → 建议 → 可选"排，装完直接双击 `build-package.bat` 再 `launch-app.bat` 就能用。

### 必须（少一个跑不起来）
| 装什么 | 版本 | 用来干什么 | 装法 |
|---|---|---|---|
| JDK | 17 | 跑后端 | 任意发行版（Temurin / Microsoft），装完 `env.bat` 会自己在 `D:\code\tools\jdk*`、`C:\Program Files\Microsoft\jdk*`、`C:\Program Files\Java\jdk*` 里找 |
| ffmpeg | 6+ | 录制、剪切、烧字幕、成片 | 解压到 `D:\code\tools\ffmpeg\bin`（或任何位置，`env.bat` 里改一行）；`env.bat` 会把它加进 PATH，**Java 侧是直接按名字调 `ffmpeg`，不进 PATH 就录不了** |
| Maven | 3.8+ | 只有打包/改代码才需要 | 解压即用；`build-package.bat` 从 `env.bat` 探测到的目录调 `mvn.cmd` |
| Node.js | 18+ | 只有打包/改前端才需要 | 装完在 `frontend/` 跑一次 `npm install` |

### 建议（不装也能跑，但功能会降级）
| 装什么 | 少了会怎样 | 装法 |
|---|---|---|
| **faster-whisper**（Python 3.12 + venv） | 成片没有烧录字幕 | `python -m venv D:\code\tools\asr-venv` → `D:\code\tools\asr-venv\Scripts\python -m pip install faster-whisper -i https://mirrors.aliyun.com/pypi/simple/`；模型 `Systran/faster-whisper-small` 首次运行会下载，之后离线缓存在 `%USERPROFILE%\.cache\huggingface`（CPU + int8 就够，不用装 CUDA/cuDNN）。设置 → 语音字幕 里把 Python 路径指到 venv 那个 |
| **websocket-client**（可复用上面同一个 venv） | 斗鱼房间采不到弹幕，高光评分退回"语音 + 画面" | `D:\code\tools\asr-venv\Scripts\python -m pip install websocket-client -i https://mirrors.aliyun.com/pypi/simple/`；程序会先试设置里的 `danmu_python`，留空则复用 ASR 的 Python，再留空才试 PATH 上的 `python`/`py` |
| **Ollama + qwen2.5:3b** | 标题/简介不做 AI 精修（`ai_mode` 退回规则生成） | 装完 `ollama pull qwen2.5:3b`；模型库位置随意，`env.bat` 里 `AIQIPIAN_OLLAMA_MODELS` 指过去。`launch-app.bat` 会自动把它拉起来，不用手动开 |

### 还要搬走的本机数据（都不进 git）
- **B 站登录**：新电脑上重新扫一次码即可（Cookie 是登录凭证，**永远不会进配置导出文件**）。
- **房间与设置**：旧电脑 设置 → 导出配置 JSON → 新电脑 导入。导出里含房间、参数设置，可选含历史；不含 Cookie。
- **素材**：`backend/media/`（录制/片段/成片/封面）和 `backend/data/`（H2 库）是各自本机文件，要留的直接拷目录。注意工作目录决定用哪个库——正常用脚本启动就是 `backend/data/`。

### 网络不通时的替代装法（本机实测）
- `github.com` 的 HTTPS 被重置 → 用 SSH  remote（`git@github.com:...`）。
- Maven 中央仓可达，首次打包会自动下依赖，无需 `settings.xml`。
- pip 走阿里云镜像（见上表命令）。
- HuggingFace 拉不动 → 在旧电脑把 `%USERPROFILE%\.cache\huggingface` 整个拷过来，并设 `HF_HUB_OFFLINE=1`。

## 四、开发约定
- API 契约以 `直播自动切片工具-单机版详细需求文档.md` §6 + `mock-server/server.mjs` 为准，三方（mock/Spring/前端）改动需同步
- mock/real 通过启动参数 `--engine.mode` 切换，业务代码与前端零改动
- 页面切换动画为纯 CSS（勿改回 Vue Transition，隐藏标签页下 rAF 冻结会卡死路由）
- 数据库变更只写幂等 DDL（`CREATE TABLE IF NOT EXISTS` / `ALTER TABLE … ADD COLUMN IF NOT EXISTS`），不许 DROP——老库要能原地升级，她的录制记录不能没

## 已知限制
- 进程崩溃时处于 UPLOADING 的任务重启后会重新上传（可能产生重复稿件，待真 Cookie 验证后加幂等）
- 发布目标仅 B 站；斗鱼/虎牙只做"监控 + 录制 + 分析 + 成片"，要发到别的平台需在 `Publisher` 接口下加实现
- 虎牙取流平台每约 2 秒切一次匿名连接，录制为重连拼接，存在秒级空隙；虎牙不采弹幕
- 抖音等其它平台真实模式一律按未开播处理
- 分析/渲染已改为独立线程池并发（`max_concurrent_heavy` / `analyze` / `render`），但单机 CPU 有限，并发开高会拖慢字幕识别

