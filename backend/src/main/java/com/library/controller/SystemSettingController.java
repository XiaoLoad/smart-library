package com.library.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.*;
import com.library.entity.BorrowLimit;
import com.library.entity.MailConfig;
import com.library.entity.NotificationLog;
import com.library.entity.NotificationRule;
import com.library.entity.User;
import com.library.mapper.BorrowLimitMapper;
import com.library.mapper.MailConfigMapper;
import com.library.mapper.NotificationLogMapper;
import com.library.mapper.UserMapper;
import com.library.security.CurrentUser;
import com.library.service.AiService;
import com.library.service.LibraryConfigService;
import com.library.service.NotificationRuleService;
import com.library.service.NotificationScheduler;
import com.library.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置控制器。
 * <p>
 * 分组：
 * <ul>
 *   <li>账户安全：修改密码</li>
 *   <li>AI 渠道：多渠道增删改/启停/排序/连通性测试（仅管理员）</li>
 *   <li>借阅规则：逾期罚款费率（登录用户可读）、各身份借阅额度（仅管理员）</li>
 *   <li>AI 人设：可调整 Prompt 段在线编辑（仅管理员）</li>
 * </ul>
 */
@RestController
@RequestMapping("/system")
public class SystemSettingController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private AiService aiService;

    @Autowired
    private LibraryConfigService configService;

    @Autowired
    private BorrowLimitMapper borrowLimitMapper;

    @Autowired
    private NotificationRuleService ruleService;

    @Autowired
    private MailConfigMapper mailConfigMapper;

    @Autowired
    private NotificationLogMapper logMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationScheduler notificationScheduler;

    /** 仅管理员可访问的设置项校验 */
    private void requireAdmin() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserType() == null || cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可操作");
        }
    }

    // 账户安全

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

    // AI 渠道

    /**
     * 渠道列表（apiKey 脱敏，仅返回是否已配置）。
     * activeId 为当前生效渠道（排序最前的启用渠道）。
     */
    @GetMapping("/ai-channels")
    public Result<Map<String, Object>> listChannels() {
        requireAdmin();
        List<LibraryConfigService.Channel> channels = configService.listChannels();
        LibraryConfigService.Channel active = configService.getActiveChannel();

        List<Map<String, Object>> list = new ArrayList<>();
        for (LibraryConfigService.Channel c : channels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("provider", c.getProvider());
            m.put("model", c.getModel());
            m.put("endpoint", c.getEndpoint());
            m.put("enabled", Boolean.TRUE.equals(c.getEnabled()));
            m.put("thinkingEnabled", Boolean.TRUE.equals(c.getThinkingEnabled()));
            m.put("sort", c.getSort());
            m.put("apiKeySet", c.getApiKey() != null && !c.getApiKey().isBlank());
            m.put("active", active != null && active.getId().equals(c.getId()));
            list.add(m);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("activeId", active == null ? null : active.getId());
        return Result.success(data);
    }

    /**
     * 新增渠道
     */
    @PostMapping("/ai-channels")
    public Result<Void> addChannel(@Valid @RequestBody AiChannelRequest req) {
        requireAdmin();
        configService.addChannel(req);
        return Result.success("渠道添加成功", null);
    }

    /**
     * 编辑渠道（apiKey 留空表示不变）
     */
    @PutMapping("/ai-channels")
    public Result<Void> updateChannel(@Valid @RequestBody AiChannelRequest req) {
        requireAdmin();
        if (req.getId() == null) {
            throw new BusinessException(400, "渠道 ID 不能为空");
        }
        configService.updateChannel(req);
        return Result.success("渠道更新成功", null);
    }

    /**
     * 启用/停用渠道
     */
    @PostMapping("/ai-channels/toggle")
    public Result<Void> toggleChannel(@Valid @RequestBody AiChannelToggleRequest req) {
        requireAdmin();
        configService.toggleChannel(req.getId(), req.getEnabled());
        return Result.success(req.getEnabled() ? "渠道已启用" : "渠道已停用", null);
    }

    /**
     * 渠道排序（direction：-1 上移，1 下移）
     */
    @PostMapping("/ai-channels/move")
    public Result<Void> moveChannel(@Valid @RequestBody AiChannelMoveRequest req) {
        requireAdmin();
        int dir = req.getDirection() == null ? 0 : req.getDirection();
        if (dir != -1 && dir != 1) {
            throw new BusinessException(400, "非法的移动方向");
        }
        configService.moveChannel(req.getId(), dir);
        return Result.success();
    }

    /**
     * 删除渠道
     */
    @PostMapping("/ai-channels/delete")
    public Result<Void> deleteChannel(@Valid @RequestBody IdRequest req) {
        requireAdmin();
        configService.deleteChannel(req.getId());
        return Result.success("渠道已删除", null);
    }

    /**
     * 测试渠道连通性：实时拉取服务商模型列表。
     * 传 id 测试已保存渠道（Key 用库中存储值）；否则用表单中的 provider/apiKey/endpoint。
     */
    @PostMapping("/ai-channels/test")
    public Result<Map<String, Object>> testChannel(@RequestBody(required = false) AiChannelTestRequest req) {
        requireAdmin();
        String provider;
        String apiKey;
        String endpoint;
        if (req != null && req.getId() != null) {
            LibraryConfigService.Channel c = configService.getChannelById(req.getId());
            provider = c.getProvider();
            apiKey = c.getApiKey();
            endpoint = c.getEndpoint();
        } else if (req != null) {
            provider = req.getProvider();
            apiKey = req.getApiKey();
            endpoint = req.getEndpoint();
        } else {
            throw new BusinessException(400, "请提供渠道信息");
        }
        List<Map<String, String>> models = aiService.fetchAvailableModels(provider, apiKey, endpoint);
        Map<String, Object> data = new HashMap<>();
        data.put("list", models);
        data.put("total", models.size());
        return Result.success("连接正常，共拉取 " + models.size() + " 个可用模型", data);
    }

    // 借阅规则

    /**
     * 各身份借阅额度规则（教师/学生）。仅管理员可查看详情。
     */
    @GetMapping("/borrow-limits")
    public Result<List<BorrowLimit>> borrowLimits() {
        requireAdmin();
        return Result.success(borrowLimitMapper.selectAll());
    }

    /**
     * 保存某身份的借阅额度规则（按 user_type upsert）。
     */
    @PostMapping("/borrow-limits")
    public Result<Void> saveBorrowLimit(@Valid @RequestBody BorrowLimitRequest req) {
        requireAdmin();
        BorrowLimit limit = new BorrowLimit();
        limit.setUserType(req.getUserType());
        limit.setMaxBooks(req.getMaxBooks());
        limit.setLoanDays(req.getLoanDays());
        limit.setRenewMaxCount(req.getRenewMaxCount());
        limit.setRenewDays(req.getRenewDays());
        limit.setPickupDays(req.getPickupDays());
        borrowLimitMapper.upsert(limit);
        return Result.success("借阅额度规则已保存", null);
    }

    /**
     * 馆内规则（逾期罚款费率等）。所有登录用户可读——罚款页需向读者公示费率。
     */
    @GetMapping("/library-rules")
    public Result<Map<String, Object>> libraryRules() {
        BigDecimal rate = configService.getFineRatePerDay();
        Map<String, Object> data = new HashMap<>();
        data.put("fineRatePerDay", rate);
        return Result.success(data);
    }

    /**
     * 保存馆内规则（仅管理员）。
     */
    @PostMapping("/library-rules")
    public Result<Void> saveLibraryRules(@Valid @RequestBody LibraryRulesRequest req) {
        requireAdmin();
        configService.saveFineRate(req.getFineRatePerDay());
        return Result.success("馆内规则已保存", null);
    }

    // AI 人设（可调整 Prompt 段）

    /**
     * 读取可调整 Prompt 段（人设/语气/称呼）。
     */
    @GetMapping("/ai-prompt")
    public Result<Map<String, Object>> getAiPrompt() {
        requireAdmin();
        String content = configService.loadCustomizablePromptSegment();
        Map<String, Object> data = new HashMap<>();
        data.put("content", content);
        return Result.success(data);
    }

    /**
     * 保存可调整 Prompt 段到外部文件，即时生效。
     * 核心业务规则（工具调用、数据完整性等）硬编码在服务端，不受此内容影响。
     */
    @PutMapping("/ai-prompt")
    public Result<Void> saveAiPrompt(@Valid @RequestBody AiPromptRequest req) {
        requireAdmin();
        configService.saveCustomizablePromptSegment(req.getContent());
        return Result.success("AI 人设已保存并即时生效", null);
    }

    // 催还通知

    /**
     * 通知规则列表（即将逾期与各类逾期事件，管理员可整表读写）。
     */
    @GetMapping("/notification-rules")
    public Result<List<NotificationRule>> notificationRules() {
        requireAdmin();
        return Result.success(ruleService.listAll());
    }

    /**
     * 保存通知规则（按 event_type upsert；前端整表编辑后一次性提交）。
     */
    @PostMapping("/notification-rules")
    public Result<Void> saveNotificationRules(@RequestBody List<NotificationRule> rules) {
        requireAdmin();
        if (rules == null || rules.isEmpty()) {
            throw new BusinessException(400, "规则不能为空");
        }
        for (NotificationRule r : rules) {
            if (r.getEventType() == null || r.getEventType().isBlank()) {
                throw new BusinessException(400, "存在缺少事件类型的规则");
            }
        }
        ruleService.saveAll(rules);
        return Result.success("通知规则已保存", null);
    }

    /**
     * 邮件渠道配置（SMTP）。
     */
    @GetMapping("/mail-config")
    public Result<MailConfig> mailConfig() {
        requireAdmin();
        return Result.success(mailConfigMapper.selectOne());
    }

    /**
     * 保存邮件渠道配置。
     */
    @PostMapping("/mail-config")
    public Result<Void> saveMailConfig(@RequestBody MailConfig cfg) {
        requireAdmin();
        cfg.setId(1L);
        mailConfigMapper.update(cfg);
        return Result.success("邮件配置已保存", null);
    }

    /**
     * 通知发送日志（管理员分页查询，支持状态/渠道/事件/日期筛选）。
     */
    @GetMapping("/notifications")
    public Result<Map<String, Object>> notificationLogs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdmin();
        PageHelper.startPage(page, size);
        List<NotificationLog> list = logMapper.selectAdminPage(status, channel, eventType, startDate, endDate);
        PageInfo<NotificationLog> info = new PageInfo<>(list);
        Map<String, Object> data = new HashMap<>();
        data.put("list", info.getList());
        data.put("total", info.getTotal());
        data.put("page", info.getPageNum());
        data.put("size", info.getPageSize());
        return Result.success(data);
    }

    /**
     * 重发某条通知（邮件失败或站内未送达时人工补救）。
     */
    @PostMapping("/notifications/resend")
    public Result<Map<String, Object>> resendNotification(@RequestBody IdRequest req) {
        requireAdmin();
        if (req.getId() == null) {
            throw new BusinessException(400, "通知 ID 不能为空");
        }
        String msg = notificationService.resend(req.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("message", msg);
        return Result.success(msg, data);
    }

    /**
     * 手动触发一次催还扫描（立即按当前规则对到期/逾期借阅发通知）。
     */
    @PostMapping("/notifications/trigger")
    public Result<Map<String, Object>> triggerNotification() {
        requireAdmin();
        int n = notificationScheduler.manualTrigger();
        Map<String, Object> data = new HashMap<>();
        data.put("dispatched", n);
        return Result.success("已触发扫描，本次分发 " + n + " 条通知", data);
    }
}
