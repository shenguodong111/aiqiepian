package com.aiqiepian.service.impl;

import com.aiqiepian.common.BizException;
import com.aiqiepian.entity.CoverAsset;
import com.aiqiepian.mapper.CoverAssetMapper;
import com.aiqiepian.service.CoverAssetService;
import com.aiqiepian.service.SettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CoverAssetServiceImpl implements CoverAssetService {

    private static final Logger log = LoggerFactory.getLogger(CoverAssetServiceImpl.class);

    private final CoverAssetMapper assetMapper;
    private final SettingsService settings;

    @Override
    public List<CoverAsset> list(Long roomId) {
        if (roomId == null) throw new BizException("缺少 room_id");
        return assetMapper.selectList(Wrappers.<CoverAsset>lambdaQuery()
                .eq(CoverAsset::getRoomId, roomId).orderByAsc(CoverAsset::getId));
    }

    @Override
    public CoverAsset upload(Long roomId, MultipartFile file) {
        if (roomId == null) throw new BizException("缺少 room_id");
        String path = saveImage(file, "covers/assets/" + roomId, "asset");
        CoverAsset a = new CoverAsset();
        a.setRoomId(roomId);
        a.setFilePath(path);
        a.setFileName(file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? new File(path).getName() : file.getOriginalFilename());
        a.setCreatedAt(LocalDateTime.now());
        assetMapper.insert(a);
        log.info("封面素材已上传 room={} asset={} {}", roomId, a.getId(), path);
        return a;
    }

    @Override
    public Map<String, Object> delete(long id) {
        CoverAsset a = assetMapper.selectById(id);
        if (a == null) throw new BizException(404, "素材不存在");
        long freed = 0;
        if (a.getFilePath() != null) {
            File f = new File(a.getFilePath());
            if (f.isFile()) {
                freed = f.length();
                if (!f.delete()) log.warn("封面素材文件删除失败: {}", a.getFilePath());
            }
        }
        assetMapper.deleteById(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed", 1);
        m.put("freed_bytes", freed);
        return m;
    }

    @Override
    public String saveImage(MultipartFile file, String subDir, String namePrefix) {
        if (file == null || file.isEmpty()) throw new BizException("请选择图片文件");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(original, file.getContentType());
        if (ext == null) {
            throw new BizException("仅支持 jpg / png / webp 图片（当前：" + original + "）");
        }
        if (file.getSize() > 20L * 1024 * 1024) throw new BizException("图片超过 20MB");
        Path dir = Path.of(settings.getStr("media_dir", "./media")).resolve(subDir.replace('/', File.separatorChar));
        String name = namePrefix + "_" + System.currentTimeMillis()
                + "_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext;
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve(name);
            try (var in = file.getInputStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return out.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            throw new BizException("图片保存失败: " + e.getMessage());
        }
    }

    /** 只按扩展名/MIME 白名单放行，返回归一化扩展名；不支持则 null */
    private static String extOf(String original, String contentType) {
        String n = original.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return ".jpg";
        if (n.endsWith(".png")) return ".png";
        if (n.endsWith(".webp")) return ".webp";
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.equals("image/jpeg")) return ".jpg";
        if (ct.equals("image/png")) return ".png";
        if (ct.equals("image/webp")) return ".webp";
        return null;
    }
}
