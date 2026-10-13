package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 录制会话（需求文档 §5 recording_session；room_name/platform 为与 mock 响应一致的冗余展示字段） */
@Data
@TableName("recording_session")
public class RecordingSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private String roomName;
    private String platform;
    /** MONITORING/RECORDING/ANALYZING/IDLE/FINISHED */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 目标时长（秒），mock 随机 25-90 */
    private Integer durationSec;
    /** 已录时长（秒），真实模式收尾时=最终文件实际捕获时长（非墙钟） */
    private Integer recordedSec;
    /** 断流重连次数（斗鱼/虎牙监管模式；B站单进程恒 0） */
    private Integer reconnectCount;
    private String filePath;
    private Long fileSizeBytes;
    /** NONE/PENDING/RUNNING/DONE */
    private String analyzeStatus;
    private LocalDateTime createdAt;
}
