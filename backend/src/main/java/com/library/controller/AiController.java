package com.library.controller;

import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.AiBatchConfirmRequest;
import com.library.dto.AiChatRequest;
import com.library.dto.AiConfirmRequest;
import com.library.entity.AiConversation;
import com.library.entity.AiMessage;
import com.library.mapper.AiConversationMapper;
import com.library.mapper.AiMessageMapper;
import com.library.security.CurrentUser;
import com.library.service.AiService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 对话控制器
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiConversationMapper conversationMapper;

    @Autowired
    private AiMessageMapper messageMapper;

    @Autowired
    private AiService aiService;

    /**
     * 会话列表
     */
    @GetMapping("/conversation/list")
    public Result<Map<String, Object>> conversationList() {
        CurrentUser cu = CurrentUser.get();
        List<AiConversation> list = conversationMapper.selectByUserId(cu.getUserId());
        return Result.success(Map.of("list", list, "total", list.size()));
    }

    /**
     * 消息列表（窗口分页：按 id 游标向后翻页，先取最新一页，向上滚动加载更早）
     *
     * @param conversationId 会话 ID
     * @param beforeId      游标：传最老已加载消息的 id 返回更早一页；为空则取最新一页
     * @param pageSize      每页条数，默认 20
     */
    @GetMapping("/message/list")
    public Result<Map<String, Object>> messageList(
            @RequestParam Long conversationId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int pageSize) {
        // 归属校验：会话不存在或非本人会话一律拒绝（管理员亦只读自己的会话，与全站无跨用户读入口保持一致）
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        CurrentUser cu = CurrentUser.get();
        if (conv.getUserId() == null || !conv.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "无权查看该会话消息");
        }
        // 分页钳制：防止一次拉取过大分页拖垮查询
        pageSize = Math.max(1, Math.min(pageSize, 100));
        List<AiMessage> list = messageMapper.selectHistoryPage(conversationId, beforeId, pageSize);
        int total = messageMapper.countByConversationId(conversationId);
        // hasMore：本页满页即可能还有更早消息；前端亦可用 loadedCount < total 判定
        return Result.success(Map.of("list", list, "total", total, "hasMore", list.size() == pageSize));
    }

    /**
     * 创建新会话
     */
    @PostMapping("/conversation/create")
    public Result<AiConversation> createConversation() {
        return Result.success(aiService.createConversation());
    }

    /**
     * 删除会话（含消息）
     */
    @DeleteMapping("/conversation/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        aiService.deleteConversation(id);
        return Result.success("已删除", null);
    }

    /**
     * 流式对话（SSE）
     * <p>
     * 事件流（完整列表）：reset（工具开始前清空正文）/ tool（工具调用状态）/
     * confirm（写操作授权卡）/ batch_confirm（批量授权卡，batch 档位）/
     * rag（知识库参考资料）/ thinking（深度思考思维链增量）/
     * delta（增量正文）/ final（消毒后最终正文替换）/ thought（工具轮决策旁白）/
     * degrade（工具循环超限降级提示）/ done（"[DONE]"，本轮结束）/ error（错误消息）
     * <p>
     * 以上事件与前端 SSE 解析逻辑一一对应，新增事件类型需同步前端处理。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AiChatRequest req) {
        return aiService.chat(req.getConversationId(), req.getContent());
    }

    /**
     * 确认执行写操作（授权卡「确认办理」按钮的回调）。
     * <p>
     * <b>只传令牌不传参数</b>：服务端按令牌中保存的原始参数执行，
     * 因此客户端无法篡改要执行的写操作内容。令牌一次性、60 秒过期。
     *
     * @param req 令牌与目标工具名
     * @return 工具执行结果
     */
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@Valid @RequestBody AiConfirmRequest req) {
        CurrentUser cu = CurrentUser.get();
        Map<String, Object> result = aiService.confirmWrite(req.getToken(), cu.getUserId(), req.getTool(), req.getConversationId());
        return Result.success(result);
    }

    /**
     * 批量授权确认（batch 档位授权卡的回调）。
     * <p>
     * 与单笔 confirm 同一安全模型：只回传批次令牌，服务端按保存的批次顺序逐项执行，
     * 逐项回报结果（部分失败不影响已成功项）。
     */
    @PostMapping("/confirm/batch")
    public Result<Map<String, Object>> confirmBatch(@Valid @RequestBody AiBatchConfirmRequest req) {
        CurrentUser cu = CurrentUser.get();
        return Result.success(aiService.confirmBatchWrite(req.getToken(), cu.getUserId(), req.getConversationId()));
    }

    /**
     * 设置会话的写操作权限档位。
     * <p>
     * 仅会话本人可设置；auto（自动执行）档仅管理员可切换到——读者端动的是
     * 用户本人资产，保持逐笔确认不做放开。降档（任何档位切回 confirm）不受限。
     */
    @PostMapping("/conversation/{id}/permission-mode")
    public Result<Void> setPermissionMode(@PathVariable Long id, @RequestParam String mode) {
        CurrentUser cu = CurrentUser.get();
        aiService.setPermissionMode(id, cu.getUserId(), cu.getUserType(), mode);
        return Result.success("权限档位已更新", null);
    }
}
