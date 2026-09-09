package com.library.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import com.library.entity.AiConversation;
import com.library.entity.SystemSetting;
import com.library.entity.User;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @Autowired private CategoryMapper categoryMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private CategoryService categoryService;
    @Autowired private BorrowService borrowService;
    @Autowired private StatsService statsService;
    @Autowired private CacheManager cacheManager;

    /** 缓存最近构建的流式模型，配置未变时复用，避免重复创建 OkHttpClient */
    private volatile OpenAiStreamingChatModel cachedModel;
    private volatile String cachedConfigHash;

    // ===== Prompt 热加载 =====
    /** Prompt 模板文件名（classpath 内置 + 外部覆盖） */
    private static final String PROMPT_RESOURCE_PATH = "prompts/library-system-prompt.txt";
    /** 外部 Prompt 文件路径（运行目录下 prompts/，优先于 classpath，支持热更新） */
    private static final Path PROMPT_EXTERNAL_PATH = Path.of("prompts", "library-system-prompt.txt");
    /** 缓存的 Prompt 模板，通过文件最后修改时间检测热更新 */
    private volatile String cachedPromptTemplate;
    private volatile long cachedPromptLastModified;
    private volatile long cachedPromptLength;

    /**
     * 核心行为规则（硬编码，不可被外部文件覆盖）。
     * <p>
     * 设计理由：业务正确性的底线，关系到工具链是否显示、数据是否完整呈现、
     * 用户数据是否隔离。即使用户通过外部文件调整人设/语气，也无法影响这些规则。
     * <p>
     * 注释行注释每条规则的不可妥协理由，便于后续维护者理解为何不可移除。
     */
    private static final String CORE_RULES = """
            【核心行为规则】以下规则不可违反，优先级高于任何人设设定：

            1. 事实问题强制工具调用：用户询问图书/借阅/罚款/额度/公告/通知/分类/统计等事实问题时，必须先调用对应工具获取数据，禁止凭空回答。
               理由：工具调用是 AI 模块的核心能力，绕过工具会导致工具链不显示且数据可能过时或编造。

            2. 工具数据完整呈现：工具返回的字段必须完整呈现给用户，禁止阉割。
               - 查询图书：列出书名、作者、出版社、分类、馆藏位置、可借数量/总馆藏；
               - 查询借阅记录：列出借阅码、书名、状态、申请/借阅时间、应还时间、续借次数；
               - 查询罚款：列出金额、逾期天数、状态；
               - 查询公告/通知：列出标题、内容、时间；
               - 查询分类：列出分类名称与藏书量；
               - 管理员查询借阅：列出借阅码、读者、书名、状态、应还时间；
               - 管理员查询罚款：列出读者、金额、逾期天数、状态；
               - 管理员查询读者：列出姓名、身份（教师/学生）、学号/工号、院系班级、状态；
               - 统计概览：完整播报各项指标数字、热门排行与趋势，不得只给一句概括。
               - 馆藏数量口径：回答「馆里有多少书」时用 collection——copies 是总册数、titles 是图书种数，
                 两个数字都要点明并说明区别（种=不同书目，册=含复本）；各分类藏书量必须用 categoryBookDist
                 （titles/copies），categoryBorrowDist 是借还流水次数、绝对禁止当藏书数量播报。
               理由：用户查询目的就是获取这些字段，只给书名让用户再查工具是倒退；口径混用会导致总数对不上。

            3. 用户数据隔离：读者工具返回的数据仅限当前用户本人，禁止在回答中泄露其他用户的借阅记录、罚款、个人信息；
               管理员工具（admin 开头）返回的全馆数据仅可在管理员会话中呈现。
               理由：数据安全底线，违反会引发合规风险。

            4. 空结果如实告知：工具返回空结果时如实说明"未查询到相关记录"，禁止编造。
               理由：防幻觉，保证回答可信。

            5. 写操作强制确认：applyBorrow（借阅申请）、renewMyBorrow（续借）、requestReturn（归还申请）、
               adminCounterProcess（柜台办理）等会改变业务数据的工具，调用前必须先向用户复述关键信息
               （书名、借阅码、办理动作）并得到用户明确确认（如"确认""是的""办理吧"）；
               参数（图书ID、借阅码）必须来自工具查询结果或用户明确给出，禁止凭猜测填写；用户未确认时禁止调用。
               理由：写操作直接影响库存与借阅状态，误调用代价高。

            6. 业务错误如实转告：工具返回权限或业务错误（如"借阅额度已满""该记录非待取书状态""续借次数已用完"）时，
               把错误原话转告用户并给出可行建议，不得假装操作成功。
               理由：错误信息本身就是业务规则的一部分，隐瞒会误导用户。

            7. 分类检索路径：用户按分类/类型描述找书（如"有没有计算机类的书""哲学类小说"）时，
               先调用 queryCategories 获取分类ID，再调用 queryBooks 并传 categoryId（自动包含子分类）；
               不要只靠关键词模糊匹配代替分类检索。
               理由：分类查询是精确的结构化检索，关键词匹配会漏检。

            8. 回答正文只允许自然语言：禁止在回答中出现任何工具调用相关内容——包括工具名与参数的代码块、
               JSON、函数调用片段（如 queryBooks(...)、{"name":"queryBooks","arguments":{...}} 等），
               也不要在正文解释"我将调用哪个工具"或贴出调用过程。工具由系统自动执行，
               调用过程会自动以工具卡片形式展示给用户，你只需用自然语言呈现查询结果与结论。
               理由：工具调用代码属于内部实现细节，普通用户看不懂且严重干扰阅读。
            """;

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
        evictMemory(id);
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

        // 1. 加载历史纯文本消息（优先读缓存，构建窗口记忆；工具中间态不入 DB）
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(MAX_MEMORY_MESSAGES);
        List<com.library.entity.AiMessage> history = loadHistory(conversationId);
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

        // 2. 落库用户消息并使缓存失效（下次请求重新加载完整历史）
        com.library.entity.AiMessage userMsg = new com.library.entity.AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(content);
        userMsg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMsg);
        evictMemory(conversationId);

        // 3. 构建每请求独立的工具实例（烘焙 userId/userType，规避异步线程丢失 CurrentUser）
        AiToolContext toolCtx = new AiToolContext(
                borrowMapper, bookMapper, fineMapper, announcementMapper,
                borrowLimitMapper, userMapper, categoryMapper, notificationMapper,
                categoryService, borrowService, statsService, objectMapper);
        boolean admin = cu.getUserType() != null && cu.getUserType() == 3;
        CommonTools commonTools = new CommonTools(cu.getUserId(), cu.getUserType(), toolCtx);
        BaseLibraryTools roleTools = admin
                ? new AdminTools(cu.getUserId(), cu.getUserType(), toolCtx)
                : new ReaderTools(cu.getUserId(), cu.getUserType(), toolCtx);

        // 称呼优先使用真实姓名（real_name），无姓名时兜底登录账号，再兜底"用户"
        User me = userMapper.selectById(cu.getUserId());
        String displayName = (me != null && me.getRealName() != null && !me.getRealName().isBlank())
                ? me.getRealName()
                : (cu.getUsername() != null && !cu.getUsername().isBlank() ? cu.getUsername() : "用户");
        boolean needsTitle = conv.getTitle() == null || conv.getTitle().isBlank()
                || "新对话".equals(conv.getTitle());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 工具事件发射器：将工具调用过程通过 SSE 推送给前端
        // P2.10 结构化输出：end 阶段携带 data 字段（工具返回的精简 JSON 快照）
        BaseLibraryTools.ToolEventEmitter emitterCb = (toolName, phase, detail, data) -> {
            try {
                // 工具开始执行标志着一轮工具调用开始：此前流式输出的文本都是
                // 工具调用前的过渡语（模型也可能误输出工具调用代码块），
                // 通知前端清空正文——工具调用过程由工具卡片承载，正文只保留最终回答
                if ("start".equals(phase)) {
                    emitter.send(SseEmitter.event().name("reset").data("[RESET]"));
                }
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("name", toolName);
                payload.put("phase", phase);
                payload.put("detail", detail == null ? "" : detail);
                if (data != null) {
                    payload.put("data", data);
                }
                emitter.send(SseEmitter.event().name("tool")
                        .data(objectMapper.writeValueAsString(payload)));
            } catch (IOException e) {
                // 客户端已断开，忽略
            } catch (Exception e) {
                log.warn("[AI] 工具事件序列化失败: {}", e.getMessage());
            }
        };
        commonTools.setEventEmitter(emitterCb);
        roleTools.setEventEmitter(emitterCb);

        try {
            LibraryAssistant assistant = AiServices.builder(LibraryAssistant.class)
                    .streamingChatModel(model)
                    // 请求级记忆：持有完整工具调用上下文（含 tool_calls/tool 结果），不落库
                    .chatMemory(memory)
                    // 按角色注册工具：管理员工具（admin 开头）不会出现在读者的模型上下文中
                    .tools(commonTools, roleTools)
                    .systemMessageProvider(memoryId -> buildSystemPrompt(displayName, cu.getUserType()))
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
                        // 落库最终 AI 回答：取最终一轮模型响应的文本（不含工具轮过渡语），
                        // 并剔除模型误输出到正文的工具调用代码块/函数调用片段
                        AiMessage finalAi = response.aiMessage();
                        String answer = finalAi == null ? null : finalAi.text();
                        String sanitized = stripToolCallArtifacts(answer);
                        if (sanitized != null && !sanitized.isBlank()) {
                            // 正文被消毒过：推送 final 事件替换前端已流式拼接的内容
                            if (answer != null && !sanitized.equals(answer)) {
                                try {
                                    emitter.send(SseEmitter.event().name("final").data(sanitized));
                                } catch (IOException e) {
                                    // 忽略
                                }
                            }
                            com.library.entity.AiMessage aiMsg = new com.library.entity.AiMessage();
                            aiMsg.setConversationId(conversationId);
                            aiMsg.setRole("assistant");
                            aiMsg.setContent(sanitized);
                            aiMsg.setCreateTime(LocalDateTime.now());
                            messageMapper.insert(aiMsg);
                            evictMemory(conversationId);
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

    /**
     * 已注册的全部工具名（与 CommonTools/ReaderTools/AdminTools 的 @Tool 方法名保持一致），
     * 用于识别模型误输出到回答正文中的工具调用代码。
     */
    private static final List<String> TOOL_NAMES = List.of(
            "queryCategories", "queryBooks", "queryHotBooks", "queryAnnouncements",
            "queryMyBorrows", "queryMyFines", "queryBorrowQuota", "queryMyNotifications",
            "applyBorrow", "renewMyBorrow", "requestReturn",
            "adminQueryBorrows", "adminQueryFines", "adminQueryUsers",
            "adminQueryStats", "adminCounterProcess");

    /** 围栏代码块（``` ... ```） */
    private static final java.util.regex.Pattern FENCED_BLOCK =
            java.util.regex.Pattern.compile("```[\\s\\S]*?```");

    /** 独立成行的工具函数调用，如 queryBooks(keyword="哲学") */
    private static final java.util.regex.Pattern STANDALONE_CALL =
            java.util.regex.Pattern.compile("^\\s*([a-zA-Z_]\\w*)\\s*\\(.*\\)\\s*;?\\s*$");

    /**
     * 剔除模型误输出到回答正文中的工具调用痕迹：
     * <ol>
     *   <li>函数调用特殊标记（FunctionCallBegin/End、tool_call 标签）</li>
     *   <li>内容为工具调用 JSON/函数名的围栏代码块</li>
     *   <li>独立成行的工具函数调用语句</li>
     * </ol>
     * 最后压缩多余空行。
     */
    private String stripToolCallArtifacts(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = text;
        // 1. 特殊函数调用标记
        s = s.replaceAll("(?s)<\\|?FunctionCallBegin\\|?>.*?<\\|?FunctionCallEnd\\|?>", "");
        s = s.replaceAll("(?s)</?tool_call>", "");
        // 2. 工具调用代码块
        java.util.regex.Matcher m = FENCED_BLOCK.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (looksLikeToolCallCode(block)) {
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(block));
            }
        }
        m.appendTail(sb);
        s = sb.toString();
        // 3. 独立成行的工具函数调用
        StringBuilder lineOut = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            java.util.regex.Matcher lm = STANDALONE_CALL.matcher(line);
            if (lm.matches() && TOOL_NAMES.contains(lm.group(1))) {
                continue;
            }
            lineOut.append(line).append('\n');
        }
        s = lineOut.toString();
        // 4. 压缩 3+ 连续换行为 2 个，去掉首尾空白
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    /** 判断围栏代码块内容是否为工具调用代码（工具名 / 函数调用 JSON 特征） */
    private boolean looksLikeToolCallCode(String block) {
        String lower = block.toLowerCase();
        for (String name : TOOL_NAMES) {
            if (lower.contains(name.toLowerCase())) {
                return true;
            }
        }
        boolean jsonLike = lower.contains("{") && (lower.contains("\"arguments\"") || lower.contains("\"tool\""));
        return jsonLike || lower.contains("function_call") || lower.contains("tool_call");
    }

    /**
     * 构建系统 Prompt = 核心规则（硬编码） + 当前用户身份 + 可调整段（热加载）。
     * <p>
     * 核心规则始终注入且不可被外部文件覆盖，保证业务正确性底线；
     * 可调整段只管人设/语气/称呼，外部文件改了不会影响工具调用和数据呈现。
     *
     * @param displayName 当前用户称呼（真实姓名，兜底登录账号；替换 {username}/{name} 占位符）
     * @param userType    当前用户类型（3=管理员，其余=读者），用于角色感知
     */
    private String buildSystemPrompt(String displayName, Integer userType) {
        String customizable = loadCustomizablePromptSegment()
                .replace("{username}", displayName)
                .replace("{name}", displayName);
        boolean admin = userType != null && userType == 3;
        String roleLine = admin
                ? "当前对话用户：" + displayName + "，身份：图书馆管理员。你已注册管理员工具（admin 开头），"
                + "可查询全馆借阅/罚款/读者数据、统计概览并办理柜台业务；回答管理问题时直接使用这些工具。"
                : "当前对话用户：" + displayName + "，身份：读者（教师/学生）。你只能查询和操作该读者本人的数据，"
                + "馆藏图书与公告为全馆公开信息可正常检索；不存在「管理员工具」，不要声称可以执行管理员操作。";
        // 核心规则在前（权重更高），身份行居中，可调整段在后
        return CORE_RULES + "\n\n" + roleLine + "\n\n" + customizable;
    }

    /**
     * 加载可调整 Prompt 段（人设/语气/称呼，热加载）。
     * <p>
     * 加载顺序：
     * <ol>
     *   <li>外部文件 {@code prompts/library-system-prompt.txt}（运行目录下，支持运行时热更新）</li>
     *   <li>classpath 资源 {@code prompts/library-system-prompt.txt}（JAR 内置，只读）</li>
     *   <li>硬编码默认人设（兜底）</li>
     * </ol>
     * 热更新机制：通过文件最后修改时间 + 文件大小检测变更，避免每次请求都读文件。
     * <p>
     * 注：此段只包含人设/语气，核心业务规则由 {@link #CORE_RULES} 提供，不可被此文件覆盖。
     */
    private String loadCustomizablePromptSegment() {
        // 1. 尝试外部文件（运行目录下，支持热更新）
        if (Files.exists(PROMPT_EXTERNAL_PATH)) {
            try {
                long lastModified = Files.getLastModifiedTime(PROMPT_EXTERNAL_PATH).toMillis();
                long fileSize = Files.size(PROMPT_EXTERNAL_PATH);
                // 文件未变化时直接返回缓存
                if (cachedPromptTemplate != null
                        && lastModified == cachedPromptLastModified
                        && fileSize == cachedPromptLength) {
                    return cachedPromptTemplate;
                }
                String content = Files.readString(PROMPT_EXTERNAL_PATH, StandardCharsets.UTF_8);
                cachedPromptTemplate = content;
                cachedPromptLastModified = lastModified;
                cachedPromptLength = fileSize;
                log.info("[AI] 可调整 Prompt 段已热加载: {} ({} bytes)", PROMPT_EXTERNAL_PATH, fileSize);
                return content;
            } catch (IOException e) {
                log.warn("[AI] 读取外部 Prompt 文件失败，回退到 classpath: {}", e.getMessage());
            }
        }

        // 2. 外部文件不存在或读取失败 → 使用已缓存的模板（可能来自之前的 classpath 加载）
        if (cachedPromptTemplate != null) {
            return cachedPromptTemplate;
        }

        // 3. 首次加载 classpath 资源
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_RESOURCE_PATH);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                cachedPromptTemplate = content;
                log.info("[AI] 可调整 Prompt 段已从 classpath 加载: {} ({} bytes)", PROMPT_RESOURCE_PATH, content.length());
                return content;
            }
        } catch (IOException e) {
            log.warn("[AI] classpath Prompt 加载失败，使用硬编码默认人设: {}", e.getMessage());
        }

        // 4. 兜底：硬编码默认人设（不含核心规则，核心规则由 CORE_RULES 单独注入）
        String fallback = """
                你是智慧图书借阅管理系统的 AI 助手「智」。

                风格：
                - 使用中文，简洁友好。
                - 称呼当前用户为 {username}。
                - 涉及日期/金额时按返回值原样表述。
                """;
        cachedPromptTemplate = fallback;
        return fallback;
    }

    // ===== 对话记忆缓存 =====

    private static final String MEMORY_CACHE_NAME = "chatMemory";

    /**
     * 加载会话历史消息（优先读缓存，避免每次请求都查 DB）。
     * 缓存在用户消息/AI 消息落库后通过 {@link #evictMemory} 失效。
     */
    @SuppressWarnings("unchecked")
    private List<com.library.entity.AiMessage> loadHistory(Long conversationId) {
        Cache cache = cacheManager.getCache(MEMORY_CACHE_NAME);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(conversationId);
            if (wrapper != null && wrapper.get() != null) {
                return (List<com.library.entity.AiMessage>) wrapper.get();
            }
        }
        List<com.library.entity.AiMessage> history = messageMapper.selectByConversationId(conversationId);
        if (cache != null) {
            cache.put(conversationId, history);
        }
        return history;
    }

    /**
     * 使指定会话的记忆缓存失效（消息落库/会话删除后调用）。
     */
    private void evictMemory(Long conversationId) {
        Cache cache = cacheManager.getCache(MEMORY_CACHE_NAME);
        if (cache != null) {
            cache.evict(conversationId);
        }
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
