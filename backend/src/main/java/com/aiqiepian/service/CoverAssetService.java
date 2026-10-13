package com.aiqiepian.service;

import com.aiqiepian.entity.CoverAsset;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 封面素材：按房间归属的主播图片底图 */
public interface CoverAssetService {

    List<CoverAsset> list(Long roomId);

    /** 上传一张房间底图素材，返回落库后的素材行 */
    CoverAsset upload(Long roomId, MultipartFile file);

    /** 删除素材（连带磁盘文件） */
    Map<String, Object> delete(long id);

    /**
     * 把上传的图片存到 media 目录下，返回绝对路径字符串。
     * 文件名由服务端生成（不落用户原名），避免路径穿越与同名覆盖。
     */
    String saveImage(MultipartFile file, String subDir, String namePrefix);
}
