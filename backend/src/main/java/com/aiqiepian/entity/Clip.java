package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 候选片段（需求文档 §5 clip） */
@Data
@TableName("clip")
public class Clip {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long roomId;
    private String roomName;
    private Integer startSec;
    private Integer endSec;
    private Integer durationSec;
    /** 精彩评分 0-100 */
    private Integer score;
    private String aiReason;
    private Integer danmakuHeat;
    /** ---------- M5 弹幕统计增强（FR-P4-06）：无弹幕 jsonl 时为 null ---------- */
    /** 片段区间内弹幕总条数 */
    private Integer danmakuCount;
    /** 弹幕密度 = count / 分钟（取整） */
    private Integer danmakuPerMin;
    /** 60 秒滚动窗内最大条数 */
    private Integer peakPerMin;
    /** 区间内去重 uid 人数 */
    private Integer uniqueUsers;
    /** 高频弹幕前 5（JSON 数组字符串 [{"t":"哈哈","c":12},...]） */
    private String topDanmaku;
    /** 主播名（live_room.anchor_name 冗余） */
    private String anchorName;
    /** PENDING/RENDERED/DISCARDED */
    private String status;
    private String filePath;
    /** 内容指纹：约 8 帧 8×8 灰度平均哈希拼接的 hex，用于轮播/重复内容去重（real 模式才有值） */
    private String fingerprint;
    private LocalDateTime createdAt;
}
