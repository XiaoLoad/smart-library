package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.security.CurrentUser;
import com.library.service.ImageBase64Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传控制器
 */
@RestController
public class FileController {

    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5MB

    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * 图片格式魔数（文件头签名）：
     * JPEG = FF D8 FF，PNG = 89 50 4E 47，GIF = GIF8，WEBP = RIFF....WEBP。
     * 仅凭扩展名校验可上传伪装成图片的任意内容（静态目录对外无鉴权可访问），
     * 魔数不匹配直接拒绝。
     */
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] MAGIC_GIF = {'G', 'I', 'F', '8'};
    private static final byte[] MAGIC_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] MAGIC_WEBP = {'W', 'E', 'B', 'P'};

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @Autowired
    private ImageBase64Service imageBase64Service;

    @PostMapping("/upload/image")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            throw new BusinessException("仅管理员可上传图片");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("图片大小不能超过 5MB");
        }
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("仅支持 jpg / jpeg / png / webp / gif 格式");
        }
        if (!hasImageMagic(file)) {
            throw new BusinessException("文件内容与图片格式不符，请上传真实图片");
        }

        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = dir.resolve(filename);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // context-path 为 /api，前端可直接用该路径访问
            return Result.success("上传成功", Map.of("url", "/api/uploads/" + filename));
        } catch (IOException e) {
            throw new BusinessException("图片保存失败，请稍后重试");
        }
    }

    /** 校验上传文件头魔数与声明格式一致 */
    private boolean hasImageMagic(MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (head.length < 12) {
                return false;
            }
            return startsWith(head, MAGIC_JPEG) || startsWith(head, MAGIC_PNG) || startsWith(head, MAGIC_GIF)
                    || (startsWith(head, MAGIC_RIFF) && startsWith(head, MAGIC_WEBP, 8));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return startsWith(data, prefix, 0);
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int offset) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 图片转 data URI，供小程序真机在无法使用 https 时显示封面。
     * 只认 /api/uploads/ 前缀，超出上传目录的路径一律忽略（不报错，前端按缺失处理）。
     *
     * @param paths 逗号分隔的相对路径，如 /api/uploads/covers/1.jpg,/api/uploads/covers/2.jpg
     * @param w     目标宽度，等比缩放且不放大；&lt;=0 表示按原图编码
     */
    @GetMapping("/file/image-base64")
    public Result<Map<String, String>> imageBase64(@RequestParam String paths,
                                                   @RequestParam(defaultValue = "240") int w) {
        List<String> list = List.of(paths.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (list.isEmpty()) {
            throw new BusinessException("请指定图片路径");
        }
        if (list.size() > ImageBase64Service.MAX_BATCH) {
            throw new BusinessException("单次最多请求 " + ImageBase64Service.MAX_BATCH + " 张图片");
        }
        Map<String, String> images = new LinkedHashMap<>();
        for (String uri : list) {
            String dataUri = imageBase64Service.toDataUri(uri, w);
            if (dataUri != null) {
                images.put(uri, dataUri);
            }
        }
        return Result.success(images);
    }
}
