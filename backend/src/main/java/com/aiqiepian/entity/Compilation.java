package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 合集（多作品串联为一个成片；功能 M4） */
@Data
@TableName("compilation")
public class Compilation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    /** 作品 id 逗号分隔，按合集顺序 */
    private String workIds;
    private Integer workCount;
    /** 创建时按成员作品快照的总时长（秒） */
    private Integer totalDurationSec;
    /** DRAFT/RENDERING/READY/FAILED */
    private String status;
    private String outputPath;
    private Integer progress;
    /** 重试/重新生成需清空失败原因，updateById 必须写 null */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
