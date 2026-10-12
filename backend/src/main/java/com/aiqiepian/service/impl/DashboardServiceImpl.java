package com.aiqiepian.service.impl;

import com.aiqiepian.entity.Clip;
import com.aiqiepian.entity.LiveRoom;
import com.aiqiepian.entity.PipelineJob;
import com.aiqiepian.entity.PublishTask;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.entity.Work;
import com.aiqiepian.mapper.ClipMapper;
import com.aiqiepian.mapper.LiveRoomMapper;
import com.aiqiepian.mapper.PipelineJobMapper;
import com.aiqiepian.mapper.PublishTaskMapper;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.mapper.WorkMapper;
import com.aiqiepian.service.DashboardService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 总览数据，结构与 server.mjs /api/dashboard/summary 完全一致 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final LiveRoomMapper roomMapper;
    private final RecordingSessionMapper sessionMapper;
    private final ClipMapper clipMapper;
    private final WorkMapper workMapper;
    private final PublishTaskMapper publishTaskMapper;
    private final PipelineJobMapper jobMapper;
    private final SettingsService settings;

    @Value("${engine.mode:mock}")
    private String engineMode;

    @Override
    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now();

        List<LiveRoom> rooms = roomMapper.selectList(Wrappers.<LiveRoom>lambdaQuery().orderByAsc(LiveRoom::getId));
        List<RecordingSession> sessions = sessionMapper.selectList(
                Wrappers.<RecordingSession>lambdaQuery().orderByAsc(RecordingSession::getId));
        List<PipelineJob> jobs = jobMapper.selectList(null);
        List<PublishTask> publishes = publishTaskMapper.selectList(null);

        // stats
        long roomsMonitoring = rooms.stream()
                .filter(r -> Boolean.TRUE.equals(r.getMonitorEnabled()))
                .filter(r -> "MONITORING".equals(r.getStatus()) || "RECORDING".equals(r.getStatus()))
                .count();
        long todayRecordSec = sessions.stream()
                .filter(s -> isToday(s.getStartedAt(), today))
                .mapToLong(s -> s.getRecordedSec() == null ? 0 : s.getRecordedSec())
                .sum();
        long todayClips = clipMapper.selectCount(Wrappers.<Clip>lambdaQuery()
                .ge(Clip::getCreatedAt, today.atStartOfDay())
                .lt(Clip::getCreatedAt, today.plusDays(1).atStartOfDay()));
        long todayPublished = publishes.stream().filter(p -> isToday(p.getPublishedAt(), today)).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("rooms_monitoring", roomsMonitoring);
        stats.put("today_record_sec", todayRecordSec);
        stats.put("today_clips", todayClips);
        stats.put("today_published", todayPublished);

        // pipeline 六节点
        long stMonitor = rooms.stream().filter(r -> "MONITORING".equals(r.getStatus())).count();
        long stRecord = sessions.stream().filter(s -> "RECORDING".equals(s.getStatus())).count();
        List<Map<String, Object>> pipeline = List.of(
                node("监控", stMonitor),
                node("录制", stRecord),
                node("分析", runningJobs(jobs, "ANALYZE")),
                node("成片", runningJobs(jobs, "RENDER")),
                node("审核", runningJobs(jobs, "REVIEW")),
                node("发布", publishes.stream().filter(p ->
                        List.of("QUEUED", "UPLOADING", "PLATFORM_REVIEW").contains(p.getStatus())).count()));

        // 实时任务：最近 10 条未完成/失败可重试任务（倒序）
        List<PipelineJob> runningJobs = jobMapper.selectList(Wrappers.<PipelineJob>lambdaQuery()
                .in(PipelineJob::getStatus, "PENDING", "RUNNING", "FAILED")
                .orderByDesc(PipelineJob::getId)
                .last("LIMIT 10"));

        List<Work> recentWorks = workMapper.selectList(Wrappers.<Work>lambdaQuery()
                .orderByDesc(Work::getId).last("LIMIT 6"));

        long diskFreeGb = new File(System.getProperty("user.dir")).getUsableSpace() / (1024L * 1024 * 1024);
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("disk_free_gb", diskFreeGb);
        system.put("engine_version", engineMode + "-1.0.0");
        system.put("auto_run", settings.getBool("auto_run", true));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("pipeline", pipeline);
        result.put("running_jobs", runningJobs);
        result.put("recent_works", recentWorks);
        result.put("system", system);
        return result;
    }

    private static Map<String, Object> node(String stage, long running) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("running", running);
        return m;
    }

    private static long runningJobs(List<PipelineJob> jobs, String type) {
        return jobs.stream().filter(j -> type.equals(j.getJobType()) && "RUNNING".equals(j.getStatus())).count();
    }

    private static boolean isToday(java.time.LocalDateTime t, LocalDate today) {
        return t != null && t.toLocalDate().equals(today);
    }
}
