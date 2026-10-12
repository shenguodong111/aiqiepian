package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 应用设置 KV（需求文档 §5 app_setting：key/value 两列，value 为 JSON 标量字符串） */
@Data
@TableName("app_setting")
public class AppSetting {

    @TableId(value = "key", type = IdType.INPUT)
    private String key;
    private String value;
}
