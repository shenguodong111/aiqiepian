package com.aiqiepian.engine.real;

import com.aiqiepian.bilibili.BiliApiException;
import com.aiqiepian.bilibili.BiliClient;
import com.aiqiepian.bilibili.NavInfo;
import com.aiqiepian.bilibili.UploadOutcome;
import com.aiqiepian.engine.Publisher;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.engine.model.PublishResult;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * M3 真实发布引擎（engine.mode=real）。
 *
 * <p>流程：Cookie 三件套前置检查 → nav() 校验 → 成片 10MB 分片上传（进度回调写 PublishTask.progress）
 * → /x/web/archives 稿件提交 → 存 remote_id(bvid)、状态 PLATFORM_REVIEW；
 * PLATFORM_REVIEW→PUBLISHED 的转码回读由 PublishServiceImpl 的 real tick 驱动。
 *
 * <p>失败：任何 B 站非 0 返回都原样写入 error_msg（code+message），不许吞错。
 */
@Component
@Primary // real 模式下 RealEngineConfig 仍注册 mock 占位 Publisher，@Primary 确保注入本实现
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RealPublisher implements Publisher {

    private static final Logger log = LoggerFactory.getLogger(RealPublisher.class);

    private final BiliClient bili;
    private final PublishTaskMapper taskMapper;
    private final WorkMapper workMapper;
    private final com.aiqiepian.service.SettingsService settings;

    /** 正在执行上传/提交的任务（防止编排器重入导致二次上传） */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    /** taskId → 真实 bvid：编排器成功分支会盖写 mock remote_id，tick 用本表修复 */
    private final Map<Long, String> submittedBvids = new ConcurrentHashMap<>();

    public RealPublisher(BiliClient bili, PublishTaskMapper taskMapper,
                         WorkMapper workMapper, com.aiqiepian.service.SettingsService settings) {
        this.bili = bili;
        this.taskMapper = taskMapper;
        this.workMapper = workMapper;
        this.settings = settings;
    }

    public Map<Long, String> submittedBvids() {
        return submittedBvids;
    }

    public boolean isRunning(long taskId) {
        return running.contains(taskId);
    }

    /**
     * Publisher 接口实现：PipelineOrchestrator 在 PLATFORM_REVIEW 阶段调用。
     * real tick 正常情况下会把任务钉在 UPLOADING/PLATFORM_REVIEW（phase_sec 心跳归零），
     * 本方法仅作兜底：已完成/进行中直接返回，否则同步执行一次完整发布。
     */
    @Override
    public PublishResult publish(Work work, PublishAccount account) {
        if (work == null) return new PublishResult(false, "作品不存在，无法真实发布");
        List<PublishTask> active = taskMapper.selectList(Wrappers.<PublishTask>lambdaQuery()
                .eq(PublishTask::getWorkId, work.getId())
                .in(PublishTask::getStatus, "QUEUED", "UPLOADING", "PLATFORM_REVIEW")
                .orderByDesc(PublishTask::getId));
        if (active.isEmpty()) return new PublishResult(false, "无进行中的发布任务");
        PublishTask task = active.get(0);
        if (running.contains(task.getId())) return new PublishResult(true, null);
        String bvid = task.getRemoteId();
        if (bvid != null && bvid.startsWith("BV") && !bvid.startsWith("BV1mock")) {
            return new PublishResult(true, null); // 已提交稿件，等待回读
        }
        try {
            execute(task.getId(), p -> {
            });
            return new PublishResult(true, null);
        } catch (BiliApiException e) {
            return new PublishResult(false, e.toErrorMsg());
        } catch (Exception e) {
            return new PublishResult(false, "真实发布异常: " + e.getMessage());
        }
    }

    /**
     * 完整真实发布流程（由 PublishServiceImpl 的异步 worker 调用）。
     * 成功：置 remote_id=bvid、status=PLATFORM_REVIEW，返回 bvid。
     * 失败：抛异常（调用方负责落 FAILED+error_msg）；本方法内也做进度/状态的过程写库。
     */
    public String execute(long taskId, IntConsumer progressSink) {
        if (!running.add(taskId)) {
            log.info("任务 {} 已在发布中，跳过重入", taskId);
            return null;
        }
        try {
            PublishTask task = taskMapper.selectById(taskId);
            if (task == null) throw new BiliApiException(-10, "发布任务不存在: " + taskId);
            Work work = workMapper.selectById(task.getWorkId());
            if (work == null) throw new BiliApiException(-10, "作品不存在: " + task.getWorkId());

            // ---- 前置检查 1：Cookie 三件套 ----
            if (!bili.cookiesComplete()) {
                markCookieStatus("MISSING", "未配置 B 站 Cookie，请在设置页扫码登录");
                throw new BiliApiException(-101,
                        "未配置 B 站 Cookie：请在设置页点「扫码登录」（当前缺失 bili_sessdata / bili_jct / bili_uid）");
            }
            // ---- 前置检查 2：nav() 登录校验 ----
            NavInfo nav = bili.nav();
            if (!nav.login()) {
                markCookieStatus("EXPIRED", "B站判定未登录（" + nav.code() + "）：" + nav.message());
                throw new BiliApiException(nav.code(),
                        "Cookie 无效或已过期（B站判定未登录）: " + nav.message() + " —— 请到设置页扫码重新登录");
            }
            long mid = nav.mid() != null ? nav.mid()
                    : parseLongOrThrow(settings.getStr("bili_uid", ""));

            // ---- 成片文件 ----
            String path = work.getOutputPath();
            File file = path == null ? null : new File(path.replace('/', java.io.File.separatorChar));
            if (file == null || !file.isFile()) {
                throw new BiliApiException(-11, "成片文件不存在，无法上传: " + path);
            }

            markUploading(taskId);
            // ---- 分片上传（进度 0→90）----
            UploadOutcome upload = bili.uploadVideo(file, file.getName(), mid, pct -> {
                int p = Math.min(90, pct * 90 / 100);
                progressSink.accept(p);
                updateProgress(taskId, p);
            });

            // ---- 稿件提交 ----
            progressSink.accept(95);
            updateProgress(taskId, 95);
            String desc = renderDesc(work);
            String cover = resolveCoverUrl(work);
            String bvid = bili.submitArchive(work.getTitle(), work.getTags(), desc,
                    cover == null || cover.isBlank() ? null : cover, upload, mid);

            submittedBvids.put(taskId, bvid);
            // ---- 存 remote_id，状态 PLATFORM_REVIEW ----
            taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                    .set(PublishTask::getRemoteId, bvid)
                    .set(PublishTask::getStatus, "PLATFORM_REVIEW")
                    .set(PublishTask::getProgress, 100)
                    .set(PublishTask::getPhaseSec, 0)
                    .set(PublishTask::getErrorMsg, null)
                    .eq(PublishTask::getId, taskId));
            progressSink.accept(100);
            log.info("任务 {} 稿件提交成功 bvid={}（uploadUri={}）", taskId, bvid, upload.uri());
            return bvid;
        } finally {
            running.remove(taskId);
        }
    }

    // ---------- 内部 ----------

    /**
     * 稿件封面：优先上传本作品已生成的封面（素材图或截帧 + 标题文字），
     * 无文件/上传失败则退回全局 publish_cover_url（仍为空就由 B站 自行取帧）。
     */
    private String resolveCoverUrl(Work work) {
        String path = work.getCoverPath();
        if (path != null && !path.isBlank()) {
            File f = new File(path.replace('/', java.io.File.separatorChar));
            if (f.isFile() && f.length() > 0) {
                try {
                    return bili.uploadCover(f);
                } catch (Exception e) {
                    log.warn("作品 {} 封面上传失败，改用全局封面或平台自动取帧: {}", work.getId(), e.toString());
                }
            } else {
                log.warn("作品 {} 封面文件不存在: {}（改用全局封面或平台自动取帧）", work.getId(), path);
            }
        }
        return settings.getStr("publish_cover_url", "");
    }

    private void markUploading(long taskId) {
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getStatus, "UPLOADING")
                .set(PublishTask::getPhaseSec, 0)
                .set(PublishTask::getProgress, 1)
                .set(PublishTask::getErrorMsg, null)
                .eq(PublishTask::getId, taskId));
    }

    /**
     * 发布现场发现的登录态问题立刻写回设置，界面（设置页徽标 / 发布中心红条）当场就能看到，
     * 不用等下一次定时体检——用户遇到的"发布失败但不知道是 Cookie 掉了"就是这一段。
     */
    private void markCookieStatus(String status, String message) {
        settings.setValue("bili_cookie_status", status);
        settings.setValue("bili_cookie_checked_at", java.time.LocalDateTime.now().toString());
        settings.setValue("bili_cookie_message", message);
    }

    private void updateProgress(long taskId, int progress) {
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getProgress, progress)
                .eq(PublishTask::getId, taskId));
    }

    private String renderDesc(Work work) {
        String tpl = settings.getStr("publish_desc_template",
                "{房间} {日期} 的精彩瞬间，由 AI 切片自动生成。");
        return tpl.replace("{房间}", work.getRoomName() == null ? "" : work.getRoomName())
                  .replace("{日期}", LocalDate.now().toString());
    }

    private static long parseLongOrThrow(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            throw new BiliApiException(-12, "bili_uid 配置非法（非数字）: " + s);
        }
    }
}
