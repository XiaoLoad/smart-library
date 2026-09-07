package com.library.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.AiConfigRequest;
import com.library.dto.ChangePasswordRequest;
import com.library.entity.SystemSetting;
import com.library.entity.User;
import com.library.mapper.SystemSettingMapper;
import com.library.mapper.UserMapper;
import com.library.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统设置控制器
 */
@RestController
@RequestMapping("/system")
public class SystemSettingController {

    @Autowired
    private SystemSettingMapper systemSettingMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        CurrentUser cu = CurrentUser.get();
        User user = userMapper.selectById(cu.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        if (req.getNewPassword().length() < 6) {
            throw new BusinessException("新密码长度不能少于 6 位");
        }
        userMapper.updatePassword(user.getId(), passwordEncoder.encode(req.getNewPassword()));
        return Result.success("密码修改成功", null);
    }

    /**
     * 获取 AI 接口配置（脱敏返回）
     */
    @GetMapping("/ai-config")
    public Result<Map<String, Object>> getAiConfig() {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可查看 AI 配置");
        }
        SystemSetting setting = systemSettingMapper.selectByKey("ai_config");
        Map<String, Object> data = new HashMap<>();
        if (setting != null && setting.getSettingValue() != null) {
            try {
                data = objectMapper.readValue(setting.getSettingValue(), Map.class);
                // 脱敏：只返回 apiKey 是否已配置
                String apiKey = (String) data.get("apiKey");
                data.put("apiKey", apiKey);
                data.put("apiKeySet", apiKey != null && !apiKey.isEmpty());
            } catch (Exception e) {
                data.put("provider", "deepseek");
                data.put("model", "deepseek-chat");
                data.put("apiKeySet", false);
            }
        } else {
            data.put("provider", "deepseek");
            data.put("model", "deepseek-chat");
            data.put("apiKeySet", false);
        }
        return Result.success(data);
    }

    /**
     * 保存 AI 接口配置
     */
    @PostMapping("/ai-config")
    public Result<Void> saveAiConfig(@Valid @RequestBody AiConfigRequest req) {
        CurrentUser cu = CurrentUser.get();
        if (cu.getUserType() != 3) {
            return Result.error(403, "仅管理员可配置 AI 接口");
        }
        Map<String, Object> config = new HashMap<>();
        config.put("provider", req.getProvider());
        config.put("apiKey", req.getApiKey() == null ? "" : req.getApiKey());
        config.put("model", req.getModel());
        config.put("endpoint", req.getEndpoint() == null ? "" : req.getEndpoint());
        try {
            String json = objectMapper.writeValueAsString(config);
            systemSettingMapper.upsert("ai_config", json);
        } catch (Exception e) {
            throw new BusinessException("保存配置失败：" + e.getMessage());
        }
        return Result.success("AI 配置保存成功", null);
    }
}
