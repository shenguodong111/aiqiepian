package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 作品/成片（需求文档 §5 work） */
@Data
@TableName("work")
public class Work {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long clipId;
    /** 归属房间：房间级封面素材按此挑选（历史作品可能为 null） */
    private Long roomId;
    private String roomName;
    private String title;
    private String description;
    /** 逗号分隔，≤10 个 */
    private String tags;
    private String coverPath;
    /** 人工为单个作品指定的封面底图；为空则用房间素材，房间也无素材则用成片截帧 */
    private String coverImage;
    private Boolean subtitleEnabled;
    /** big/outline/none */
    private String subtitleStyle;
    /** 9:16 / 16:9 / 1:1 */
    private String aspectRatio;
    /** DRAFT/RENDERING/READY/APPROVED/REJECTED/PUBLISHING/PUBLISHED/PUBLISH_FAILED */
    private String status;
    private String outputPath;
    private Integer score;
    /** 高光集锦：源录制文件绝对路径；非单切片作品为 null */
    private String sourcePath;
    /** 高光集锦裁切区间，格式 "start,end;start,end"（秒）；非集锦为 null */
    private String reelSegments;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
