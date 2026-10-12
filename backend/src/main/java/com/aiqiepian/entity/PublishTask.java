package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发布任务（需求文档 §5 publish_task；work_title/phase_sec 与 mock-server 响应/状态机一致） */
@Data
@TableName("publish_task")
public class PublishTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workId;
    private String workTitle;
    /** 本期仅 bilibili */
    private String platform;
    /** QUEUED/UPLOADING/PLATFORM_REVIEW/PUBLISHED/FAILED */
    private String status;
    private Integer progress;
    /** 当前阶段已持续秒数（发布状态机内部计时，mock 同名返回） */
    private Integer phaseSec;
    /** 模拟稿件 ID，如 BV1mock000001 */
    private String remoteId;
    /** 重试需清空失败原因，updateById 必须写 null（对照 mock p.error_msg = null） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMsg;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    /** 播放量同步（B 站 web-interface/view 公开接口，仅真实 BV 稿件） */
    private Integer viewCount;
    private Integer likeCount;
    private Integer danmakuCount;
    private LocalDateTime viewSyncedAt;
}
