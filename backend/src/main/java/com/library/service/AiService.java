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
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * AI 对话服务：基于 LangChain4j 1.20 + OpenAI 兼容接口（DeepSeek/OpenAI 等），
 * 支持深度思考模式（reasoning_content 解析与回传由框架原生处理）。
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
    /**
     * M8：单次任务链允许的最大连续续跑轮数。
     * <p>
     * 确认成功后前端自动发起续跑，模型可能再次弹卡等待确认、确认后再次续跑——
     * 不设上限会形成「确认→续跑→确认」循环。3 轮覆盖常规多步任务链
     * （如查书→借书→续借→归还），超限后用户手动发消息即重置。
     */
    private static final int MAX_CONTINUATION_ROUNDS = 3;
    /** M8：续跑引导消息的落库前缀（连续续跑计数的识别标记） */
    private static final String CONTINUATION_PREFIX = "[系统续跑] ";
    /**
     * 单次对话允许的最大工具循环轮次（模型返回「含工具调用的响应」的次数）。
     * <p>
     * 不设置时 LangChain4j 默认允许 100 轮，模型若陷入「查了没结果→再查」的死循环
     * 会持续消耗 token 与时间直到框架抛异常。设上限后超限走优雅降级（见 {@link #isToolRoundLimitExceeded}）。
     * <p>
     * 取值 8 的依据：覆盖省赛演示主线的最大轮数（查额度→检索图书→确认→办理→推荐→汇总）。
     */
    private static final int MAX_TOOL_ROUNDS = 8;
    /**
     * M4：单条过渡语（thought）最大字符数。
     * <p>
     * M7 起 reset 已移除、正文保留全部叙述，thought 只是时间线上的摘要节点——
     * 保持短句即可，长文本会与正文重复刷屏。
     */
    private static final int THOUGHT_MAX_LEN = 60;
    /** SSE 超时时间（毫秒） */
    private static final long SSE_TIMEOUT_MS = 300_000L;
    /** 首条消息生成标题的最大字符数 */
    private static final int TITLE_MAX_LEN = 20;
    /** 模型调用超时（秒），避免长时间无响应卡死 SSE */
    // 模型流式调用总时长上限（OkHttp call timeout 覆盖整个流式过程，含深度思考阶段；
    // 60s 会在思考型模型长回复中途掐断流导致末尾断字，故放宽至 5 分钟，与 SSE 容器超时对齐）
    private static final long MODEL_TIMEOUT_SECONDS = 300L;
    /** RAG 来源回传给前端的单个片段摘要最大字符数 */
    private static final int RAG_SNIPPET_MAX_LEN = 120;

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
    @Autowired private LibraryConfigService libraryConfigService;
    @Autowired private RagIndexService ragIndexService;
    @Autowired private AiConfirmService aiConfirmService;
    @Autowired private AiBatchConfirmService aiBatchConfirmService;
    @Autowired private AiSessionAllowService aiSessionAllowService;
    @Autowired private NotificationService notificationService;
    @Autowired private AiAgentTraceService traceService;

    /** 缓存最近构建的流式模型，配置未变时复用，避免重复创建 OkHttpClient */
    private volatile OpenAiStreamingChatModel cachedModel;
    private volatile String cachedConfigHash;

    // 模型请求/响应日志开关（application.yml app.ai.log-requests/log-responses）
    // 完整 prompt 含用户借阅记录等个人信息，日志打开等于把 PII 写进日志文件——生产必须关闭
    @Value("${app.ai.log-requests:true}")
    private boolean logRequestsEnabled;
    @Value("${app.ai.log-responses:true}")
    private boolean logResponsesEnabled;

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

            2. 结果必须来自本次工具真实返回：任何数据的查询或修改，都必须实际调用工具并以本次工具返回的数据为准，
               禁止从对话上下文推断、复用历史查询结果或编造结论。
               - 上下文中出现过某条记录（借阅码、书名、状态等）≠ 该记录已被操作或可被操作；
                 即使刚刚查询过，执行取书/归还/归还确认/续借/罚款缴纳/增删改时仍必须重新调用对应工具；
               - 未调用写操作工具并拿到返回前，禁止说出"办理成功""已归还""已缴纳""已取消"等结果性表述；
               - 未调用查询工具前，禁止报出任何数量、状态或明细（数据随时变化，必须现查）。
               - 写工具返回 awaitingConfirm=true 时，说明授权卡已弹出、正在等待用户点击，
                 此时不要重复调用该写工具（会重复弹卡），应结束本轮回复并告知用户确认。
               理由：AI 曾凭上下文脑补"归还成功"造成虚假承诺，数据时效与真实性只能由工具调用保证。

            3. 工具数据完整呈现与卡片去重：工具返回的关键字段必须让用户看到，禁止阉割；
               但呈现方式优先交给系统自动生成的可视化卡片。
               - 系统会把下列工具的返回自动渲染成可视化卡片，展示在对话中你的回复上方：
                 queryBooks（图书卡）、queryHotBooks（排行榜）、queryMyBorrows / adminQueryBorrows（借阅记录表）、
                 queryMyFines / adminQueryFines（罚款表）、queryCategories（分类分布）、queryAnnouncements（公告列表）、
                 queryBorrowQuota（额度条）、adminQueryUsers（用户表）、adminQueryStats（统计看板）。
                 这些工具返回后，你的正文不要再逐条罗列卡片已展示的数据（不要重复书单、借阅码、金额明细表），
                 只做简短总结与下一步引导（如"为你找到 N 本相关图书，见上方卡片，想借哪本告诉我"）；
                 卡片未覆盖的字段（如推荐理由、注意事项）仍需说明；
               - 未生成可视化卡片的查询，正文需完整呈现关键字段：
                 查询图书：书名、作者、出版社、分类、馆藏位置、可借数量/总馆藏；
                 查询借阅记录：借阅码、书名、状态、申请/借阅时间、应还时间、续借次数；
                 查询罚款：金额、逾期天数、状态；
                 查询公告/通知：标题、内容、时间；
                 查询分类：分类名称与藏书量；
                 管理员查询借阅：借阅码、读者、书名、状态、应还时间；
                 管理员查询罚款：读者、金额、逾期天数、状态；
                 管理员查询读者：姓名、身份（教师/学生）、学号/工号、院系班级、状态；
                 统计概览：完整播报各项指标数字、热门排行与趋势，不得只给一句概括。
               - 馆藏数量口径：回答「馆里有多少书」时用 collection——copies 是总册数、titles 是图书种数，
                 两个数字都要点明并说明区别（种=不同书目，册=含复本）；各分类藏书量必须用 categoryBookDist
                 （titles/copies），categoryBorrowDist 是借还流水次数、绝对禁止当藏书数量播报。
               理由：卡片已把数据可视化的情况下再逐条文字复述是重复噪声，伤害阅读体验；
               而没有卡片时只给一句概括会丢失用户查询所需的字段；口径混用会导致总数对不上。

            4. 用户数据隔离：读者工具返回的数据仅限当前用户本人，禁止在回答中泄露其他用户的借阅记录、罚款、个人信息；
               管理员工具（admin 开头）返回的全馆数据仅可在管理员会话中呈现。
               理由：数据安全底线，违反会引发合规风险。

            5. 空结果如实告知：工具返回空结果时如实说明"未查询到相关记录"，禁止编造。
               理由：防幻觉，保证回答可信。

            6. 写操作由系统授权卡把关：applyBorrow（借阅申请）、renewMyBorrow（续借）、requestReturn（归还申请）、
               adminCounterProcess（柜台办理）等会改变业务数据的工具，请直接调用它们——
               系统会在执行前拦截并弹出授权卡，由用户点击确认后才真正执行，你无需自行向用户索要确认。
               你的职责是：
               - 调用前把关键信息（书名、ISBN、借阅码）复述给用户，让用户知道要确认什么；
               - 参数必须来自工具查询结果或用户明确给出，禁止凭猜测填写；
               - 工具返回 awaitingConfirm=true 表示授权卡已弹出、正在等待用户点击，
                 此时你应停止后续操作并结束本轮回复，简单告知用户「已发起，请确认」；
               - 用户未在授权卡上确认前，禁止说出"办理成功""已归还""已缴纳""已取消"等结果性表述；
               - applyBorrow 必须传用户确认的书名；queryBooks 结果里带了 isbn 就一并传入——ISBN 唯一，
                 是唯一能确定"借出的就是这一本"的凭据，只凭书名会有同名多版本借错的风险。
               - 若 applyBorrow 返回 candidates 候选清单（书名命中多本），必须把候选念给用户选定后再以选定书名办理，
                 禁止自行挑选；办理成功后必须把返回的 bookTitle 复述给用户核对。
               理由：写操作由代码级门禁强制拦截，模型无权绕过；授权卡是用户唯一能看到参数并出决策的入口，
               模型提前宣称结果会造成「界面显示未办理、模型却说已办理」的虚假承诺。
               注：不要因为「还没拿到用户确认」就不调用写工具——直接调用即可，拦截与确认由系统完成。

            6b. 写操作必须真的调用工具，不得只用语言答复：用户表达借阅/续借/归还/催还/柜台办理意图时，
                必须实际调用对应写工具（applyBorrow / renewMyBorrow / requestReturn /
                adminUrgeOverdue / adminCounterProcess / adminCounterProcessBatch），
                不要只回答「好的，我来帮你办理」就结束，也不要只查书就停下。
                正确流程是：先查询工具核实记录，再立即调用对应写工具提交办理，
                系统会自动弹出授权卡，由用户在卡片上点击确认。
                特别注意：说「请在授权卡上确认」之前必须真的调用了写工具（授权卡是系统
                在你调用写工具后自动弹出的，你不调用就不会有任何卡弹出）。
                例外：仅当工具返回明确的业务错误（如额度已满、该书不可借、找不到书目）时才停下并转告用户。

            7. 业务错误如实转告：工具返回权限或业务错误（如"借阅额度已满""该记录非待取书状态""续借次数已用完"）时，
               把错误原话转告用户并给出可行建议，不得假装操作成功。
               理由：错误信息本身就是业务规则的一部分，隐瞒会误导用户。

            8. 分类检索路径：用户按分类/类型描述找书（如"有没有计算机类的书""哲学类小说"）时，
               先调用 queryCategories 获取分类ID，再调用 queryBooks 并传 categoryId（自动包含子分类）；
               不要只靠关键词模糊匹配代替分类检索。
               理由：分类查询是精确的结构化检索，关键词匹配会漏检。

            9. 回答正文只允许自然语言：禁止在回答中出现任何工具调用相关内容——包括工具名与参数的代码块、
               JSON、函数调用片段（如 queryBooks(...)、{"name":"queryBooks","arguments":{...}} 等），
               也不要在正文解释"我将调用哪个工具"或贴出调用过程。工具由系统自动执行，
               调用过程会自动以工具卡片形式展示给用户，你只需用自然语言呈现查询结果与结论。
               理由：工具调用代码属于内部实现细节，普通用户看不懂且严重干扰阅读。

            10. 知识库资料优先且必须标注来源：当本次请求的上下文中提供了「馆方知识库检索到的资料片段」时，
                制度/规章类问题（借阅规则、逾期罚款标准、开放时间、读者须知、办证流程等）必须以这些资料为依据作答，
                并在回答末尾用「（来源：《文档名》）」标注所依据的文档；资料未覆盖或不足以回答该问题时，
                如实说明「馆方知识库中暂未收录相关内容」，不得凭常识猜测或用其他资料顶替。
                资料中的数量、状态类数字（在馆数、可借数、当前罚款金额等）只是录入时的快照，
                一律不得当作实时数据播报，此类问题必须按规则 2 调用工具现查。
                理由：制度性内容最容易被模型用通用常识替代，来源标注才能追溯；资料是静态文本，当实时数据用会答错。

            11. 推荐/找书类请求必须调用工具：当用户请求「推荐书籍」「有什么好书」「帮我找书」「大家都在看什么」
                「最近借得最多的书」「热门/畅销的书」等需要给出具体图书的请求时，必须调用查询工具获取真实馆藏数据
                （无明确主题偏好时调 queryHotBooks 取热门排行；有明确分类/主题时先 queryCategories 再 queryBooks），
                禁止凭空编造书单、禁止照搬通用书店畅销榜或训练数据里的书名。
                理由：推荐也属于馆藏事实查询，只有工具返回的数据才能驱动前端「AI 检索结果」视图与真实库存展示。

            12. 回答正文只允许最终答复，禁止输出思考/推理过程：你的内部规划、核对步骤、自我提醒
                （无论中文还是英文，如 "I'll first verify..." / "Let me check..." / "First, ... then ..."）
                一律不得出现在回答正文里——用户只看得到你的正文，推理过程属于内部活动。
                用户与你的对话语言是中文，回答正文必须使用中文。
                理由：正文里混入推理旁白（尤其英文）会被用户当成回答的一部分，严重干扰阅读且显得失控。
            13. 授权卡的生命周期铁律：写操作只有收到「[系统回执] … 结果：成功」才算完成。
                授权卡弹出（未确认）≠ 操作已完成；没有任何回执时，严禁向用户宣称任何写操作
                「已办完/已成功」。若用户要求继续某任务，先核对历史回执确认哪些项真正成功，
                只为「尚无成功回执的剩余项」发起授权；对已有成功回执的项绝不重弹授权卡。
                汇总办理结果时必须逐项列出回执中的单号/状态，不允许凭推测总结。
                理由：模型曾出现未调用工具就宣称三本书全部借出的幻觉——实际只成功两本，严重误导用户。
            """;

    /**
     * AI 服务接口（流式返回 TokenStream）。
     * 单 String 参数由 LangChain4j 自动识别为用户消息。
     */
    public interface LibraryAssistant {
        TokenStream chat(String userMessage);
    }

    // 会话管理

    /**
     * 设置会话写操作权限档位（M7）。
     * <p>
     * 规则：仅会话本人可改；auto 档仅管理员可设置（读者端强制逐笔确认）；
     * 非法档位名直接拒绝而非静默回退，避免用户以为切成功实际没切。
     */
    public void setPermissionMode(Long conversationId, Long userId, Integer userType, String mode) {
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!conv.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作该会话");
        }
        PermissionMode target = PermissionMode.of(mode);
        if (!target.code().equalsIgnoreCase(mode == null ? "" : mode.trim())) {
            throw new BusinessException(400, "未知权限档位：" + mode + "（可选 confirm/batch/auto）");
        }
        if (target == PermissionMode.AUTO && (userType == null || userType != 3)) {
            throw new BusinessException(403, "自动执行档仅管理员可用");
        }
        conversationMapper.updatePermissionMode(conversationId, target.code());
    }

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
        // M8：会话删除时清空其「始终允许」白名单
        aiSessionAllowService.clearConversation(id);
    }

    // 流式对话

    /**
     * 流式对话：返回 SseEmitter。
     * <p>
     * 事件流：
     * <ul>
     *   <li>{@code delta} —— 增量文本片段</li>
     *   <li>{@code rag}   —— 本轮检索注入的知识库来源（JSON 数组：[{docTitle, snippet}]，早于 delta）</li>
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
        return chat(conversationId, content, false);
    }

    /**
     * 流式对话（支持确认续跑）。
     *
     * @param continuation M8：true 表示由前端在授权确认成功后自动发起的续跑轮。
     *                     续跑轮限制连续次数（默认 3），防止「确认→续跑→再弹卡→确认」
     *                     无限循环消耗 token；超限走普通对话路径让用户手动继续。
     */
    public SseEmitter chat(Long conversationId, String content, boolean continuation) {
        CurrentUser cu = CurrentUser.get();
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!conv.getUserId().equals(cu.getUserId())) {
            throw new BusinessException(403, "无权操作该会话");
        }
        // M8 续跑循环防护：统计历史里最近的连续续跑引导消息数，达到上限拒绝本轮续跑
        if (continuation) {
            long recentContinuations = countTrailingContinuations(conversationId);
            if (recentContinuations >= MAX_CONTINUATION_ROUNDS) {
                throw new BusinessException(429, "续跑次数已达上限，请直接输入消息继续");
            }
        }

        AiConfig cfg = loadConfig();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new BusinessException(400, "AI 助手未启用：请管理员在「系统设置 · AI 渠道」中添加并启用渠道");
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
        // M8：续跑轮的引导语加前缀落库——模型历史可见其上下文意图；
        // countTrailingContinuations 靠该前缀统计连续续跑次数
        com.library.entity.AiMessage userMsg = new com.library.entity.AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(continuation ? CONTINUATION_PREFIX + content : content);
        // M10：续跑引导语是内部机制消息，对 UI 隐藏（模型历史仍可见）
        if (continuation) {
            userMsg.setVisibility("hidden");
        }
        userMsg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMsg);
        evictMemory(conversationId);

        // M6a：本次对话的轨迹收集器（工具序列、决策旁白、门禁/降级标记、耗时、token）
        AiAgentTraceService.Collector trace = traceService.newCollector(
                conversationId, cu.getUserId(), cu.getUserType(), content);

        // 3. 构建每请求独立的工具实例（烘焙 userId/userType，规避异步线程丢失 CurrentUser）
        AiToolContext toolCtx = buildToolContext();
        boolean admin = cu.getUserType() != null && cu.getUserType() == 3;
        // M7：会话权限档位。读者端强制逐笔确认（动的是用户本人资产，不做自动执行）；
        // 管理员可按会话切 batch/auto，未配置时回退 confirm
        PermissionMode mode = admin
                ? PermissionMode.of(conv.getPermissionMode())
                : PermissionMode.CONFIRM;
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
        // M10：登记会话级通道，确认执行后可按会话推送 tool_result（授权卡原地更新）
        AiSseSessionRegistry.register(conversationId, emitter);
        // 流结束/超时/出错时注销，避免推送到已关闭的通道
        Runnable unregister = () -> AiSseSessionRegistry.unregister(conversationId, emitter);
        emitter.onCompletion(unregister);
        emitter.onTimeout(unregister);

        // <think> 标签兜底过滤器：部分本地模型/中转网关不返回 reasoning_content 字段，
        // 而是把思维链以 <think>...</think> 包裹在正文流中输出。此过滤器把标签内
        // 内容改道为 thinking 事件（与字段路径归一），正文只保留最终回答。
        ThinkTagStreamFilter thinkFilter = new ThinkTagStreamFilter(emitter);

        // 本次回复实际执行完成的工具调用（按顺序记录工具名），随助手消息落库为摘要
        java.util.List<String> toolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();

        // 本轮产生可视化卡片的工具快照（[{tool,type,data,createTime}]），随助手消息落库，
        // 前端加载历史消息时据此重建内联生成式卡片。快照是查询时点数据，历史卡片由前端标注生成时间。
        java.util.List<Map<String, Object>> toolSnapshots = new java.util.concurrent.CopyOnWriteArrayList<>();

        // 本轮工具循环轮次：与框架的计数口径严格一致。
        // <p>
        // 框架（LangChain4j 1.22）在每次「模型返回含工具调用的响应」时递减计数，
        // 且该递减点与 {@code onIntermediateResponse} 回调一一对应（两者在同一个响应处理点触发）。
        // 因此这里用 onIntermediateResponse 计数，而不是数工具调用次数——
        // 一轮模型往返可能并发调用多个工具，按工具数计数会低估轮次，导致熔断阈值失真。
        java.util.concurrent.atomic.AtomicInteger toolRounds = new java.util.concurrent.atomic.AtomicInteger(0);

        // M4：工具轮的过渡语缓冲。
        // <p>
        // 模型在调用工具前常会说一段过渡语（「我先查一下馆藏…」），这些文本原本随 reset 被清空丢弃，
        // 导致用户/评委看不到 Agent 的决策链。此处把过渡语累积起来，在下一个工具开始时
        // 改道为 thought 事件发出，由前端渲染成步骤时间线。
        // <p>
        // 用 StringBuilder 而非并发容器：写入端（onPartialResponse）与读取端（工具事件回调）
        // 都跑在同一条对话的流式线程上，且此处不做跨请求共享，无需额外同步。
        StringBuilder thoughtBuffer = new StringBuilder();

        // 工具事件发射器：将工具调用过程通过 SSE 推送给前端
        // 结构化输出：end 阶段携带 data 字段（工具返回的精简 JSON 快照）
        BaseLibraryTools.ToolEventEmitter emitterCb = (toolName, phase, detail, data) -> {
            try {
                // 工具开始执行标志着一轮工具调用开始。
                // M7 缺字修复：不再发送 reset 清空正文。此前工具开始时清空正文、
                // 工具返回后模型从「它认为自己已说到哪」的断点续写，导致气泡开头
                // 缺一段（被抹掉的字只以截断旁白留在时间线 thought 节点里）。
                // 改为保留正文原样：模型续写后自然拼成完整句子；过渡语摘要仍改道
                // thought 事件供时间线展示（截短避免与正文重复刷屏）。
                if ("start".equals(phase)) {
                    emitPendingThought(emitter, thoughtBuffer, trace);
                }
                // end 阶段计入摘要（成功执行完的工具；error 阶段不计）
                if ("end".equals(phase)) {
                    toolCalls.add(toolName);
                    // M6a：工具序列记入轨迹
                    trace.recordTool(toolName);
                    // 快照带可视化 type 时收集落库（历史消息重建内联卡片用）
                    if (data != null && data.contains("\"type\"")) {
                        try {
                            Map<?, ?> snap = objectMapper.readValue(data, Map.class);
                            if (snap.get("type") instanceof String genType && !genType.isBlank()) {
                                Map<String, Object> rec = new java.util.LinkedHashMap<>();
                                rec.put("tool", toolName);
                                rec.put("type", genType);
                                rec.put("data", snap);
                                rec.put("createTime", LocalDateTime.now().toString());
                                toolSnapshots.add(rec);
                            }
                        } catch (Exception e) {
                            log.warn("[AI] 快照解析失败 tool={}: {}", toolName, e.getMessage());
                        }
                    }
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

        // 写操作授权回调：写工具被门禁拦截时（不执行），改为向用户发起授权请求。
        // 令牌在服务端登记，客户端只拿到不透明 token，确认时回传 token 而非参数——参数无法被篡改。
        BaseLibraryTools.ConfirmRequestHandler confirmCb = (toolName, title, rows, args) -> {
            // 令牌先在 try 外登记占位：推送失败时 catch 中可拿到它并作废，
            // 避免「前端没收到授权卡、令牌却在服务端存活 60 秒」的悬挂状态
            String token = null;
            try {
                // M4：授权请求也是工具轮的一部分，先把过渡语发出，再推授权卡，
                // 保证时间线顺序为「思考 → 授权请求」
                emitPendingThought(emitter, thoughtBuffer, trace);
                // M6a：门禁触发记入轨迹（安全事件的量化依据）
                trace.markGate(toolName);
                token = aiConfirmService.issue(cu.getUserId(), conversationId, toolName, args, title, rows);
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("token", token);
                payload.put("tool", toolName);
                payload.put("title", title);
                payload.put("rows", rows == null ? List.of() : rows);
                payload.put("ttlSeconds", 60);
                // M8：本会话「始终允许」开关，前端渲染为授权卡上的勾选项
                payload.put("sessionAllow", true);
                emitter.send(SseEmitter.event().name("confirm")
                        .data(objectMapper.writeValueAsString(payload)));
            } catch (IOException e) {
                // 客户端已断开：作废令牌，用户重发消息后需重新发起授权
                if (token != null) {
                    aiConfirmService.revoke(token);
                    log.warn("[AI] 授权卡推送失败，令牌已作废 tool={}", toolName);
                }
            } catch (Exception e) {
                if (token != null) {
                    aiConfirmService.revoke(token);
                    log.warn("[AI] 授权卡推送失败，令牌已作废 tool={}: {}", toolName, e.getMessage());
                }
            }
        };
        commonTools.setConfirmHandler(confirmCb);
        roleTools.setConfirmHandler(confirmCb);

        // M7：批量授权收集器（batch 档位）。本轮被拦截的写操作在此累积，
        // onCompleteResponse 时合并为一张批量卡（batch_confirm 事件）统一请求确认
        java.util.List<AiBatchConfirmService.BatchItem> batchItems =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        BaseLibraryTools.BatchCollector batchCb = (toolName, title, rows, args) -> {
            trace.markGate(toolName);
            batchItems.add(new AiBatchConfirmService.BatchItem(toolName, title,
                    rows == null ? List.of() : rows, args));
        };
        commonTools.setBatchCollector(batchCb);
        roleTools.setBatchCollector(batchCb);
        commonTools.setPermissionMode(mode);
        roleTools.setPermissionMode(mode);
        // M8：会话白名单查询（授权卡「本会话始终允许」的放行依据）
        BaseLibraryTools.SessionAllowChecker allowCb =
                (convId, toolName) -> aiSessionAllowService.isAllowed(convId, toolName);
        commonTools.setSessionAllowChecker(allowCb);
        roleTools.setSessionAllowChecker(allowCb);
        commonTools.setConversationId(conversationId);
        roleTools.setConversationId(conversationId);

        try {
            // RAG：知识库启用且索引就绪时挂载检索增强；retriever 每请求构造以携带当前用户的可见范围
            RetrievalAugmentor augmentor = buildAugmentor(admin);

            AiServices<LibraryAssistant> serviceBuilder = AiServices.builder(LibraryAssistant.class)
                    .streamingChatModel(model)
                    // 请求级记忆：持有完整工具调用上下文（含 tool_calls/tool 结果），不落库
                    .chatMemory(memory)
                    // 按角色注册工具：管理员工具（admin 开头）不会出现在读者的模型上下文中
                    .tools(commonTools, roleTools)
                    // 工具循环上限：防止模型陷入死循环无限消耗 token，超限由 onError 转优雅降级。
                    // 用 maxToolCallingRoundTrips（1.15.0 起的新名）；maxSequentialToolsInvocations 已废弃。
                    .maxToolCallingRoundTrips(MAX_TOOL_ROUNDS)
                    .systemMessageProvider(memoryId -> buildSystemPrompt(displayName, cu.getUserType()));
            if (augmentor != null) {
                serviceBuilder.retrievalAugmentor(augmentor);
            }
            LibraryAssistant assistant = serviceBuilder.build();

            assistant.chat(content)
                    // 知识库来源回传：在调用 LLM 之前触发（早于 delta），前端据此展示「参考资料」
                    .onRetrieved(contents -> emitRagSources(emitter, contents))
                    .onIntermediateResponse(response -> {
                        // 工具循环轮次自增：与框架的计数递减点严格对应
                        // （框架在同一次「模型返回含工具调用的响应」时递减 maxToolCallingRoundTrips）
                        toolRounds.incrementAndGet();
                        // M6a：轮次同步写回轨迹收集器，落库取真实轮次而非按工具数估算
                        trace.setToolRounds(toolRounds.get());
                    })
                    .onPartialThinking(partial -> {
                        // 深度思考思维链增量（reasoning_content）：独立 thinking 事件推给前端展示，
                        // 不落库、不计入回答正文（与工具中间态一致，避免污染历史）
                        try {
                            String t = partial.text();
                            if (t != null && !t.isEmpty()) {
                                emitter.send(SseEmitter.event().name("thinking").data(t));
                            }
                        } catch (IOException e) {
                            // 客户端已断开，忽略
                        }
                    })
                    .onPartialResponse(partial -> {
                        try {
                            // 经 <think> 标签过滤器分流：标签内 → thinking 事件，标签外 → delta
                            String routed = thinkFilter.feed(partial);
                            if (routed != null && !routed.isEmpty()) {
                                // M4：同时累积到过渡语缓冲，等工具开始时改道为 thought 事件。
                                // 缓冲内容若最终未被发出（模型直接给最终答案），在 onCompleteResponse 清空即可。
                                thoughtBuffer.append(routed);
                                emitter.send(SseEmitter.event().name("delta").data(routed));
                            }
                        } catch (IOException e) {
                            // 客户端已断开，忽略
                        }
                    })
                    .onCompleteResponse(response -> {
                        // M4：最终回答完成，缓冲里剩余的文本是最终答案的一部分（不是过渡语），
                        // 直接丢弃，避免它在下一次工具调用时被误当作旁白发出。
                        thoughtBuffer.setLength(0);
                        // 冲刷 <think> 过滤器缓冲：pending 中滞留的末尾正文增量补发为 delta，
                        // 否则回答结尾会缺字（残片滞留缓冲从未被发出）
                        try {
                            String tail = thinkFilter.flush();
                            if (tail != null && !tail.isEmpty()) {
                                emitter.send(SseEmitter.event().name("delta").data(tail));
                            }
                        } catch (IOException ignored) {
                            // 客户端已断开
                        }
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
                            // 工具调用摘要（JSON 数组、按调用顺序记录工具名），供前端徽章行持久展示
                            if (!toolCalls.isEmpty()) {
                                StringBuilder ts = new StringBuilder("[");
                                for (int i = 0; i < toolCalls.size(); i++) {
                                    if (i > 0) ts.append(',');
                                    ts.append('"').append(toolCalls.get(i)).append('"');
                                }
                                ts.append(']');
                                aiMsg.setToolSummary(ts.toString());
                            }
                            // 工具快照数组落库：前端加载历史消息时重建内联生成式卡片
                            if (!toolSnapshots.isEmpty()) {
                                try {
                                    aiMsg.setToolSnapshots(objectMapper.writeValueAsString(toolSnapshots));
                                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                    log.warn("[AI] 工具快照序列化失败: {}", e.getMessage());
                                }
                            }
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
                        // M6a：记录 token 用量并落库轨迹
                        recordTokenUsage(trace, response);
                        traceService.save(trace);
                        // M7：批量授权档——本轮收集到的写操作在 done 前合并成一张批量卡统一请求确认。
                        // 放在 done 之前保证前端先收到卡再结束流；批次令牌同样由服务端持有参数
                        if (!batchItems.isEmpty()) {
                            emitBatchConfirm(emitter, conversationId, cu.getUserId(), batchItems);
                        }
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (IOException e) {
                            // 忽略
                        }
                        emitter.complete();
                    })
                    .onError(err -> {
                        // 工具循环超限：转优雅降级（degrade 事件），而非红色报错。
                        // 已完成的动作仍然有效，未完成部分提示用户再说一次。
                        if (isToolRoundLimitExceeded(err, toolRounds.get())) {
                            handleToolRoundDegrade(emitter, conversationId, toolRounds.get());
                            // M6a：降级轨迹
                            trace.markDegraded();
                            traceService.save(trace);
                            return;
                        }
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
                            // M6a：异常轨迹
                            trace.markError(msg);
                            traceService.save(trace);
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

        // 超时收尾先补发 done 再 complete：前端 SSE 解析以事件驱动，只 complete 不发终止事件时，
        // 部分场景（如容器异步超时但连接仍在）前端会一直停在生成态；done 与正常结束同形，UI 才能闭环。
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            } catch (Exception ignored) {
                // 连接已断，直接收尾
            }
            emitter.complete();
        });
        emitter.onError(t -> emitter.complete());

        return emitter;
    }

    // 内部方法

    /**
     * M6a：从最终响应中提取 token 用量记入轨迹。
     * <p>
     * 取不到时保持 null，不影响轨迹落库。
     */
    private void recordTokenUsage(AiAgentTraceService.Collector trace,
                                  dev.langchain4j.model.chat.response.ChatResponse response) {
        try {
            if (response == null || response.metadata() == null) {
                return;
            }
            var usage = response.metadata().tokenUsage();
            if (usage == null) {
                return;
            }
            trace.setTokenUsage(usage.inputTokenCount(), usage.outputTokenCount());
        } catch (Exception e) {
            // token 用量取不到不影响主流程
        }
    }

    /**
     * M4：把缓冲的过渡语作为 thought 事件发出，并清空缓冲。
     * <p>
     * 过渡语是模型在调用工具前的旁白（「我先查一下馆藏…」），把它独立于正文推送后，
     * 前端可将其渲染成步骤时间线节点，与工具卡片交替出现，形成 Agent 的决策链视图。
     * <p>
     * 清洗规则：
     * <ul>
     *   <li>去掉首尾空白与 markdown 装饰残留；</li>
     *   <li>剔除工具调用代码块（模型偶尔会把函数调用写进正文）；</li>
     *   <li>过长则截断（旁白不应喧宾夺主）。</li>
     * </ul>
     *
     * @param emitter SSE 发射器
     * @param buffer  过渡语缓冲
     * @param traceCollector 轨迹收集器（可为 null）
     */
    private void emitPendingThought(SseEmitter emitter, StringBuilder buffer,
                                    AiAgentTraceService.Collector traceCollector) {
        if (buffer == null || buffer.length() == 0) {
            return;
        }
        String raw = buffer.toString();
        buffer.setLength(0);
        String text = stripToolCallArtifacts(raw);
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }
        // M4 视觉修正（2）：部分模型会把「最终答案正文」一起流式输出后才调用工具，
        // 导致旁白里混入完整书目介绍，时间线被长文本淹没。
        // 旁白本应是「一句话意图」，这里截取到首个分句边界，得到短句意图描述。
        text = firstClause(text);
        // 单条旁白上限：避免模型长篇大论淹没时间线
        if (text.length() > THOUGHT_MAX_LEN) {
            text = text.substring(0, THOUGHT_MAX_LEN) + "…";
        }
        // M4 视觉修正：截断处的未闭合书名号会让旁白显示成「我来帮您查一下《三」，
        // 观感像渲染错误。此处剔除未闭合的《… 片段，只保留完整语义。
        if (text.indexOf('》') < 0) {
            int open = text.lastIndexOf('《');
            if (open >= 0) {
                text = text.substring(0, open).trim();
            }
        }
        if (text.isEmpty()) {
            return;
        }
        // 模型在工具调用前会边流式输出边调用工具，缓冲里的旁白往往停在半句
        // （如「我先帮您查一下这」）。此时补一个省略号，既如实表达「进行中」，
        // 也避免时间线上出现像被截断的残句。
        if (!isTerminalPunct(text.charAt(text.length() - 1))) {
            text = text + "…";
        }
        try {
            emitter.send(SseEmitter.event().name("thought").data(text));
            // M6a：决策旁白记入轨迹（Agent 规划轨迹的可视化证据）
            if (traceCollector != null) {
                traceCollector.recordThought(text);
            }
        } catch (IOException e) {
            // 客户端已断开，忽略
        }
    }

    /**
     * M4：判断字符是否为句末标点（用于判断旁白是否为完整句子）。
     */
    private boolean isTerminalPunct(char c) {
        return c == '。' || c == '！' || c == '？' || c == '…'
                || c == ')' || c == '）'
                || c == '"' || c == '"'
                || c == '\'' || c == '\'';
    }

    /**
     * M4：截取首个分句，作为「一句话意图」旁白。
     * <p>
     * 模型在工具轮前可能连同最终答案一起流式输出（先说答案再调工具），
     * 若原样作为旁白展示，时间线会被整段书目介绍淹没。此处按中文分句边界
     * （。！？，；：与换行）取首句，保留「我要做什么」的语义，去掉后面的数据罗列。
     *
     * @param text 原始旁白文本
     * @return 首个分句（过短时回退为原文本）
     */
    private String firstClause(String text) {
        if (text == null) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '，' || c == '；' || c == '：' || c == '\n') {
                String head = text.substring(0, i).trim();
                // 太短说明切在了标点噪声上，保留原文
                return head.length() >= 4 ? head : text.trim();
            }
        }
        return text.trim();
    }

    /**
     * 执行用户已确认的写操作（授权卡「确认办理」按钮的回调入口）。
     * <p>
     * 流程：消费令牌（一次性、校验过期/用户/摘要）→ 按令牌中保存的原始参数执行工具
     * → 把工具真实结果回填给模型，由模型基于真实返回生成最终回答。
     * <p>
     * 关键安全点：<b>客户端只回传令牌，不回传参数</b>。参数始终取自服务端保存的副本，
     * 因此不存在「用户改一下参数就借了别的书」的可能。
     *
     * @param token    授权令牌
     * @param userId   当前登录用户
     * @param toolName 待执行的工具名
     * @param conversationId 当前会话 ID（令牌签发时绑定；null 跳过会话校验，兼容旧客户端）
     * @return 工具执行结果；令牌无效时抛出业务异常（前端提示原因）
     */
    public Map<String, Object> confirmWrite(String token, Long userId, String toolName, Long conversationId) {
        return confirmWrite(token, userId, toolName, conversationId, false);
    }

    /**
     * 执行用户已确认的写操作（支持「本会话始终允许」）。
     *
     * @param alwaysAllow M8：true 时确认成功后把该工具登记进会话白名单，
     *                    本会话后续同类写操作由门禁直接放行
     */
    public Map<String, Object> confirmWrite(String token, Long userId, String toolName,
                                            Long conversationId, boolean alwaysAllow) {
        AiConfirmService.PendingConfirm pc = aiConfirmService.consume(token, userId, toolName, conversationId);
        if (pc == null) {
            throw new BusinessException(400, "授权已失效或已被处理，请重新发起该操作");
        }
        // 按令牌中保存的原始参数执行，忽略任何外部传入的参数
        Map<String, Object> args = pc.args();
        Object result = executeConfirmed(userId, pc.toolName(), args);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        // M8：确认成功且用户勾选「始终允许」时登记会话白名单。
        // 仅在执行成功后登记——失败的写操作不自动放行重试
        if (alwaysAllow && conversationId != null && Boolean.TRUE.equals(map.get("success"))) {
            aiSessionAllowService.allow(conversationId, pc.toolName());
        }
        // M8 确认续跑：把执行结果落库为助手摘要消息。前端确认成功后以 continuation
        // 标记发起下一轮对话，模型从历史读到本条结果后继续未完成的任务链
        if (conversationId != null) {
            persistConfirmOutcome(conversationId, pc.toolName(), map, pc.token());
        }
        return map;
    }

    /**
     * M10：工具执行结果 SSE 推送。
     * <p>
     * 确认执行完成后把结果摘要以 tool_result 事件推给前端，前端据此把对应授权卡
     * 原地更新为终态（success/error），结果内联在卡片上，替代旧的回执文字气泡。
     * <p>
     * 推送方式：确认接口不在 SSE 流内（独立 POST），SSE 通道此时可能已关闭，
     * 因此走 AiSseSessionRegistry 的会话级通道；找不到活跃通道时静默放弃——
     * 确认接口本身的返回值已携带结果，前端兜底用它更新卡片。
     */
    private void pushToolResult(Long conversationId, String toolName, boolean success,
                                Map<String, Object> result, String token) {
        try {
            SseEmitter emitter = AiSseSessionRegistry.get(conversationId);
            if (emitter == null) {
                return;
            }
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            // token 用于前端精确匹配是哪一张授权卡（同轮可能有多张同名工具的卡）
            payload.put("token", token);
            payload.put("tool", toolName);
            payload.put("success", success);
            Object msg = result.get("message");
            if (msg != null) {
                payload.put("message", str(msg, ""));
            }
            if (result.get("results") instanceof List<?> results) {
                payload.put("results", results);
            }
            if (result.get("successCount") instanceof Integer sc) {
                payload.put("successCount", sc);
            }
            if (result.get("failCount") instanceof Integer fc) {
                payload.put("failCount", fc);
            }
            emitter.send(SseEmitter.event().name("tool_result")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            // 推送失败不影响确认返回；前端用 confirm 接口返回值兜底
            log.debug("[AI] tool_result 推送跳过/失败 conversationId={}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * M8 确认续跑：把授权执行结果落库为一条助手消息（模型下一轮可见的历史）。
     * <p>
     * 落库为 assistant 角色而非伪造 user 消息：避免历史里出现用户没说过的话；
     * 内容是「系统回执」式的中性陈述（做了什么 + 结果如何），模型据此判断
     * 任务链剩余步骤，且不会把回执当成用户的新指令。
     */
    private void persistConfirmOutcome(Long conversationId, String toolName, Map<String, Object> result,
                                       String token) {
        try {
            boolean success = Boolean.TRUE.equals(result.get("success"));
            StringBuilder sb = new StringBuilder("[系统回执] 写操作已完成执行。");
            sb.append("工具：").append(toolName).append("；结果：").append(success ? "成功" : "失败").append("。");
            Object msg = result.get("message");
            if (msg != null && !str(msg, "").isBlank()) {
                sb.append("详情：").append(str(msg, ""));
            }
            // 逐项结果（批量办理时每条的成功/失败），供模型核对剩余任务
            if (result.get("results") instanceof List<?> results && !results.isEmpty()) {
                sb.append("；逐项：");
                for (Object r : results) {
                    if (r instanceof Map<?, ?> item) {
                        sb.append(item.get("borrowNo") != null ? item.get("borrowNo") : item.get("title"))
                          .append("=")
                          .append(Boolean.TRUE.equals(item.get("success")) ? "成功" : "失败")
                          .append(" ");
                    }
                }
            }
            sb.append("。请基于此结果继续处理用户任务链中的剩余步骤；若全部完成，请向用户汇总办理结果。");
            com.library.entity.AiMessage m = new com.library.entity.AiMessage();
            m.setConversationId(conversationId);
            m.setRole("assistant");
            m.setContent(sb.toString());
            // M10：机制消息对 UI 隐藏（模型历史仍可见，续跑上下文不丢）
            m.setVisibility("hidden");
            m.setCreateTime(LocalDateTime.now());
            messageMapper.insert(m);
            evictMemory(conversationId);
            // M10：同步推 tool_result SSE 事件，前端把授权卡原地更新为结果态，
            // 不再显示回执文字气泡
            pushToolResult(conversationId, toolName, success, result, token);
        } catch (Exception e) {
            // 回执落库失败不影响本次确认返回；续跑时模型少一条上下文，用户可手动追问
            log.warn("[AI] 确认回执落库失败 conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    /** 执行已确认的借阅申请（复用 ReaderTools 的实际办理逻辑） */
    private Object applyBorrowConfirmed(Long userId, Map<String, Object> args) {
        AiToolContext toolCtx = buildToolContext();
        ReaderTools tools = new ReaderTools(userId, currentUserType(), toolCtx);
        String title = str(args.get("bookTitle"), "");
        String isbn = str(args.get("isbn"), "");
        return tools.applyBorrowConfirmed(title, isbn.isBlank() ? null : isbn);
    }

    /** 执行已确认的续借 */
    private Object renewConfirmed(Long userId, Map<String, Object> args) {
        AiToolContext toolCtx = buildToolContext();
        ReaderTools tools = new ReaderTools(userId, currentUserType(), toolCtx);
        return tools.renewMyBorrowConfirmed(str(args.get("borrowNo"), ""));
    }

    /** 执行已确认的归还申请 */
    private Object returnConfirmed(Long userId, Map<String, Object> args) {
        AiToolContext toolCtx = buildToolContext();
        ReaderTools tools = new ReaderTools(userId, currentUserType(), toolCtx);
        return tools.requestReturnConfirmed(str(args.get("borrowNo"), ""));
    }

    /** 执行已确认的柜台办理 */
    private Object counterConfirmed(Long userId, Map<String, Object> args) {
        // 柜台办理是管理员能力：即便令牌校验通过，也再次校验执行者角色，
        // 防止管理员发起授权后令牌被同账户其他入口/降权场景复用
        Integer userType = currentUserType();
        if (userType == null || userType != 3) {
            throw new BusinessException(403, "仅管理员可执行柜台办理");
        }
        AiToolContext toolCtx = buildToolContext();
        AdminTools tools = new AdminTools(userId, currentUserType(), toolCtx);
        return tools.adminCounterProcessConfirmed(str(args.get("borrowNo"), ""));
    }

    /**
     * M7：批量授权卡 SSE 推送（batch 档位）。
     * <p>
     * 把本轮收集到的全部写操作合并为一张批量卡：rows 汇总各项明细，客户端只拿
     * 一个批次令牌；确认时服务端按顺序执行全部操作并逐项回报结果。
     * 推送失败时作废批次，避免「前端没收到卡、批次却在服务端存活」的悬挂状态。
     */
    private void emitBatchConfirm(SseEmitter emitter, Long conversationId, Long userId,
                                  List<AiBatchConfirmService.BatchItem> items) {
        String token = null;
        try {
            token = aiBatchConfirmService.issue(userId, conversationId, items);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("token", token);
            payload.put("batch", true);
            payload.put("count", items.size());
            payload.put("title", "批量授权（" + items.size() + " 项操作）");
            payload.put("rows", AiBatchConfirmService.mergeRows(items));
            payload.put("ttlSeconds", 120);
            emitter.send(SseEmitter.event().name("batch_confirm")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            if (token != null) {
                aiBatchConfirmService.revoke(token);
                log.warn("[AI] 批量授权卡推送失败，批次已作废");
            }
        } catch (Exception e) {
            if (token != null) {
                aiBatchConfirmService.revoke(token);
            }
            log.warn("[AI] 批量授权卡序列化失败: {}", e.getMessage());
        }
    }

    /**
     * 执行已确认的批量授权：按批次内顺序逐项执行，逐项回报结果。
     * <p>
     * 各项独立 try-catch：某项业务失败（如该书已借出）不影响其余项，
     * 汇总结构对齐 BorrowService.batchProcess 的 PickResultVo 风格，前端可统一渲染。
     */
    public Map<String, Object> confirmBatchWrite(String token, Long userId, Long conversationId) {
        AiBatchConfirmService.PendingBatch pb = aiBatchConfirmService.consume(token, userId, conversationId);
        if (pb == null) {
            throw new BusinessException(400, "批量授权已失效或已被处理，请重新发起该操作");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (AiBatchConfirmService.BatchItem item : pb.items()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("tool", item.toolName());
            r.put("title", item.title());
            try {
                Object result = executeConfirmed(userId, item.toolName(), item.args());
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                r.putAll(map);
                if (Boolean.TRUE.equals(map.get("success"))) {
                    success++;
                }
            } catch (Exception e) {
                r.put("success", false);
                r.put("message", e.getMessage() == null ? "执行失败" : e.getMessage());
            }
            results.add(r);
        }
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("success", success == results.size());
        summary.put("total", results.size());
        summary.put("successCount", success);
        summary.put("failCount", results.size() - success);
        summary.put("results", results);
        summary.put("message", "批量办理完成：成功 " + success + " 项，失败 " + (results.size() - success) + " 项");
        // M8 确认续跑：批量结果同样落库为系统回执，前端续跑后模型可核对剩余任务
        if (conversationId != null) {
            persistConfirmOutcome(conversationId, "adminCounterProcessBatch", summary, token);
        }
        return summary;
    }

    /**
     * M8：统计会话末尾连续的续跑引导消息数（按落库前缀识别）。
     * 用户一旦亲自发言（无前缀的 user 消息），计数即被打断归零重新开始。
     */
    private long countTrailingContinuations(Long conversationId) {
        List<com.library.entity.AiMessage> history = messageMapper.selectByConversationId(conversationId);
        long count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            com.library.entity.AiMessage m = history.get(i);
            if (!"user".equalsIgnoreCase(m.getRole())) {
                continue;
            }
            if (m.getContent() != null && m.getContent().startsWith(CONTINUATION_PREFIX)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * M9：把授权卡的决策态存入会话最新一条助手消息的快照通道，
     * 历史消息据此重建只读授权卡。pending 态不落库（刷新即失效是安全语义），
     * 仅记录已成事实的决策：confirmed / cancelled / expired / failed。
     * <p>
     * 追加到最后一条助手消息而非新建消息：卡片属于产生它的那轮回复的上下文。
     * 找不到助手消息（如首条消息授权且流未落库）时静默放弃——决策结果仍有
     * 系统回执文字记录，不丢事实。
     */
    public void recordCardDecision(Long conversationId, Long userId, String decision,
                                   String tool, String title, List<Map<String, String>> rows,
                                   boolean batch) {
        // 会话归属校验：只能给自己的会话记录
        AiConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return;
        }
        List<com.library.entity.AiMessage> history = messageMapper.selectByConversationId(conversationId);
        com.library.entity.AiMessage target = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            com.library.entity.AiMessage m = history.get(i);
            if ("assistant".equalsIgnoreCase(m.getRole())
                    // M10：跳过 hidden 的回执消息——它被历史接口过滤，快照挂在它上面
                    // 会跟着消失；决策卡属于用户可见的上一条正式回复
                    && !"hidden".equalsIgnoreCase(m.getVisibility())) {
                target = m;
                break;
            }
        }
        if (target == null) {
            return;
        }
        try {
            List<Map<String, Object>> snapshots = new ArrayList<>();
            if (target.getToolSnapshots() != null && !target.getToolSnapshots().isBlank()) {
                List<?> existing = objectMapper.readValue(target.getToolSnapshots(), List.class);
                for (Object o : existing) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) m);
                        snapshots.add(copy);
                    }
                }
            }
            Map<String, Object> rec = new java.util.LinkedHashMap<>();
            rec.put("kind", "card_decision");
            rec.put("state", decision);
            rec.put("tool", tool);
            rec.put("title", title == null ? "" : title);
            rec.put("rows", rows == null ? List.of() : rows);
            rec.put("batch", batch);
            rec.put("createTime", LocalDateTime.now().toString());
            snapshots.add(rec);
            messageMapper.updateToolSnapshots(target.getId(),
                    objectMapper.writeValueAsString(snapshots));
            evictMemory(conversationId);
        } catch (Exception e) {
            // 展示层记录失败不影响主流程
            log.warn("[AI] 授权卡决策记录失败 conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    /** 按工具名分发已确认的写操作执行（批量确认与单笔确认共用） */
    private Object executeConfirmed(Long userId, String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "applyBorrow" -> applyBorrowConfirmed(userId, args);
            case "renewMyBorrow" -> renewConfirmed(userId, args);
            case "requestReturn" -> returnConfirmed(userId, args);
            case "adminCounterProcess" -> counterConfirmed(userId, args);
            case "adminCounterProcessBatch" -> counterBatchConfirmed(userId, args);
            case "adminUrgeOverdue" -> urgeConfirmed(userId, args);
            default -> throw new BusinessException(400, "不支持的授权操作：" + toolName);
        };
    }

    /** 执行已确认的批量柜台办理（批量授权卡单项或单笔工具卡确认后的入口） */
    private Object counterBatchConfirmed(Long userId, Map<String, Object> args) {
        Integer userType = currentUserType();
        if (userType == null || userType != 3) {
            throw new BusinessException(403, "仅管理员可执行批量柜台办理");
        }
        AiToolContext toolCtx = buildToolContext();
        AdminTools tools = new AdminTools(userId, currentUserType(), toolCtx);
        return tools.adminCounterProcessBatchConfirmed(str(args.get("borrowNos"), ""));
    }

    /** 执行已确认的逾期催还（授权卡确认后的入口；管理员二次校验同柜台办理） */
    private Object urgeConfirmed(Long userId, Map<String, Object> args) {
        Integer userType = currentUserType();
        if (userType == null || userType != 3) {
            throw new BusinessException(403, "仅管理员可执行催还");
        }
        AiToolContext toolCtx = buildToolContext();
        AdminTools tools = new AdminTools(userId, currentUserType(), toolCtx);
        return tools.adminUrgeOverdueConfirmed(str(args.get("borrowNos"), ""));
    }

    /** 构造工具依赖上下文 */
    private AiToolContext buildToolContext() {
        return new AiToolContext(
                borrowMapper, bookMapper, fineMapper, announcementMapper,
                borrowLimitMapper, userMapper, categoryMapper, notificationMapper,
                categoryService, borrowService, statsService, notificationService, objectMapper);
    }

    /** 当前登录用户的类型（3=管理员） */
    private Integer currentUserType() {
        CurrentUser cu = CurrentUser.get();
        return cu == null ? null : cu.getUserType();
    }

    /**
     * 判断异常是否为「工具循环超限」。
     * <p>
     * 判定顺序：先沿异常链匹配框架的消息特征，再以轮次计数是否已达上限兜底。
     * <p>
     * 框架在超限时抛出的消息形如：
     * {@code "Something is wrong, exceeded 8 tool calling round trips (maxToolCallingRoundTrips)"}，
     * 属于 IllegalStateException（{@code runtime(...)} 包装）。
     * <p>
     * 兜底的理由：框架的异常消息文案可能随版本变化，仅靠字符串匹配会漏判；
     * 而漏判的后果是用户看到红色报错而非优雅降级。轮次已达上限时，任何异常都按超限处理，
     * 此时把「真实故障」误判为「超限」的代价也只是降级提示，不会掩盖严重问题。
     *
     * @param err        框架抛出的异常
     * @param toolRounds 本轮已执行的工具循环轮次
     */
    private boolean isToolRoundLimitExceeded(Throwable err, int toolRounds) {
        Throwable t = err;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("tool calling round trips")
                        || lower.contains("maxtoolcallingroundtrips")
                        || lower.contains("toolcallingroundtrips")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        // 兜底：轮次已达上限
        return toolRounds >= MAX_TOOL_ROUNDS;
    }

    /**
     * 工具循环超限的降级处理：推送 degrade 事件 + 降级文案落库 + 正常结束本轮。
     * <p>
     * 落库原因：超限时模型未产出最终回答（{@code onCompleteResponse} 不触发），
     * 若不落库，会话历史里会留下「有提问无回答」，导致下一轮上下文断裂、模型重复已完成的操作。
     * <p>
     * 文案原则：只陈述已完成的部分，绝不承诺未完成的结果（与 CORE_RULES 规则 2/7 一致）。
     */
    private void handleToolRoundDegrade(SseEmitter emitter, Long conversationId, int rounds) {
        // 不写「已完成的操作仍然有效」：超限时工具可能只执行了一半，断言"已完成"会误导用户；
        // 改为中性陈述，请用户以查询结果为准核对
        String text = "这个任务涉及的步骤较多，我执行 " + rounds
                + " 轮查询后停了下来。已查到的结果仍然可用，办理类操作是否生效请以页面显示为准，"
                + "未完成的部分可以再说一次。";
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("rounds", rounds);
            payload.put("message", text);
            emitter.send(SseEmitter.event().name("degrade")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            // 客户端已断开，忽略
        } catch (Exception e) {
            log.warn("[AI] 降级事件序列化失败: {}", e.getMessage());
        }

        // 降级文案落库，保证会话历史完整（用户提问有对应回答），下次提问上下文连续
        try {
            com.library.entity.AiMessage aiMsg = new com.library.entity.AiMessage();
            aiMsg.setConversationId(conversationId);
            aiMsg.setRole("assistant");
            aiMsg.setContent(text);
            aiMsg.setCreateTime(LocalDateTime.now());
            messageMapper.insert(aiMsg);
            evictMemory(conversationId);
        } catch (Exception e) {
            log.warn("[AI] 降级文案落库失败: {}", e.getMessage());
        }

        try {
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
        } catch (IOException e) {
            // 忽略
        }
        emitter.complete();
    }

    /**
     * 构造 RAG 检索增强器；知识库关闭或索引未就绪时返回 null（调用方跳过挂载，行为与接入前一致）。
     * <p>
     * 检索器每请求构造：{@code LibraryAssistant#chat} 没有 {@code @MemoryId}，
     * dynamicFilter 拿不到用户身份，而 AiServices 本就每请求构建，
     * 因此直接用<b>静态 filter</b> 按 scope 做权限隔离，比 ThreadLocal 方案更直接且无泄漏风险。
     * <ul>
     *   <li>管理员：{@code scope in (public, admin)}，可见全部资料</li>
     *   <li>读者：{@code scope = public}，admin 类内部规章不会进入其检索结果</li>
     * </ul>
     */
    private RetrievalAugmentor buildAugmentor(boolean admin) {
        if (!ragIndexService.isReady()) {
            return null;
        }
        LibraryConfigService.RagConfig cfg = libraryConfigService.getRagConfig();
        if (!Boolean.TRUE.equals(cfg.getEnabled())) {
            return null;
        }
        Filter scopeFilter = admin
                ? metadataKey("scope").isIn(RagIndexService.SCOPE_PUBLIC, RagIndexService.SCOPE_ADMIN)
                : metadataKey("scope").isEqualTo(RagIndexService.SCOPE_PUBLIC);
        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(ragIndexService.getStore())
                .embeddingModel(ragIndexService.getEmbeddingModel())
                .maxResults(cfg.getMaxResults())
                .minScore(cfg.getMinScore())
                .filter(scopeFilter)
                .build();
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(retriever)
                // 自定义中文注入模板（默认模板是英文且无来源标注/免责约束）
                .contentInjector(RagContentInjector.get())
                .build();
    }

    /**
     * 把本轮检索到的知识库来源通过 SSE {@code rag} 事件回传（早于 delta）。
     * 检索结果为空时不推送——不注入内容就没有来源可展示。
     */
    private void emitRagSources(SseEmitter emitter, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        try {
            List<Map<String, String>> sources = new ArrayList<>();
            for (Content c : contents) {
                TextSegment segment = c.textSegment();
                if (segment == null) {
                    continue;
                }
                String title = segment.metadata() == null ? null : segment.metadata().getString("docTitle");
                sources.add(Map.of(
                        "docTitle", title == null || title.isBlank() ? "未命名文档" : title,
                        "snippet", truncate(segment.text(), RAG_SNIPPET_MAX_LEN)));
            }
            if (sources.isEmpty()) {
                return;
            }
            emitter.send(SseEmitter.event().name("rag")
                    .data(objectMapper.writeValueAsString(sources)));
        } catch (IOException e) {
            // 客户端已断开，忽略
        } catch (Exception e) {
            log.warn("[AI] 知识库来源序列化失败: {}", e.getMessage());
        }
    }

    /** 截断文本（超长时加省略号），用于来源摘要 */
    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLen ? flat : flat.substring(0, maxLen) + "…";
    }

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

    /** 思维链标签（部分本地模型/网关把思维链以 <think> 包裹输出在正文流中），兼容常见变体 */
    private static final java.util.regex.Pattern THINK_OPEN_TAG =
            java.util.regex.Pattern.compile("<(think|thinking|reason|reasoning|thought)>");

    /** 完整的 <tag>...</tag> 思维链段（含空白），用于落库前的正文消毒 */
    private static final java.util.regex.Pattern THINK_BLOCK =
            java.util.regex.Pattern.compile("(?s)<(?:think|thinking|reason|reasoning|thought)>[\\s\\S]*?</(?:think|thinking|reason|reasoning|thought)>\\s*");

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
        // 0. 完整思维链标签段（<think>...</think> 等）：兜底剔除未流式截获的残留
        s = THINK_BLOCK.matcher(s).replaceAll("");
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
        // 5. M9 推理旁白剔除：部分模型（thinkingEnabled=false 的 DeepSeek 等）把内部规划
        //    以英文句子混在正文开头（"I'll first verify... processing the returns.两条记录都..."）。
        //    仅处理「正文以英文开头」的情况：剥掉开头至第一个 CJK 字符之前的英文推理段。
        //    正文以中文开头时不做任何处理——中英混排的正常回答（如书名 ISBN）不受影响。
        if (!s.isEmpty() && isAsciiLetter(s.charAt(0))) {
            int cjk = firstCjkIndex(s);
            if (cjk > 0) {
                s = s.substring(cjk).trim();
            }
        }
        return s;
    }

    /** ASCII 字母判定（推理旁白剔除的触发条件） */
    private boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** 第一个 CJK 字符（含中文标点）的下标；无则 -1 */
    private int firstCjkIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F)
                    || (c >= 0xFF00 && c <= 0xFFEF)) {
                return i;
            }
        }
        return -1;
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
        // 可调整段（人设/语气）由 LibraryConfigService 统一管理（外部文件热加载 + 在线编辑）
        String customizable = libraryConfigService.loadCustomizablePromptSegment()
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

    // 对话记忆缓存

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

    /**
     * AI 配置：来自当前启用的 AI 渠道（多渠道中按排序取第一个启用者，见 {@link LibraryConfigService}）。
     * 无启用渠道时各字段返回空串，由调用方给出「未配置」提示。
     */
    private record AiConfig(String provider, String apiKey, String model, String endpoint,
                            boolean thinkingEnabled) {}

    private AiConfig loadConfig() {
        LibraryConfigService.Channel ch = libraryConfigService.getActiveChannel();
        if (ch == null) {
            return new AiConfig("", "", "", "", false);
        }
        return new AiConfig(
                str(ch.getProvider(), ""),
                str(ch.getApiKey(), ""),
                str(ch.getModel(), ""),
                str(ch.getEndpoint(), ""),
                Boolean.TRUE.equals(ch.getThinkingEnabled()));
    }

    private OpenAiStreamingChatModel getOrBuildModel(AiConfig cfg) {
        String hash = cfg.provider() + "|" + cfg.apiKey() + "|" + cfg.model() + "|" + cfg.endpoint()
                + "|thinking=" + cfg.thinkingEnabled()
                + "|log=" + logRequestsEnabled + "," + logResponsesEnabled;
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
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(cfg.apiKey())
                .modelName(cfg.model());
        if (cfg.thinkingEnabled()) {
            // 深度思考（思维链）开启：returnThinking 解析响应中的 reasoning_content，
            // sendThinking 在后续轮次（含工具调用）自动回传思维链——满足 DeepSeek 思考模式
            // 「工具调用轮必须回传 reasoning_content」的协议要求，由框架原生处理。
            // 无 reasoning_content 的服务商（通义/Kimi 等未开启思考时）该字段为 null，
            // 不会序列化进请求体，互不影响。
            builder.returnThinking(true).sendThinking(true);
        } else {
            // 深度思考关闭：DeepSeek V3.1+/V4 默认开启思考且无法通过缺省参数关闭，
            // 需装饰 HTTP 客户端在请求体注入 thinking:disabled；其他厂商默认即不思考，
            // 装饰器只改写 DeepSeek 模型请求，不影响其他渠道。
            builder.httpClientBuilder(new DeepSeekThinkingDisableHttpClient.Builder());
        }
        OpenAiStreamingChatModel model = builder
                // 模型调用超时：避免长时间无响应卡死 SSE（参考 langchain4j-examples）
                .timeout(Duration.ofSeconds(MODEL_TIMEOUT_SECONDS))
                // 请求/响应日志：便于排查调用失败、token 用量等问题（app.ai.log-* 配置，生产建议关闭）
                .logRequests(logRequestsEnabled)
                .logResponses(logResponsesEnabled)
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

    // 模型列表动态拉取

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

    /**
     * 正文流中的思维链标签过滤器（有状态，单次回复生命周期）。
     * 流式增量可能把 "<think>"、"</think>" 拆在任意两个分片里，因此跨分片缓冲：
     * 常态直通；遇到可能是标签开头的尾部残片时暂存，等下一分片拼接后判定；
     * 进入标签后把内容作为 thinking 事件发出，直到遇到闭合标签。
     */
    private static class ThinkTagStreamFilter {
        private final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter;
        /** true = 当前处于标签内（思维链），false = 正文直通 */
        private boolean inThink = false;
        /** 跨分片缓冲：可能是未完标签开头的尾部字符 */
        private String pending = "";

        ThinkTagStreamFilter(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** 喂入一个正文增量，返回应作为正文（delta）发出的部分；思维链部分直接以 thinking 事件转发 */
        String feed(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            String buf = pending + chunk;
            pending = "";
            int i = 0;
            while (i < buf.length()) {
                if (inThink) {
                    int close = findTag(buf, i, true);
                    if (close >= 0) {
                        emitThinking(buf.substring(i, close));
                        i = close + endTagLen(buf, close);
                        inThink = false;
                    } else {
                        // 标签未闭合：尾部可能是 "</thi" 残片，留待下个分片
                        int hold = holdbackLen(buf, i, true);
                        int emitUpTo = Math.max(i, buf.length() - hold);
                        if (emitUpTo > i) {
                            emitThinking(buf.substring(i, emitUpTo));
                        }
                        pending = buf.substring(emitUpTo);
                        i = buf.length();
                    }
                } else {
                    int open = findTag(buf, i, false);
                    if (open >= 0) {
                        out.append(buf, i, open);
                        i = open + openTagLen(buf, open);
                        inThink = true;
                    } else {
                        // 尾部可能是 "<thi" 残片，留待下个分片判定
                        int hold = holdbackLen(buf, i, false);
                        int emitUpTo = Math.max(i, buf.length() - hold);
                        out.append(buf, i, emitUpTo);
                        pending = buf.substring(emitUpTo);
                        i = buf.length();
                    }
                }
            }
            return out.toString();
        }

        /**
         * 流结束时冲刷缓冲：把 pending 中残留的正文增量返回（此时不可能再有标签开头，
         * 残片是普通文本），或标签内残留以 thinking 事件发出。必须在 onCompleteResponse 调用，
         * 否则末尾 1~4 个字符会滞留在缓冲中丢失。
         */
        String flush() {
            if (pending.isEmpty()) {
                return "";
            }
            String rest = pending;
            pending = "";
            if (inThink) {
                emitThinking(rest);
                return "";
            }
            return rest;
        }

        /** 从 from 开始查找下一个思维链开/闭标签，返回下标或 -1（跳过 pending 逻辑由调用方保证 buf 完整性） */
        private int findTag(String s, int from, boolean closing) {
            java.util.regex.Matcher m = (closing ? THINK_CLOSE_TAG : THINK_OPEN_TAG)
                    .matcher(s).region(from, s.length());
            return m.find() ? m.start() : -1;
        }

        private int openTagLen(String s, int at) {
            java.util.regex.Matcher m = THINK_OPEN_TAG.matcher(s).region(at, s.length());
            return m.find() && m.start() == at ? m.group().length() : 0;
        }

        private int endTagLen(String s, int at) {
            java.util.regex.Matcher m = THINK_CLOSE_TAG.matcher(s).region(at, s.length());
            return m.find() && m.start() == at ? m.group().length() : 0;
        }

        /** 需要暂存等待下一分片的最大尾部长度（按开/闭标签族里最长的字面长度取，防止跨包截断泄漏） */
        private int holdbackLen(String s, int from, boolean closing) {
            // 闭标签族最长是 </reasoning>（12），比 "</thinking>"（11）还长 1，固定取 11 会导致
            // 分片恰好断在 "</reasoni" 时把半个闭合标签当正文发出，这里显式写最长的
            int maxTag = closing ? "</reasoning>".length() : "<reasoning>".length();
            return Math.min(maxTag - 1, s.length() - from);
        }

        private void emitThinking(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                        .event().name("thinking").data(text));
            } catch (java.io.IOException ignored) {
                // 客户端已断开
            }
        }
    }

    /** 思维链闭合标签 */
    private static final java.util.regex.Pattern THINK_CLOSE_TAG =
            java.util.regex.Pattern.compile("</(?:think|thinking|reason|reasoning|thought)>");
}
