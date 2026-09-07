package com.library.controller;

import com.library.common.Result;
import com.library.entity.AiConversation;
import com.library.entity.AiMessage;
import com.library.mapper.AiConversationMapper;
import com.library.mapper.AiMessageMapper;
import com.library.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/conversation/list")
    public Result<Map<String, Object>> conversationList() {
        CurrentUser cu = CurrentUser.get();
        List<AiConversation> list = conversationMapper.selectByUserId(cu.getUserId());
        return Result.success(Map.of("list", list, "total", list.size()));
    }

    @GetMapping("/message/list")
    public Result<Map<String, Object>> messageList(@RequestParam Long conversationId) {
        List<AiMessage> list = messageMapper.selectByConversationId(conversationId);
        return Result.success(Map.of("list", list, "total", list.size()));
    }
}
