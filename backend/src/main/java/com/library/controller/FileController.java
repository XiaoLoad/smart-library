package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

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
}
