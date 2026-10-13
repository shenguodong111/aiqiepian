package com.aiqiepian.engine.real;

import com.aiqiepian.common.SessionFiles;
import com.aiqiepian.entity.RecordingSession;
import com.aiqiepian.mapper.RecordingSessionMapper;
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
 * <p>刻意<b>不</b>自动补跑分析：遗留会话可能有十几个、单个几 GB，开机就串跑 Whisper 会把机器占死。
 * 文件恢复可播后，用户想分析哪条在录制任务页点「重新分析」即可。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "engine.mode", havingValue = "real")
public class RecordingRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecordingRecovery.class);

    private final RecordingSessionMapper sessionMapper;

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
        int merged = 0, closed = 0;
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
                sessionMapper.updateById(s);
                closed++;
            }
        }
        if (merged > 0 || closed > 0) {
            log.info("启动录制收尾完成：合并分片 {} 条，关闭遗留录制中行 {} 条（需要分析请在录制任务点「重新分析」）",
                    merged, closed);
        }
    }
}
