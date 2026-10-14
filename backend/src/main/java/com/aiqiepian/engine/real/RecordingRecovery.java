package com.aiqiepian.engine.real;

import com.aiqiepian.common.SessionFiles;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.mapper.RecordingSessionMapper;
import com.aiqiepian.service.PipelineService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时收尾遗留的录制分段。
 *
 * <p>斗鱼/虎牙的录制由重连监管线程写 {@code *.segNNN.mp4}，只有该线程正常走到 finally 才会合并成
 * DB 里 {@code file_path} 指向的最终文件。进程被强杀（崩溃、被回收、直接关窗口）时这一步永远不会发生，
 * 于是几 GB 素材既播不了也分析不了——对管线彻底隐形。这里在启动时补一次收尾。
 *
 * <p>遗留分片恢复可播之后，**正在录制中被掐断的那几条会自动接上分析**（文件已经录好了，
 * 停在"已结束、未分析"就是用户看到的"录完却没走到下一步"）。历史遗留的 FINISHED 行不在此列——
 * 那可能有十几条几 GB 的旧素材，开机全串跑 Whisper 会把机器占死，想分析哪条在录制任务页点「重新分析」。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RecordingRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecordingRecovery.class);

    private final RecordingSessionMapper sessionMapper;
    private final PipelineService pipeline;

    @Override
    public void run(ApplicationArguments args) {
        // 多段合并要跑 ffmpeg concat，放到后台线程，不拖慢启动
        Thread t = new Thread(this::recover, "rec-recovery");
        t.setDaemon(true);
        t.start();
    }

    private void recover() {
        List<RecordingSession> rows = sessionMapper.selectList(
                Wrappers.<RecordingSession>lambdaQuery()
                        .in(RecordingSession::getStatus, List.of("RECORDING", "FINISHED"))
                        .orderByAsc(RecordingSession::getId));
        int merged = 0, closed = 0, queued = 0;
        for (RecordingSession s : rows) {
            String p = s.getFilePath();
            if (p == null || p.isBlank()) {
                continue;
            }
            File fin = new File(p);
            boolean finalMissing = !(fin.isFile() && fin.length() > 0);
            if (finalMissing) {
                List<File> segs = SessionFiles.segmentsOf(fin);
                if (segs.isEmpty()) {
                    continue;
                }
                long bytes = 0;
                for (File f : segs) bytes += f.length();
                if (!RealRecorder.mergeInto(fin, segs)) {
                    log.warn("遗留分片合并失败 session={} 段={}，素材仍不可用", s.getId(), segs.size());
                    continue;
                }
                merged++;
                log.info("遗留录制分片已收尾 session={} 段={} 字节={}", s.getId(), segs.size(), bytes);
            }
            // 上一进程遗留的 RECORDING 行：本进程刚起来，不可能持有它的 ffmpeg 句柄，
            // 留着会一直显示"录制中"且永远等不到文件
            if ("RECORDING".equals(s.getStatus())) {
                s.setStatus("FINISHED");
                s.setEndedAt(LocalDateTime.now());
                // duration_sec 存的是"计划录多久"（上限常为 4 小时），会话被掐断后它仍停在上限，
                // 界面拿它当总时长就会显示成 240:00；收尾时按实际录到的秒数收口。
                int real = s.getRecordedSec() == null ? 0 : s.getRecordedSec();
                if (real > 0 && (s.getDurationSec() == null || s.getDurationSec() > real)) s.setDurationSec(real);
                boolean queueAnalysis = "NONE".equals(nvl(s.getAnalyzeStatus())) || s.getAnalyzeStatus() == null;
                if (queueAnalysis) s.setAnalyzeStatus("PENDING");
                sessionMapper.updateById(s);
                closed++;
                if (queueAnalysis && pipeline.activeJob("ANALYZE", "session", s.getId()) == null) {
                    pipeline.addJob("ANALYZE", "session", s.getId());
                    queued++;
                    log.info("遗留录制已接上分析队列 session={} 录到 {} 秒", s.getId(), nz(s.getRecordedSec()));
                }
            }
        }
        if (merged > 0 || closed > 0) {
            log.info("启动录制收尾完成：合并分片 {} 条，关闭遗留录制中行 {} 条（其中 {} 条已自动排队分析；"
                    + "更早的历史会话请在录制任务点「重新分析」）", merged, closed, queued);
        }
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
