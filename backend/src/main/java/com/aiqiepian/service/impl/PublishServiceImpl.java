package com.aiqiepian.service.impl;

import com.aiqiepian.bilibili.BiliApiException;
import com.aiqiepian.bilibili.BiliClient;
import com.aiqiepian.bilibili.NavInfo;
import com.aiqiepian.common.BizException;
import com.aiqiepian.common.PageResult;
import com.aiqiepian.engine.model.PublishAccount;
import com.aiqiepian.engine.real.RealPublisher;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.PublishService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class PublishServiceImpl implements PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishServiceImpl.class);

    /** PLATFORM_REVIEW 回读转码完成的等待上限（毫秒） */
    private static final long REVIEW_TIMEOUT_MS = 15 * 60 * 1000L;
    /** view 回读轮询间隔（毫秒） */
    private static final long VIEW_POLL_MS = 10 * 1000L;

    private final PublishTaskMapper taskMapper;
    private final WorkMapper workMapper;
    private final SettingsService settings;
    private final ObjectProvider<RealPublisher> realPublisher;
    private final ObjectProvider<BiliClient> biliClient;

    @Value("${engine.mode:mock}")
    private String engineMode;

    /** real 模式：上传 worker（与 @Scheduled 单线程调度器隔离，不阻塞 mock tick） */
    private final ExecutorService realExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "real-publish");
        t.setDaemon(true);
        return t;
    });
    /** taskId → worker 最新进度（权威值，心跳写回 DB，抵消编排器 mock 进度） */
    private final Map<Long, Integer> realProgress = new ConcurrentHashMap<>();
    /** 本 tick 循环已接管（worker 在跑或待终态）的任务，防重复领取 */
    private final Set<Long> realEnqueued = ConcurrentHashMap.newKeySet();
    /** taskId → 进入真实 PLATFORM_REVIEW 回读的时间 */
    private final Map<Long, Long> reviewSince = new ConcurrentHashMap<>();
    /** 上次对某 task 调 view 回读的时间 */
    private final Map<Long, Long> lastViewPoll = new ConcurrentHashMap<>();
    /**
     * taskId → 已自动回队重传次数。只用于"上传阶段被网络抖动打断"（此刻远端还没有稿件，重传不会重复投稿）；
     * 超过 {@link #NETWORK_RETRY_MAX} 才落 FAILED，交人工。进程重启会清零，但重启本身也会让在传的稿件重新排队。
     */
    private final Map<Long, Integer> networkRetries = new ConcurrentHashMap<>();
    private static final int NETWORK_RETRY_MAX = 2;

    @Override
    public PublishAccount getAccount() {
        return settings.getJson(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT, PublishAccount.class);
    }

    @Override
    public PublishAccount bind(String nickname) {
        PublishAccount account = new PublishAccount();
        account.setPlatform("bilibili");
        account.setNickname(nickname == null || nickname.isEmpty() ? "AI切片演示号" : nickname);
        account.setBoundAt(LocalDateTime.now().toString());
        settings.setJson(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT, account);
        return account;
    }

    @Override
    public void unbind() {
        settings.remove(SettingsServiceImpl.KEY_PUBLISH_ACCOUNT);
    }

    /** M3 §5：real 模式下用设置中的 Cookie 调 nav() 校验（前端"校验Cookie"按钮） */
    @Override
    public Map<String, Object> checkAccount() {
        Map<String, Object> r = new LinkedHashMap<>();
        BiliClient bili = biliClient.getIfAvailable();
        if (!"real".equalsIgnoreCase(engineMode) || bili == null) {
            r.put("login", false);
            r.put("message", "当前 engine.mode 非 real（模拟引擎无需 Cookie 校验）");
            return r;
        }
        if (!bili.cookiesComplete()) {
            r.put("login", false);
            r.put("message", "未配置 bili_sessdata / bili_jct / bili_uid，请直接扫码登录");
            markCookieStatus("MISSING", "未配置 Cookie 三件套");
            return r;
        }
        try {
            NavInfo n = bili.nav();
            if (!n.login()) {
                // 校验不通过先试一次续期：B站网页端也在周期调同一个接口，能续上就不用用户重新登录
                if (!refreshCookie().get("ok").equals(true)) {
                    markCookieStatus("EXPIRED", "Cookie 无效：" + n.message());
                }
                n = bili.nav();
            }
            r.put("login", n.login());
            r.put("code", n.code());
            r.put("uname", n.uname());
            r.put("mid", n.mid());
            if (n.login()) {
                // 校验通过即持久化绑定：使 publish 的账号门(getAccount!=null)通过，且重启/刷新后不再丢绑定态
                r.put("account", bind(n.uname()));
                r.put("message", "Cookie 有效");
                markCookieStatus("VALID", "Cookie 有效：" + n.uname());
            } else {
                r.put("message", "未登录/Cookie 无效：" + n.message());
            }
        } catch (BiliApiException e) {
            r.put("login", false);
            r.put("code", e.getCode());
            r.put("message", e.getBiliMessage());
            markCookieStatus(e.getCode() == -101 ? "EXPIRED" : "UNKNOWN", "校验异常：" + e.getBiliMessage());
        }
        return r;
    }

    @Override
    public Map<String, Object> qrcodeStart() {
        Map<String, Object> r = new LinkedHashMap<>();
        BiliClient bili = biliClient.getIfAvailable();
        if (!"real".equalsIgnoreCase(engineMode) || bili == null) {
            r.put("ok", false);
            r.put("message", "当前引擎非 real（模拟引擎不需要 B 站登录态）");
            return r;
        }
        BiliClient.QrLogin q = bili.qrcodeGenerate();
        r.put("ok", true);
        r.put("qrcode_key", q.qrcodeKey());
        r.put("image", q.imagePng());
        r.put("url", q.url());
        r.put("expire_sec", 180);
        return r;
    }

    @Override
    public Map<String, Object> qrcodePoll(String qrcodeKey) {
        Map<String, Object> r = new LinkedHashMap<>();
        BiliClient bili = biliClient.getIfAvailable();
        if (!"real".equalsIgnoreCase(engineMode) || bili == null) {
            r.put("status", "unsupported");
            r.put("message", "当前引擎非 real");
            return r;
        }
        if (qrcodeKey == null || qrcodeKey.isBlank()) {
            r.put("status", "expired");
            r.put("message", "缺少 qrcode_key");
            return r;
        }
        BiliClient.QrPoll p = bili.qrcodePoll(qrcodeKey);
        r.put("status", p.status());
        r.put("message", p.message());
        if (!"confirmed".equals(p.status())) return r;

        bili.applyLoginCookies(p.cookies());
        try {
            NavInfo n = bili.nav();
            if (n.login()) {
                r.put("account", bind(n.uname()));
                r.put("nickname", n.uname());
                r.put("mid", n.mid());
                markCookieStatus("VALID", "扫码登录成功：" + n.uname());
                log.info("扫码登录成功，Cookie 已自动保存并绑定账号 uid={}（无需再手工填写三件套）", n.mid());
            } else {
                markCookieStatus("EXPIRED", "扫码后 nav 仍未登录：" + n.message());
                r.put("message", "扫码成功但账号校验未通过：" + n.message());
            }
        } catch (BiliApiException e) {
            markCookieStatus("UNKNOWN", "扫码后校验异常：" + e.getBiliMessage());
            r.put("message", "扫码成功但校验异常：" + e.getBiliMessage());
        }
        return r;
    }

    @Override
    public Map<String, Object> refreshCookie() {
        Map<String, Object> r = new LinkedHashMap<>();
        BiliClient bili = biliClient.getIfAvailable();
        if (!"real".equalsIgnoreCase(engineMode) || bili == null) {
            r.put("ok", false);
            r.put("message", "当前引擎非 real");
            return r;
        }
        if (!bili.cookiesComplete()) {
            markCookieStatus("MISSING", "未配置 Cookie 三件套");
            r.put("ok", false);
            r.put("message", "尚未配置 Cookie，请直接扫码登录");
            return r;
        }
        Map<String, String> ck = bili.refreshCookies();
        if (ck.isEmpty()) {
            markCookieStatus("EXPIRED", "续期未通过（会话已被 B 站作废）");
            r.put("ok", false);
            r.put("message", "续期失败：本次登录已被 B 站作废，请扫码重新登录");
            return r;
        }
        bili.applyLoginCookies(ck);
        r.put("ok", true);
        try {
            NavInfo n = bili.nav();
            if (n.login()) {
                bind(n.uname());
                markCookieStatus("VALID", "已续期：" + n.uname());
                r.put("message", "已续期，登录态有效");
                r.put("nickname", n.uname());
            } else {
                markCookieStatus("EXPIRED", "续期后仍未登录");
                r.put("message", "续期后账号校验仍未通过，请扫码重新登录");
            }
        } catch (BiliApiException e) {
            r.put("message", "续期已写入但校验异常：" + e.getBiliMessage());
        }
        return r;
    }

    @Override
    public Map<String, Object> cookieStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", settings.getStr("bili_cookie_status", "UNKNOWN"));
        r.put("checked_at", settings.getStr("bili_cookie_checked_at", ""));
        r.put("message", settings.getStr("bili_cookie_message", ""));
        PublishAccount acc = getAccount();
        r.put("nickname", acc == null ? null : acc.getNickname());
        BiliClient bili = biliClient.getIfAvailable();
        r.put("configured", "real".equalsIgnoreCase(engineMode) && bili != null && bili.cookiesComplete());
        r.put("qrcode_enabled", "real".equalsIgnoreCase(engineMode) && bili != null);
        return r;
    }

    /** 把 Cookie 体检结论落库，供界面（设置页徽标 / 发布中心红条）直接读取 */
    private void markCookieStatus(String status, String message) {
        settings.setValue("bili_cookie_status", status);
        settings.setValue("bili_cookie_checked_at", LocalDateTime.now().toString());
        settings.setValue("bili_cookie_message", message == null ? "" : message);
    }

    /**
     * 每 30 分钟体检一次登录态：先 nav，失效就试着自己续期（B站网页端也在周期调同一个接口），
     * 续不动才标成 EXPIRED 提示扫码。这样"会话自然滚到期"这一类不再需要人工干预，
     * 只有被服务端强制作废（改密码/异地登录/风控）才必须重新扫码。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 45 * 1000L)
    public void keepCookieAlive() {
        BiliClient bili = biliClient.getIfAvailable();
        if (!"real".equalsIgnoreCase(engineMode) || bili == null) {
            return;
        }
        if (!bili.cookiesComplete()) {
            markCookieStatus("MISSING", "未配置 Cookie 三件套，请扫码登录");
            return;
        }
        try {
            NavInfo n = bili.nav();
            if (n.login()) {
                if (!"VALID".equals(settings.getStr("bili_cookie_status", ""))) {
                    log.info("B站登录态恢复正常：{}", n.uname());
                }
                bind(n.uname());
                markCookieStatus("VALID", "Cookie 有效：" + n.uname());
                return;
            }
        } catch (BiliApiException e) {
            log.info("B站登录态校验异常，尝试续期: {}", e.getBiliMessage());
        }
        Map<String, Object> r = refreshCookie();
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            log.warn("B站 Cookie 已失效且续期未通过，需要重新扫码登录：{}", r.get("message"));
        }
    }

    @Override
    public PageResult<PublishTask> tasks(String status, Integer page, Integer size) {
        var q = Wrappers.<PublishTask>lambdaQuery().orderByDesc(PublishTask::getId);
        if (status != null && !status.isEmpty()) q.eq(PublishTask::getStatus, status);
        List<PublishTask> list = taskMapper.selectList(q);
        return PageResult.of(list, page, size);
    }

    @Override
    public PublishTask retry(long id) {
        PublishTask p = taskMapper.selectById(id);
        if (p == null) throw new BizException(404, "不存在");
        p.setStatus("QUEUED");
        p.setPhaseSec(0);
        p.setProgress(0);
        p.setErrorMsg(null);
        taskMapper.updateById(p);
        realEnqueued.remove(id);
        realProgress.remove(id);
        networkRetries.remove(id); // 人工重试重新给满自动重传预算
        return p;
    }

    @Override
    public PageResult<PublishTask> records(Integer page, Integer size) {
        List<PublishTask> list = taskMapper.selectList(Wrappers.<PublishTask>lambdaQuery()
                .eq(PublishTask::getStatus, "PUBLISHED")
                .orderByDesc(PublishTask::getId));
        return PageResult.of(list, page, size);
    }

    // ================= M3 real 模式发布驱动 =================

    /**
     * engine.mode=real 时接管发布状态机（1s tick）：
     * 1) QUEUED/孤儿 UPLOADING → 领取并异步执行 RealPublisher.execute（真实分片上传+稿件提交）；
     * 2) 心跳把已接管任务的 phase_sec 钉回 0 —— 编排器（禁止修改）的推进条件均为
     *    phase>3 / >8 / >5，读到 0 后 phase=3 永不满足，mock 状态机因此被无害挂起，
     *    不会提前调用 publish() 造成二次上传；进度同时被心跳修正；
     * 3) 提交成功（remote_id=BVxxxx）后进入真实回读：view?bvid= 可见 → PUBLISHED；超时 → FAILED；
     * 4) 编排器若已在本次 tick 用 mock 值覆盖 remote_id，用 submittedBvids 映射修复。
     */
    @Scheduled(fixedRate = 1000)
    public void realPublishTick() {
        if (!"real".equalsIgnoreCase(engineMode)) return;
        RealPublisher rp = realPublisher.getIfAvailable();
        if (rp == null) return;

        for (PublishTask p : taskMapper.selectList(Wrappers.<PublishTask>lambdaQuery()
                .orderByAsc(PublishTask::getId))) {
            long id = p.getId();
            String real = rp.submittedBvids().get(id);
            if (real != null && !real.equals(p.getRemoteId())) {
                // 修复编排器成功分支盖写的 BV1mock remote_id
                setFields(id, Map.of("remote_id", real));
            }
            String status = p.getStatus();
            if ("PUBLISHED".equals(status) || "FAILED".equals(status)) {
                realEnqueued.remove(id);
                realProgress.remove(id);
                reviewSince.remove(id);
                networkRetries.remove(id);
                continue;
            }
            switch (status) {
                case "QUEUED" -> {
                    if (!realEnqueued.contains(id)) claim(p, rp);
                }
                case "UPLOADING" -> {
                    if (realEnqueued.contains(id)) {
                        heartbeat(id, realProgress.getOrDefault(id, 0));
                    } else {
                        claim(p, rp); // 重启后的孤儿任务：重新执行（可能产生重复稿件，见报告遗留风险）
                    }
                }
                case "PLATFORM_REVIEW" -> {
                    String bvid = p.getRemoteId() != null && !p.getRemoteId().startsWith("BV1mock")
                            ? p.getRemoteId() : real;
                    if (bvid == null) {
                        heartbeat(id, realProgress.getOrDefault(id, 0)); // worker 尚在提交中
                        continue;
                    }
                    heartbeat(id, 100);
                    pollReview(rp, p, id, bvid);
                }
                default -> {
                }
            }
        }
    }

    private void claim(PublishTask p, RealPublisher rp) {
        long id = p.getId();
        realEnqueued.add(id);
        realProgress.put(id, 0);
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getStatus, "UPLOADING")
                .set(PublishTask::getPhaseSec, 0)
                .set(PublishTask::getProgress, 0)
                .set(PublishTask::getErrorMsg, null)
                .eq(PublishTask::getId, id));
        realExecutor.submit(() -> {
            try {
                rp.execute(id, pct -> realProgress.put(id, pct));
                reviewSince.putIfAbsent(id, System.currentTimeMillis());
            } catch (Throwable e) {
                String msg = e instanceof BiliApiException b ? b.toErrorMsg()
                        : "真实发布异常: " + e.getMessage();
                // 稿件提交（进度 95）之前失败的，远端不可能已有稿件：网络抖动就自动回队重传，
                // 无人值守跑通宵时偶发 DNS/断网不至于把整批作品全刷成"发布失败"。
                // 提交之后（进度 ≥95）一律落 FAILED 交人工——自动重试会产生重复稿件。
                int pct = realProgress.getOrDefault(id, 0);
                if (pct < 95 && isNetworkFault(msg) && tryNetworkRetry(id, p.getWorkId(), msg)) return;
                failTask(id, p.getWorkId(), msg);
            }
        });
        log.info("real 模式已接管发布任务 {}（work={}）", id, p.getWorkId());
    }

    /** 心跳：phase_sec 归零（挂起编排器 mock 推进），progress 用 worker 权威值 */
    private void heartbeat(long taskId, int progress) {
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getPhaseSec, 0)
                .set(PublishTask::getProgress, Math.max(0, progress))
                .eq(PublishTask::getId, taskId));
    }

    /** view?bvid= 回读转码/可见状态；可见→PUBLISHED，超时→FAILED */
    private void pollReview(RealPublisher rp, PublishTask p, long id, String bvid) {
        reviewSince.putIfAbsent(id, System.currentTimeMillis());
        BiliClient bili = biliClient.getIfAvailable();
        if (bili == null) return;
        long now = System.currentTimeMillis();
        if (now - lastViewPoll.getOrDefault(id, 0L) < VIEW_POLL_MS) return;
        lastViewPoll.put(id, now);
        try {
            if (bili.viewReady(bvid)) {
                LocalDateTime ts = LocalDateTime.now();
                taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                        .set(PublishTask::getStatus, "PUBLISHED")
                        .set(PublishTask::getPublishedAt, ts)
                        .set(PublishTask::getProgress, 100)
                        .eq(PublishTask::getId, id));
                updateWork(p.getWorkId(), "PUBLISHED", ts);
                log.info("任务 {} 回读通过，bvid={} 已可见 → PUBLISHED", id, bvid);
                realEnqueued.remove(id);
            } else if (now - reviewSince.getOrDefault(id, now) > REVIEW_TIMEOUT_MS) {
                // 稿件已提交但回读超时：若平台仍在审核/等待发布（62002/62003，含私密稿件），
                // 保持 PLATFORM_REVIEW 不误判 FAILED；其余情况按失败处理。
                int ownerCode = -999;
                String ownerMsg = "";
                try {
                    com.fasterxml.jackson.databind.JsonNode ov = bili.ownerView(bvid);
                    ownerCode = ov.path("code").asInt(-999);
                    ownerMsg = ov.path("message").asText("");
                } catch (RuntimeException ignore) {
                    // 回读异常走失败分支
                }
                if (ownerCode == 62002 || ownerCode == 62003 || ownerMsg.contains("审核") || ownerMsg.contains("等待发布")) {
                    log.info("任务 {} 稿件 {} 仍在平台审核/等待发布（code={} msg={}），保持 PLATFORM_REVIEW",
                            id, bvid, ownerCode, ownerMsg);
                    return;
                }
                if (ownerCode == -404) {
                    // 稿件确已提交（web/add code=0），但 -404 对"审核中的私密稿件"是正常表现
                    // （公开审核中会返回 62002）。回读无法确认≠发布失败：保持 PLATFORM_REVIEW，不误判 FAILED。
                    log.info("任务 {} 稿件 {} 回读 -404（审核中或仅自己可见），保持 PLATFORM_REVIEW", id, bvid);
                    return;
                }
                failTask(id, p.getWorkId(), "稿件已提交(bvid=" + bvid + ")，但回读超时（>15min 转码未完成/不可见）");
            }
        } catch (BiliApiException e) {
            log.warn("任务 {} view 回读失败: {}", id, e.toErrorMsg());
            if (now - reviewSince.getOrDefault(id, now) > REVIEW_TIMEOUT_MS) {
                failTask(id, p.getWorkId(), "稿件已提交(bvid=" + bvid + ")，回读持续失败: " + e.toErrorMsg());
            }
        }
    }

    /** 上传阶段的失败原因是否属于"网络本身不通"（而非鉴权/风控/参数被平台拒绝） */
    private static boolean isNetworkFault(String msg) {
        if (msg == null) return false;
        return msg.contains("网络异常") || msg.contains("网络请求失败") || msg.contains("I/O error")
                || msg.contains("Connection") || msg.contains("timed out") || msg.contains("超时");
    }

    /**
     * 网络抖动自动回队重传：还在上传阶段（远端无稿件）时才允许。
     *
     * @return true 表示已回队、调用方不要再落 FAILED
     */
    private boolean tryNetworkRetry(long taskId, Long workId, String msg) {
        int n = networkRetries.merge(taskId, 1, Integer::sum);
        if (n > NETWORK_RETRY_MAX) {
            log.warn("发布任务 {} 网络抖动自动重传已用尽（{} 次），落 FAILED 交人工", taskId, NETWORK_RETRY_MAX);
            return false;
        }
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getStatus, "QUEUED")
                .set(PublishTask::getProgress, 0)
                .set(PublishTask::getPhaseSec, 0)
                .set(PublishTask::getErrorMsg,
                        "网络抖动，第 " + n + "/" + NETWORK_RETRY_MAX + " 次自动重传（上一轮：" + trimMsg(msg) + "）")
                .eq(PublishTask::getId, taskId));
        realEnqueued.remove(taskId);
        realProgress.remove(taskId);
        reviewSince.remove(taskId);
        lastViewPoll.remove(taskId);
        log.warn("发布任务 {} 上传阶段网络抖动，自动回队重传（第 {}/{} 次）: {}",
                taskId, n, NETWORK_RETRY_MAX, msg);
        return true;
    }

    private static String trimMsg(String s) {
        if (s == null) return "发布失败";
        return s.substring(0, Math.min(240, s.length()));
    }

    private void failTask(long taskId, Long workId, String errorMsg) {
        String msg = errorMsg == null ? "发布失败" : errorMsg.substring(0, Math.min(480, errorMsg.length()));
        taskMapper.update(null, Wrappers.<PublishTask>lambdaUpdate()
                .set(PublishTask::getStatus, "FAILED")
                .set(PublishTask::getErrorMsg, msg)
                .eq(PublishTask::getId, taskId));
        updateWork(workId, "PUBLISH_FAILED", null);
        realEnqueued.remove(taskId);
        reviewSince.remove(taskId);
        lastViewPoll.remove(taskId);
        networkRetries.remove(taskId);
        log.warn("发布任务 {} FAILED: {}", taskId, msg);
    }

    private void updateWork(Long workId, String status, LocalDateTime publishedAt) {
        Work w = workId == null ? null : workMapper.selectById(workId);
        if (w == null) return;
        w.setStatus(status);
        w.setUpdatedAt(LocalDateTime.now());
        if (publishedAt != null && "PUBLISHED".equals(status)) w.setUpdatedAt(publishedAt);
        workMapper.updateById(w);
    }

    private void setFields(long taskId, Map<String, Object> fields) {
        var upd = Wrappers.<PublishTask>lambdaUpdate().eq(PublishTask::getId, taskId);
        fields.forEach((k, v) -> {
            if ("remote_id".equals(k)) upd.set(PublishTask::getRemoteId, v);
        });
        taskMapper.update(null, upd);
    }
}
