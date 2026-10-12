package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管线任务（需求文档 §5 pipeline_job） */
@Data
@TableName("pipeline_job")
public class PipelineJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** MONITOR/RECORD/ANALYZE/RENDER/REVIEW/PUBLISH */
    private String jobType;
    /** room/session/clip/work */
    private String entityType;
    private Long entityId;
    /** PENDING/RUNNING/SUCCESS/FAILED/SKIPPED */
    private String status;
    private Integer progress;
    private Integer retryCount;
    private String log;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
