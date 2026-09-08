package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import com.library.entity.AiConversation;
import com.library.entity.SystemSetting;
import com.library.mapper.*;
import com.library.security.CurrentUser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AI 对话服务：基于 LangChain4j 1.0.0 + OpenAI 兼容接口（DeepSeek/OpenAI 等）。
 * <p>
 * 提供：会话增删、SSE 流式对话（含历史记忆 + 工具调用 + 首条消息自动生成标题）。
 * <p>
 * 记忆管理：历史「用户提问 + AI 最终回答」以纯文本持久化到 MySQL，每请求加载进窗口记忆；
 * 工具调用的中间消息（tool_calls / tool 结果）仅存在于请求内内存记忆，不落库，
 * 以保证多轮工具调用上下文完整且不会污染历史（详见 {@link #chat} 注释）。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 单会话保留的最大历史消息条数（窗口记忆） */
    private static final int MAX_MEMORY_MESSAGES = 40;
    /** SSE 超时时间（毫秒） */
    private static final long SSE_TIMEOUT_MS = 180_000L;
    /** 首条消息生成标题的最大字符数 */
    private static final int TITLE_MAX_LEN = 20;
    /** 模型调用超时（秒），避免长时间无响应卡死 SSE */
    private static final long MODEL_TIMEOUT_SECONDS = 60L;

    @Autowired private SystemSettingMapper systemSettingMapper;
    @Autowired private AiConversationMapper conversationMapper;
    @Autowired private AiMessageMapper messageMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BorrowMapper borrowMapper;
    @Autowired private BookMapper bookMapper;
    @Autowired private FineMapper fineMapper;
    @Autowired private AnnouncementMapper announcementMapper;
    @Autowired private BorrowLimitMapper borrowLimitMapper;
    @Autowired private UserMapper userMapper;

    /** 缓存最近构建的流式模型，配置未变时复用，避免重复创建 OkHttpClient */
    private volatile OpenAiStreamingChatModel cachedModel;
    private volatile String cachedConfigHash;

    /**
     * AI 服务接口（流式返回 TokenStream）。
     * 单 String 参数由 LangChain4j 自动识别为用户消息。
     */
    public interface LibraryAssistant {
        TokenStream chat(String userMessage);
    }

    // ===== 会话管理 =====

    /**
     * 创建新会话。标题暂为「新对话」，首条消息后自动更新。
     */
    public AiConversation createConversation() {
        CurrentUser cu = CurrentUser.get();
        AiConfig cfg = loadConfig();
        AiConversation c = new AiConversation();
        c.setUserId(cu.getUserId());
        c.setTitle("新对话");
        c.setModel(cfg.model());
        c.setStatus(1);
        c.setCreateTime(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }

    /**
     * 删除会话（含消息）。仅本人可删。
     */
    public void deleteConversation(Long id) {
        CurrentUser cu = CurrentUser.get();
        AiConversation c = conversationMapper.selectById(id);
        if (c == null) {
            return;
        }
        if (!c.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "无权删除该会话");
        }
        messageMapper.deleteByConversationId(id);
        conversationMapper.deleteById(id);
    }

    // ===== 流式对话 =====

    /**
     * 流式对话：返回 SseEmitter。
     * <p>
     * 事件流：
     * <ul>
     *   <li>{@code delta} —— 增量文本片段</li>
     *   <li>{@code tool}  —— 工具调用过程（JSON：{name, phase, detail}）</li>
     *   <li>{@code done}  —— "[DONE]"，表示本轮结束</li>
     *   <li>{@code error} —— 错误消息</li>
     * </ul>
     * <p>
     * 记忆策略：每请求从 DB 加载历史「纯文本」消息（用户提问 + AI 最终回答）构建窗口记忆；
     * 工具调用的中间消息（带 tool_calls 的 AiMessage、ToolExecutionResultMessage）只存在于
     * 本次请求的内存记忆中，<b>不落库</b>。
     * <p>
     * 原因：OpenAI/DeepSeek 协议要求多轮工具调用必须原样回传 assistant 的 tool_calls 与
     * 对应 tool 结果；而 ai_messages 表只存文本。若把工具轮的过渡语（如「我来查询」）落库、
     * 却丢失 tool_calls/tool 结果，下一轮模型会看到一堆孤立的过渡语、看不到工具调用记录，
     * 从而反复重复调用工具。因此 DB 只保存完整的用户提问与最终回答，请求内由 LangChain4j
     * 自动维护完整的工具调用上下文。
     */
    public SseEmitter chat(Long conversationId, String content) {
        CurrentUser cu = CurrentUser.get();
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!conv.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "无权操作该会话");
        }

        AiConfig cfg = loadConfig();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new BusinessException(400, "AI 接口未配置，请联系管理员在系统设置中配置 API Key");
        }
        OpenAiStreamingChatModel model = getOrBuildModel(cfg);

        // 1. 从 DB 加载历史纯文本消息，构建本次请求的窗口记忆（工具中间态不入 DB）
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(MAX_MEMORY_MESSAGES);
        List<com.library.entity.AiMessage> history = messageMapper.selectByConversationId(conversationId);
        for (com.library.entity.AiMessage m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(m.getRole())) {
                memory.add(UserMessage.from(m.getContent()));
            } else if ("assistant".equalsIgnoreCase(m.getRole())) {
                memory.add(AiMessage.from(m.getContent()));
            }
        }

        // 2. 落库用户消息
        com.library.entity.AiMessage userMsg = new com.library.entity.AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(content);
        userMsg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 3. 构建每请求独立的工具实例（烘焙 userId，规避异步线程丢失 CurrentUser）
        LibraryTools tools = new LibraryTools(cu.getUserId(), cu.getUserType(),
                borrowMapper, bookMapper, fineMapper, announcementMapper,
                borrowLimitMapper, userMapper);

        String displayName = cu.getUsername() == null ? "用户" : cu.getUsername();
        boolean needsTitle = conv.getTitle() == null || conv.getTitle().isBlank()
                || "新对话".equals(conv.getTitle());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 工具事件发射器：将工具调用过程通过 SSE 推送给前端
        tools.setEventEmitter((toolName, phase, detail) -> {
            try {
                String payload = objectMapper.writeValueAsString(
                        Map.of("name", toolName, "phase", phase,
                                "detail", detail == null ? "" : detail));
                emitter.send(SseEmitter.event().name("tool").data(payload));
            } catch (IOException e) {
                // 客户端已断开，忽略
            } catch (Exception e) {
                log.warn("[AI] 工具事件序列化失败: {}", e.getMessage());
            }
        });

        try {
            LibraryAssistant assistant = AiServices.builder(LibraryAssistant.class)
                    .streamingChatModel(model)
                    // 请求级记忆：持有完整工具调用上下文（含 tool_calls/tool 结果），不落库
                    .chatMemory(memory)
                    .tools(tools)
                    .systemMessageProvider(memoryId -> buildSystemPrompt(displayName))
                    .build();

            assistant.chat(content)
                    .onPartialResponse(partial -> {
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(partial));
                        } catch (IOException e) {
                            // 客户端已断开，忽略
                        }
                    })
                    .onCompleteResponse(response -> {
                        // 落库最终 AI 回答：取最终一轮模型响应的文本（不含工具轮过渡语）
                        AiMessage finalAi = response.aiMessage();
                        String answer = finalAi == null ? null : finalAi.text();
                        if (answer != null && !answer.isBlank()) {
                            com.library.entity.AiMessage aiMsg = new com.library.entity.AiMessage();
                            aiMsg.setConversationId(conversationId);
                            aiMsg.setRole("assistant");
                            aiMsg.setContent(answer);
                            aiMsg.setCreateTime(LocalDateTime.now());
                            messageMapper.insert(aiMsg);
                        }
                        // 首条消息自动生成标题
                        if (needsTitle) {
                            String title = content.length() > TITLE_MAX_LEN
                                    ? content.substring(0, TITLE_MAX_LEN) + "…"
                                    : content;
                            conversationMapper.updateTitle(conversationId, title);
                        }
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (IOException e) {
                            // 忽略
                        }
                        emitter.complete();
                    })
                    .onError(err -> {
                        try {
                            // 提取可读错误信息：LangChain4j 异常 message 常为空，需取 cause
                            String msg = err.getMessage();
                            if (msg == null || msg.isBlank()) {
                                Throwable cause = err.getCause();
                                if (cause != null) {
                                    msg = cause.getMessage();
                                }
                            }
                            if (msg == null || msg.isBlank()) {
                                msg = err.getClass().getSimpleName();
                            }
                            emitter.send(SseEmitter.event().name("error").data(msg));
                        } catch (IOException e) {
                            // 忽略
                        }
                        // 注意：不能用 completeWithError，否则 Spring 异常处理器会尝试用 JSON Result 写入 SSE 响应，触发 HttpMessageNotWritableException
                        emitter.complete();
                    })
                    .start();
        } catch (RuntimeException e) {
            log.error("[AI] 构建对话失败 conversationId={}", conversationId, e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(e.getMessage() == null ? "AI 服务异常" : e.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }

        emitter.onTimeout(emitter::complete);
        emitter.onError(t -> emitter.complete());

        return emitter;
    }

    // ===== 内部方法 =====

    private String buildSystemPrompt(String username) {
        return """
                你是智慧图书借阅管理系统的 AI 助手「智」。你可以通过工具查询馆藏图书、当前用户本人的借阅记录、罚款、借阅额度以及馆内公告。

                行为规范：
                - 使用中文，简洁友好。
                - 工具返回的数据仅限当前用户（%s）本人，不要泄露其他用户数据。
                - 若工具返回空结果，如实告知，不要编造。
                - 回答图书/借阅/罚款等事实问题时，必须先调用对应工具，不要凭空回答。
                - 涉及日期/金额时按返回值原样表述。
                """.formatted(username);
    }

    /** AI 配置（来自 system_settings.ai_config） */
    private record AiConfig(String provider, String apiKey, String model, String endpoint) {}

    private AiConfig loadConfig() {
        SystemSetting s = systemSettingMapper.selectByKey("ai_config");
        if (s == null || s.getSettingValue() == null) {
            return new AiConfig("deepseek", "", "deepseek-chat", "");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.readValue(s.getSettingValue(), Map.class);
            String provider = str(m.get("provider"), "deepseek");
            String apiKey = str(m.get("apiKey"), "");
            String model = str(m.get("model"), "deepseek-chat");
            String endpoint = str(m.get("endpoint"), "");
            return new AiConfig(provider, apiKey, model, endpoint);
        } catch (Exception e) {
            return new AiConfig("deepseek", "", "deepseek-chat", "");
        }
    }

    private OpenAiStreamingChatModel getOrBuildModel(AiConfig cfg) {
        String hash = cfg.provider() + "|" + cfg.apiKey() + "|" + cfg.model() + "|" + cfg.endpoint();
        if (cachedModel != null && hash.equals(cachedConfigHash)) {
            return cachedModel;
        }
        // endpoint 可能是完整 chat/completions URL，需转为 baseUrl（LangChain4j 会自动追加 /chat/completions）
        String baseUrl = normalizeBaseUrl(cfg.endpoint());
        if (baseUrl == null || baseUrl.isBlank()) {
            String provider = cfg.provider() == null ? "deepseek" : cfg.provider();
            baseUrl = switch (provider) {
                case "openai" -> "https://api.openai.com/v1";
                case "deepseek" -> "https://api.deepseek.com";
                case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
                case "moonshot" -> "https://api.moonshot.cn/v1";
                case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
                case "baichuan" -> "https://api.baichuan-ai.com/v1";
                case "minimax" -> "https://api.minimax.chat/v1";
                default -> "https://api.deepseek.com";
            };
        }
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(cfg.apiKey())
                .modelName(cfg.model())
                // 自定义 HTTP 客户端：对 DeepSeek 模型自动关闭 thinking 模式，
                // 规避 reasoning_content 在多轮/工具调用时必须回传而 LangChain4j 不支持的问题
                .httpClientBuilder(new DeepSeekCompatHttpClient.Builder())
                // 模型调用超时：避免长时间无响应卡死 SSE（参考 langchain4j-examples）
                .timeout(Duration.ofSeconds(MODEL_TIMEOUT_SECONDS))
                // 请求/响应日志：便于排查调用失败、token 用量等问题
                .logRequests(true)
                .logResponses(true)
                .build();
        cachedModel = model;
        cachedConfigHash = hash;
        return model;
    }

    /**
     * 将用户填写的接口地址规范化为 baseUrl。
     * 用户可能填写完整 chat/completions URL，需去掉后缀；LangChain4j 会自动追加 /chat/completions。
     */
    private String normalizeBaseUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String url = endpoint.trim();
        int idx = url.indexOf("/chat/completions");
        if (idx > 0) {
            return url.substring(0, idx);
        }
        idx = url.indexOf("/chatcompletion");
        if (idx > 0) {
            return url.substring(0, idx);
        }
        return url;
    }

    private String str(Object o, String def) {
        return o == null || o.toString().isBlank() ? def : o.toString();
    }

    // ===== 模型列表动态拉取 =====

    /**
     * 从 AI 服务商 API 动态拉取可用模型列表。
     * <p>
     * 各厂商均兼容 OpenAI GET /models 格式，响应为：
     * <pre>{ "object": "list", "data": [{ "id": "...", "owned_by": "..." }] }</pre>
     *
     * @param provider 服务商（可选，空则用已保存配置）
     * @param apiKey  API Key（可选，空则用已保存配置）
     * @param endpoint 接口地址（可选，空则用已保存配置）
     */
    public List<Map<String, String>> fetchAvailableModels(String provider, String apiKey, String endpoint) {
        AiConfig cfg = loadConfig();
        String p = (provider != null && !provider.isBlank()) ? provider : cfg.provider();
        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey : cfg.apiKey();
        String ep = (endpoint != null && !endpoint.isBlank()) ? endpoint : cfg.endpoint();

        if (key == null || key.isBlank()) {
            throw new BusinessException(400, "请先配置 API Key");
        }

        String modelsUrl = deriveModelsUrl(p, ep);
        if (modelsUrl == null) {
            throw new BusinessException(400, "无法确定模型列表接口地址，请检查接口地址配置");
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new BusinessException(400, "服务商返回错误（HTTP " + response.statusCode() + "）");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new BusinessException(500, "模型列表格式异常，无法解析");
            }

            List<Map<String, String>> models = new ArrayList<>();
            for (JsonNode m : data) {
                String id = m.has("id") ? m.get("id").asText() : null;
                if (id != null && !id.isBlank()) {
                    models.add(Map.of("id", id, "name", id));
                }
            }
            models.sort(Comparator.comparing(m -> m.get("id")));
            if (models.isEmpty()) {
                throw new BusinessException(404, "服务商未返回任何可用模型");
            }
            return models;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "拉取模型列表失败：" + e.getMessage());
        }
    }

    /**
     * 根据服务商和接口地址推导模型列表 API URL。
     * 各 OpenAI 兼容服务商的模型列表路径一般为 baseUrl + /models。
     */
    private String deriveModelsUrl(String provider, String endpoint) {
        // 优先从接口地址推导：去掉 /chat/completions 后缀，追加 /models
        if (endpoint != null && !endpoint.isBlank()) {
            String base = endpoint;
            int idx = base.indexOf("/chat/completions");
            if (idx > 0) {
                return base.substring(0, idx) + "/models";
            }
            idx = base.indexOf("/chatcompletion");
            if (idx > 0) {
                return base.substring(0, idx) + "/models";
            }
        }
        // 回退到服务商默认地址
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case "openai" -> "https://api.openai.com/v1/models";
            case "deepseek" -> "https://api.deepseek.com/models";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1/models";
            case "moonshot" -> "https://api.moonshot.cn/v1/models";
            case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4/models";
            case "baichuan" -> "https://api.baichuan-ai.com/v1/models";
            case "minimax" -> "https://api.minimax.chat/v1/models";
            default -> null;
        };
    }
}
