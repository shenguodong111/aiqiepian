# 直播自动切片工具（单机版）

[English README](./README.en.md)

> **使用手册（操作向）见 [`使用手册.md`](./使用手册.md)** —— 六个环节怎么用、总开关（管线运行中/已暂停）意味着什么、如何播放录制/片段/成片、支持平台、本地大模型、常见问题，都在那里。下面这段偏开发者。

参考 aiqiepian.cn 设计的直播内容自动化生产工具：监控直播间 → 开播自动录制（ffmpeg）→ 音频/画面/弹幕分析出精彩片段 → 自动成片（剪切+标题条+封面）→ 规则审核（自动通过/人工/拒绝）→ 上传发布到 Bilibili。

## 目录结构
```
backend/       Spring Boot 3.2.5 + MyBatis-Plus + H2（真实引擎 + mock 引擎双实现）
frontend/      Vue3 + Vite 本地控制台（9 页面，暗色主题）
mock-server/   Node 单文件模拟后端（无 JDK 时的完整演示，同 API 契约）
media/         素材目录（录制/片段/字幕/封面/成片）
```

## 一、真实管线（推荐，本机已验证）
```bash
# 后端（需 JDK17 + Maven；本机已装于
#   C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
#   C:\Users\25436\tools\maven-home\apache-maven-3.9.16）
cd backend
export JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
"C:/Users/25436/tools/maven-home/apache-maven-3.9.16/bin/mvn.cmd" \
  -s "C:/Users/25436/tools/maven-home/settings.xml" \
  spring-boot:run -Dspring-boot.run.arguments="--server.port=8090 --engine.mode=real"

# 前端（代理指向真实后端）
cd frontend
VITE_API_TARGET=http://localhost:8090 npm run dev   # http://localhost:5173
```
前提：`ffmpeg` 在 PATH（本机 9.0 已装）。

### 使用步骤
1. 设置页 → 填入 B 站 Cookie 三件套（SESSDATA / bili_jct / DedeUserID，浏览器 F12 复制）与投稿分区 tid → 保存
2. 发布中心 → "校验 Cookie"（显示你的 B 站昵称即有效）
3. 直播间页 → 添加直播间（粘贴 https://live.bilibili.com/xxx 链接，开关监控）
4. 保持"自动运行"开启：开播自动录制（上限默认 240 分钟，可在设置调整）→ 下播/手动停止后自动分析出片段 → 高分片段自动成片 → 按阈值自动/人工审核 → 通过后自动上传 B 站并回读稿件状态
5. 智能标题（自动生成，可改名）：比赛切片=直播标题解析对阵，成题为「赛事名【战队1】VS【战队2】决胜时刻」；主播日常切片=「主播精简名+高频弹幕汇总一句话」（如"小雅高能瞬间，弹幕齐刷「666」「好强」"）；剪辑工作台卡片"编辑"→"AI 生成标题"重新生成、直接改名后点"保存并快速发布"即快速上传；片段分析页可见每片的弹幕数/密度/峰值/人数/高频弹幕

### 真实引擎能力边界（已实测）
- 监控/录制/分析/成片：本机端到端验证通过（B 站官方直播间实录 150s → 3 片段 → 成片 30.000s 精确）
- 上传：协议链路已验证（B 站返回真实 -101 判定），成功路径取决于你填入的有效 Cookie
- 弹幕增强分析：需登录 Cookie（匿名被 B 站 -352 风控），无 Cookie 自动降级为音频+画面分析，不影响出片
- 开播检测：B站 + 斗鱼（官方开放接口，匿名可用）；虎牙/抖音暂未支持，检测一律报未开播
- 真实录制取流：当前仅 B站；斗鱼取流需签名算法（未接入），斗鱼房间只做"开播检测"不会自动录制

## 二、模拟演示（无 JDK 也能跑）
```bash
node mock-server/server.mjs            # 8081（8080 被占时）
cd frontend && VITE_API_TARGET=http://localhost:8081 npm run dev
```
六环节全模拟自动流转（加速节奏），用于演示与前端联调。

## 三、开发约定
- API 契约以 `直播自动切片工具-单机版详细需求文档.md` §6 + `mock-server/server.mjs` 为准，三方（mock/Spring/前端）改动需同步
- mock/real 通过启动参数 `--engine.mode` 切换，业务代码与前端零改动
- 页面切换动画为纯 CSS（勿改回 Vue Transition，隐藏标签页下 rAF 冻结会卡死路由）

## 已知限制
- 分析/渲染在调度线程内同步执行，超长录像会拖慢其他任务推进（后续可加线程池）
- 进程崩溃时处于 UPLOADING 的任务重启后会重新上传（可能产生重复稿件，待真 Cookie 验证后加幂等）
- 仅支持 B 站发布（对齐参考站现状）；抖音/斗鱼录制可扩展（StreamMonitor/Recorder 接口已抽象）
