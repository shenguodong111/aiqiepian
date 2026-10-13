package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 直播间（需求文档 §5 live_room） */
@Data
@TableName("live_room")
public class LiveRoom {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** bilibili/douyu/huya/douyin */
    private String platform;
    private String roomUrl;
    /** 房间备注名：用户自定义，用于拼接作品标题，监控不会覆盖（仅在为空时用直播标题补一次默认） */
    private String roomName;
    /** 主播名 */
    private String anchorName;
    /** 直播间真实标题（监控每次开播检测刷新，仅供展示，不参与标题拼接） */
    private String liveTitle;
    private Boolean monitorEnabled;
    private Boolean autoRecord;
    /** 房间级自动发布开关：审核通过后是否自动进入发布队列（全局 settings.auto_publish 仍需为真） */
    private Boolean autoPublish;
    /** 发布标签（逗号分隔）：发布到 B站 时自动作为话题标签带上，为空则用全局默认 */
    private String publishTags;
    /** highlight/full/clip */
    private String analyzeTemplate;
    /** OFF/IDLE/MONITORING/RECORDING */
    private String status;
    private LocalDateTime lastLiveAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
