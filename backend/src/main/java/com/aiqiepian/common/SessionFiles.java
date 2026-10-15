package com.aiqiepian.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 录制会话的文件名约定。斗鱼/虎牙走重连监管，实际写的是
 * {@code session_1_1791865.mp4.seg000.mp4}，DB 里的 file_path 始终是收尾后的最终名——
 * 所以录制中、或进程被强杀没走到合并时，最终名对应的文件并不存在。
 * 命名规则由录制器和素材/分析两侧共用，集中在这里避免各写一份。
 */
public final class SessionFiles {

    private SessionFiles() {
    }

    /** 该最终文件在录制目录下的所有分段，按 segNNN 升序（编号零填充，字典序即时间序） */
    public static List<File> segmentsOf(File finalFile) {
        if (finalFile == null) return List.of();
        File dir = finalFile.getParentFile();
        String prefix = finalFile.getName() + ".seg";
        if (dir == null || !dir.isDirectory()) return List.of();
        File[] fs = dir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".mp4"));
        if (fs == null || fs.length == 0) return List.of();
        List<File> segs = new ArrayList<>(Arrays.asList(fs));
        segs.sort(Comparator.comparing(File::getName));
        return segs;
    }

    /**
     * 会话当前真正可读的文件：优先最终文件；它还不存在时退到最新分段。
     * 没有这个回退，正在录制的会话点「▶ 播放」必然报文件不存在。
     */
    public static File playable(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        File fin = new File(filePath);
        if (fin.isFile() && fin.length() > 0) return fin;
        List<File> segs = segmentsOf(fin);
        return segs.isEmpty() ? null : segs.get(segs.size() - 1);
    }

    /**
     * 该会话的弹幕采集文件（media 根目录下的 {@code {平台房间号}_{会话号}_danmaku.jsonl}）。
     * 前缀是平台房间号、不是本项目的 room_id，所以只能按会话号后缀匹配。
     */
    public static List<File> danmakuFilesOf(File mediaDir, long sessionId) {
        if (mediaDir == null || !mediaDir.isDirectory()) return List.of();
        String suffix = "_" + sessionId + "_danmaku.jsonl";
        File[] fs = mediaDir.listFiles((d, n) -> n.endsWith(suffix));
        return fs == null || fs.length == 0 ? List.of() : Arrays.asList(fs);
    }
}
