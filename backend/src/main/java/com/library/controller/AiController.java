package com.library.controller;

import com.library.common.Result;
import com.library.dto.AiChatRequest;
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
     * 消息列表
     */
    @GetMapping("/message/list")
    public Result<Map<String, Object>> messageList(@RequestParam Long conversationId) {
        List<AiMessage> list = messageMapper.selectByConversationId(conversationId);
        return Result.success(Map.of("list", list, "total", list.size()));
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
     * 事件流：delta（增量文本）/ done（"[DONE]"）/ error（错误消息）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AiChatRequest req) {
        return aiService.chat(req.getConversationId(), req.getContent());
    }
}
