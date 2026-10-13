package com.aiqiepian.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 封面素材：主播图片底图，按房间归属，成片封面 = 素材图 + 标题文字 */
@Data
@TableName("cover_asset")
public class CoverAsset {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    private String filePath;
    private String fileName;
    private LocalDateTime createdAt;
}
