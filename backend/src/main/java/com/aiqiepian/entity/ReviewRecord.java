package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审核记录（需求文档 §5 review_record） */
@Data
@TableName("review_record")
public class ReviewRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workId;
    /** AUTO_PASS/AUTO_REJECT/MANUAL_PASS/MANUAL_REJECT/MANUAL */
    private String decision;
    private Integer confidence;
    private String reason;
    /** system/local */
    private String operator;
    private LocalDateTime createdAt;
}
