package com.library.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.exception.BusinessException;
import com.library.dto.AiChannelRequest;
import com.library.entity.SystemSetting;
import com.library.mapper.SystemSettingMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图书馆运营配置服务：统一读写 system_settings 键值表与外部 Prompt 文件。
 * <p>
 * 管理三类配置：
 * <ul>
 *   <li><b>AI 渠道</b>（key={@value #KEY_AI_CHANNELS}）：支持多渠道配置、启停、排序；
 *       系统按 sort 顺序使用第一个启用中的渠道；旧版单条 ai_config 首次访问时自动迁移</li>
 *   <li><b>馆内规则</b>（key={@value #KEY_LIBRARY_RULES}）：逾期罚款费率等</li>
 *   <li><b>AI 可调整 Prompt 段</b>：外部文件 prompts/library-system-prompt.txt（热加载），
 *       classpath 内置兜底；核心业务规则仍硬编码在 AiService，不受此文件影响</li>
 * </ul>
 * 解析结果在内存缓存，写操作后整体失效重读，避免每次业务请求查库。
 */
@Service
public class LibraryConfigService {

    private static final Logger log = LoggerFactory.getLogger(LibraryConfigService.class);

    public static final String KEY_AI_CHANNELS = "ai_channels";
    /** 旧版单条 AI 配置（仅用于首次迁移，之后不再读写） */
    public static final String KEY_AI_CONFIG_LEGACY = "ai_config";
    public static final String KEY_LIBRARY_RULES = "library_rules";

    /** 默认逾期罚款费率（元/天），与历史硬编码值保持一致 */
    public static final BigDecimal DEFAULT_FINE_RATE = new BigDecimal("0.50");

    // ===== Prompt 热加载（从 AiService 迁移至此统一管理） =====
    /** Prompt 模板文件名（classpath 内置 + 外部覆盖） */
    private static final String PROMPT_RESOURCE_PATH = "prompts/library-system-prompt.txt";
    /** 外部 Prompt 文件路径（运行目录下 prompts/，优先于 classpath，支持热更新与在线保存） */
    private static final Path PROMPT_EXTERNAL_PATH = Path.of("prompts", "library-system-prompt.txt");

    @Autowired
    private SystemSettingMapper systemSettingMapper;
    @Autowired
    private ObjectMapper objectMapper;

    // ===== 内存缓存 =====
    private volatile List<Channel> cachedChannels;
    private volatile BigDecimal cachedFineRate;
    private volatile String cachedPromptTemplate;
    private volatile long cachedPromptLastModified;
    private volatile long cachedPromptLength;

    // ==================== AI 渠道 ====================

    /**
     * AI 渠道（可变 POJO，便于 Jackson 序列化与控制器读取）。
     */
    @Data
    public static class Channel {
        private Long id;
        private String name;
        private String provider;
        private String apiKey;
        private String model;
        private String endpoint;
        private Boolean enabled;
        /**
         * 是否开启深度思考（思维链 reasoning_content）。默认关闭：
         * 开启后回答更严谨但首字响应更慢、Token 消耗更高；仅思考型模型有效
         * （如 DeepSeek V3.1+/V4）。由管理员按渠道控制，读者无权切换。
         */
        private Boolean thinkingEnabled;
        /** 排序值，越小优先级越高 */
        private Integer sort;
    }

    /** 渠道列表存储结构 */
    @Data
    private static class ChannelStore {
        private List<Channel> channels = new ArrayList<>();
    }

    /**
     * 全部渠道（按 sort 升序）。首次访问时若新键不存在，自动从旧版 ai_config 迁移。
     */
    public List<Channel> listChannels() {
        List<Channel> cached = cachedChannels;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedChannels != null) {
                return cachedChannels;
            }
            ChannelStore store = loadStore();
            cachedChannels = store.getChannels();
            return cachedChannels;
        }
    }

    /**
     * 当前生效渠道：sort 最前且启用中的渠道；无则 null。
     */
    public Channel getActiveChannel() {
        return listChannels().stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 新增渠道。apiKey 必填；默认追加到末尾（sort 最大）。
     */
    public synchronized Channel addChannel(AiChannelRequest req) {
        if (req.getApiKey() == null || req.getApiKey().isBlank()) {
            throw new BusinessException(400, "请填写 API Key");
        }
        ChannelStore store = loadStore();
        List<Channel> channels = store.getChannels();
        Channel c = new Channel();
        c.setId(channels.stream().mapToLong(Channel::getId).max().orElse(0L) + 1);
        c.setName(req.getName().trim());
        c.setProvider(blankToEmpty(req.getProvider()));
        c.setApiKey(req.getApiKey().trim());
        c.setModel(req.getModel().trim());
        c.setEndpoint(req.getEndpoint().trim());
        c.setEnabled(req.getEnabled() == null || req.getEnabled());
        c.setSort(channels.size());
        channels.add(c);
        persistStore(store);
        log.info("[配置] 新增 AI 渠道：id={} name={}", c.getId(), c.getName());
        return c;
    }

    /**
     * 编辑渠道。apiKey 留空表示保留原 Key 不变。
     */
    public synchronized Channel updateChannel(AiChannelRequest req) {
        if (req.getId() == null) {
            throw new BusinessException(400, "渠道 ID 不能为空");
        }
        ChannelStore store = loadStore();
        Channel target = store.getChannels().stream()
                .filter(c -> c.getId().equals(req.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "渠道不存在"));
        target.setName(req.getName().trim());
        target.setProvider(blankToEmpty(req.getProvider()));
        target.setModel(req.getModel().trim());
        target.setEndpoint(req.getEndpoint().trim());
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            target.setApiKey(req.getApiKey().trim());
        }
        if (req.getEnabled() != null) {
            target.setEnabled(req.getEnabled());
        }
        target.setThinkingEnabled(Boolean.TRUE.equals(req.getThinkingEnabled()));
        persistStore(store);
        log.info("[配置] 更新 AI 渠道：id={} name={}", target.getId(), target.getName());
        return target;
    }

    /**
     * 启用/停用渠道。
     */
    public synchronized void toggleChannel(Long id, boolean enabled) {
        ChannelStore store = loadStore();
        Channel target = requireChannel(store, id);
        target.setEnabled(enabled);
        persistStore(store);
        log.info("[配置] AI 渠道 {} 已{}", id, enabled ? "启用" : "停用");
    }

    /**
     * 上移/下移渠道（交换相邻 sort）。direction=-1 上移，1 下移。
     */
    public synchronized void moveChannel(Long id, int direction) {
        ChannelStore store = loadStore();
        List<Channel> channels = store.getChannels();
        int idx = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).getId().equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new BusinessException(404, "渠道不存在");
        }
        int swapIdx = idx + direction;
        if (swapIdx < 0 || swapIdx >= channels.size()) {
            return; // 已在端点，无操作
        }
        Channel a = channels.get(idx);
        Channel b = channels.get(swapIdx);
        Integer tmp = a.getSort();
        a.setSort(b.getSort());
        b.setSort(tmp);
        channels.sort((x, y) -> Integer.compare(x.getSort(), y.getSort()));
        persistStore(store);
    }

    /**
     * 删除渠道。
     */
    public synchronized void deleteChannel(Long id) {
        ChannelStore store = loadStore();
        Channel target = requireChannel(store, id);
        store.getChannels().remove(target);
        // 重排 sort，保证连续
        List<Channel> channels = store.getChannels();
        for (int i = 0; i < channels.size(); i++) {
            channels.get(i).setSort(i);
        }
        persistStore(store);
        log.info("[配置] 删除 AI 渠道：id={}", id);
    }

    /**
     * 按 ID 获取渠道（含 apiKey，仅供服务端测试连接使用，禁止直接回传前端）。
     */
    public Channel getChannelById(Long id) {
        return listChannels().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "渠道不存在"));
    }

    private Channel requireChannel(ChannelStore store, Long id) {
        return store.getChannels().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "渠道不存在"));
    }

    /**
     * 读取渠道存储。
     * <p>
     * 以下三种情况都会尝试从旧版 ai_config 迁移并落库：
     * 新键不存在、JSON 解析失败、或渠道列表为空（settings.sql 种子会插入
     * {@code {"channels":[]}} 空行，若直接返回会阻断旧配置迁移，导致已配 Key 的渠道"消失"）。
     * 已有渠道数据时不迁移，避免重复追加旧渠道。
     */
    private ChannelStore loadStore() {
        ChannelStore store = null;
        SystemSetting s = systemSettingMapper.selectByKey(KEY_AI_CHANNELS);
        if (s != null && s.getSettingValue() != null && !s.getSettingValue().isBlank()) {
            try {
                store = objectMapper.readValue(s.getSettingValue(), ChannelStore.class);
                if (store.getChannels() == null) {
                    store.setChannels(new ArrayList<>());
                }
                store.getChannels().sort((a, b) -> Integer.compare(
                        a.getSort() == null ? 0 : a.getSort(),
                        b.getSort() == null ? 0 : b.getSort()));
            } catch (Exception e) {
                log.warn("[配置] ai_channels 解析失败，尝试迁移旧配置: {}", e.getMessage());
                store = null;
            }
        }

        // 已有渠道数据：直接使用，不迁移
        if (store != null && !store.getChannels().isEmpty()) {
            return store;
        }

        // 键缺失 / 解析失败 / 空列表（种子行）：迁移旧版单条配置
        ChannelStore migrated = migrateLegacyConfig();
        if (!migrated.getChannels().isEmpty()) {
            persistStore(migrated);
            // 迁移完成后清理旧行，避免用户删光渠道重启后旧渠道被重复迁移回来
            systemSettingMapper.deleteByKey(KEY_AI_CONFIG_LEGACY);
            log.info("[配置] 旧版 ai_config 已迁移完成并清理");
            return migrated;
        }

        // 无旧配置可迁移：键不存在时落库空存储，避免后续重复查询；已有空行则原样返回
        if (store == null) {
            persistStore(migrated);
        }
        return store != null ? store : migrated;
    }

    /**
     * 旧版 ai_config（单渠道 JSON）迁移为渠道列表的第一个启用渠道。
     */
    private ChannelStore migrateLegacyConfig() {
        ChannelStore store = new ChannelStore();
        SystemSetting legacy = systemSettingMapper.selectByKey(KEY_AI_CONFIG_LEGACY);
        if (legacy != null && legacy.getSettingValue() != null && !legacy.getSettingValue().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.readValue(legacy.getSettingValue(), Map.class);
                String apiKey = m.get("apiKey") == null ? "" : m.get("apiKey").toString();
                Channel c = new Channel();
                c.setId(1L);
                c.setName("默认渠道");
                c.setProvider(m.get("provider") == null ? "deepseek" : m.get("provider").toString());
                c.setApiKey(apiKey);
                c.setModel(m.get("model") == null ? "deepseek-chat" : m.get("model").toString());
                c.setEndpoint(m.get("endpoint") == null ? "" : m.get("endpoint").toString());
                // 旧配置未配 Key 时默认停用，避免启用一个不可用渠道
                c.setEnabled(!apiKey.isBlank());
                c.setSort(0);
                store.getChannels().add(c);
                log.info("[配置] 已从旧版 ai_config 迁移 AI 渠道：{}（apiKey {})",
                        c.getProvider(), apiKey.isBlank() ? "未配置，渠道停用" : "已配置");
            } catch (Exception e) {
                log.warn("[配置] 旧版 ai_config 迁移失败: {}", e.getMessage());
            }
        }
        return store;
    }

    private void persistStore(ChannelStore store) {
        try {
            store.getChannels().sort((a, b) -> Integer.compare(
                    a.getSort() == null ? 0 : a.getSort(),
                    b.getSort() == null ? 0 : b.getSort()));
            String json = objectMapper.writeValueAsString(store);
            systemSettingMapper.upsert(KEY_AI_CHANNELS, json);
            cachedChannels = store.getChannels();
        } catch (Exception e) {
            throw new BusinessException(500, "保存渠道配置失败：" + e.getMessage());
        }
    }

    // ==================== 馆内规则 ====================

    /**
     * 逾期罚款费率（元/天）。未配置或解析失败时返回默认值 0.50。
     */
    public BigDecimal getFineRatePerDay() {
        BigDecimal cached = cachedFineRate;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedFineRate != null) {
                return cachedFineRate;
            }
            BigDecimal rate = DEFAULT_FINE_RATE;
            SystemSetting s = systemSettingMapper.selectByKey(KEY_LIBRARY_RULES);
            if (s != null && s.getSettingValue() != null && !s.getSettingValue().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = objectMapper.readValue(s.getSettingValue(), Map.class);
                    Object v = m.get("fineRatePerDay");
                    if (v != null && !v.toString().isBlank()) {
                        rate = new BigDecimal(v.toString());
                    }
                } catch (Exception e) {
                    log.warn("[配置] library_rules 解析失败，使用默认罚款费率: {}", e.getMessage());
                }
            }
            cachedFineRate = rate;
            return rate;
        }
    }

    /**
     * 保存馆内规则（目前仅罚款费率，后续扩展字段同样写入此 JSON）。
     */
    public synchronized void saveFineRate(BigDecimal fineRatePerDay) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("fineRatePerDay", fineRatePerDay));
            systemSettingMapper.upsert(KEY_LIBRARY_RULES, json);
            cachedFineRate = fineRatePerDay;
            log.info("[配置] 逾期罚款费率已更新为 {} 元/天", fineRatePerDay);
        } catch (Exception e) {
            throw new BusinessException(500, "保存馆内规则失败：" + e.getMessage());
        }
    }

    // ==================== RAG 知识库配置 ====================

    public static final String KEY_RAG_CONFIG = "rag_config";
    /** 当前使用的本地中文 embedding 模型标识（启动校验用：与配置不符则强制重建索引） */
    public static final String EMBEDDING_MODEL_NAME = "bge-small-zh-v15-q";
    /** bge-small-zh-v1.5 隐藏层维度；实测 BgeSmallZhV15QuantizedEmbeddingModel.dimension() = 512 */
    public static final int EMBEDDING_DIM = 512;

    /**
     * RAG 检索/切分参数（可在 Web 端在线调整，无需改代码重启）。
     */
    @Data
    public static class RagConfig {
        /** RAG 总开关 */
        private Boolean enabled = true;
        /** 检索返回片段数 */
        private Integer maxResults = 3;
        /** 相似度下限，低于该分数的片段不注入（防止硬塞不相关片段）。
         *  实测：相关问句 0.79~0.84，无关问句也有 0.70~0.71，故取 0.72 */
        private Double minScore = 0.72;
        /** 切片字数 */
        private Integer chunkSize = 400;
        /** 切片重叠字数 */
        private Integer chunkOverlap = 60;
        /** 建索引所用 embedding 模型标识 */
        private String embeddingModel = EMBEDDING_MODEL_NAME;
        /** 向量维度 */
        private Integer embeddingDim = EMBEDDING_DIM;
    }

    private volatile RagConfig cachedRagConfig;

    /**
     * RAG 配置。未配置或字段缺失时返回默认值（不落种子行，避免空值覆盖默认）。
     */
    public RagConfig getRagConfig() {
        RagConfig cached = cachedRagConfig;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedRagConfig != null) {
                return cachedRagConfig;
            }
            RagConfig cfg = new RagConfig();
            SystemSetting s = systemSettingMapper.selectByKey(KEY_RAG_CONFIG);
            if (s != null && s.getSettingValue() != null && !s.getSettingValue().isBlank()) {
                try {
                    cfg = objectMapper.readValue(s.getSettingValue(), RagConfig.class);
                } catch (Exception e) {
                    log.warn("[配置] rag_config 解析失败，使用默认参数: {}", e.getMessage());
                    cfg = new RagConfig();
                }
            }
            normalize(cfg);
            cachedRagConfig = cfg;
            return cfg;
        }
    }

    /**
     * 保存 RAG 配置（保存后立即生效，新的检索请求按新参数执行）。
     */
    public synchronized void saveRagConfig(RagConfig cfg) {
        try {
            normalize(cfg);
            systemSettingMapper.upsert(KEY_RAG_CONFIG, objectMapper.writeValueAsString(cfg));
            cachedRagConfig = cfg;
            log.info("[配置] RAG 参数已更新：enabled={} maxResults={} minScore={} chunkSize={} chunkOverlap={}",
                    cfg.getEnabled(), cfg.getMaxResults(), cfg.getMinScore(), cfg.getChunkSize(), cfg.getChunkOverlap());
        } catch (Exception e) {
            throw new BusinessException(500, "保存 RAG 配置失败：" + e.getMessage());
        }
    }

    /**
     * 记录本次建索引实际使用的模型与维度（索引重建完成后调用）。
     * 模型/维度与记录不符是「必须重建索引」的判据，因此重建后要回写。
     */
    public synchronized void updateRagEmbeddingInfo(String embeddingModel, int embeddingDim) {
        RagConfig cfg = getRagConfig();
        if (embeddingModel.equals(cfg.getEmbeddingModel()) && cfg.getEmbeddingDim() == embeddingDim) {
            return;
        }
        cfg.setEmbeddingModel(embeddingModel);
        cfg.setEmbeddingDim(embeddingDim);
        saveRagConfig(cfg);
    }

    /** 缺省值兜底：键存在但值为 null / 非法时按默认值处理 */
    private static void normalize(RagConfig cfg) {
        RagConfig d = new RagConfig();
        if (cfg.getEnabled() == null) {
            cfg.setEnabled(d.getEnabled());
        }
        if (cfg.getMaxResults() == null || cfg.getMaxResults() < 1) {
            cfg.setMaxResults(d.getMaxResults());
        }
        if (cfg.getMinScore() == null || cfg.getMinScore() < 0 || cfg.getMinScore() > 1) {
            cfg.setMinScore(d.getMinScore());
        }
        if (cfg.getChunkSize() == null || cfg.getChunkSize() < 50) {
            cfg.setChunkSize(d.getChunkSize());
        }
        if (cfg.getChunkOverlap() == null || cfg.getChunkOverlap() < 0) {
            cfg.setChunkOverlap(d.getChunkOverlap());
        }
        if (cfg.getEmbeddingModel() == null || cfg.getEmbeddingModel().isBlank()) {
            cfg.setEmbeddingModel(d.getEmbeddingModel());
        }
        if (cfg.getEmbeddingDim() == null || cfg.getEmbeddingDim() < 1) {
            cfg.setEmbeddingDim(d.getEmbeddingDim());
        }
    }

    // ==================== AI 可调整 Prompt 段 ====================

    /**
     * 加载可调整 Prompt 段（人设/语气/称呼，热加载）。
     * <p>
     * 加载顺序：外部文件（运行目录 prompts/，支持热更新）→ classpath 内置 → 硬编码兜底。
     * 通过文件最后修改时间 + 文件大小检测变更，避免每次请求读文件。
     */
    public String loadCustomizablePromptSegment() {
        // 1. 外部文件（运行目录下，支持热更新与在线保存）
        if (Files.exists(PROMPT_EXTERNAL_PATH)) {
            try {
                long lastModified = Files.getLastModifiedTime(PROMPT_EXTERNAL_PATH).toMillis();
                long fileSize = Files.size(PROMPT_EXTERNAL_PATH);
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

        // 2. 外部文件不存在或读取失败 → 使用已缓存模板（可能来自之前的 classpath 加载）
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

        // 4. 兜底：硬编码默认人设（不含核心规则，核心规则由 AiService.CORE_RULES 注入）
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

    /**
     * 保存可调整 Prompt 段到外部文件（运行目录 prompts/library-system-prompt.txt），
     * 保存后即时生效（下一次 AI 对话即使用新内容）。
     */
    public synchronized void saveCustomizablePromptSegment(String content) {
        try {
            Files.createDirectories(PROMPT_EXTERNAL_PATH.getParent());
            Files.writeString(PROMPT_EXTERNAL_PATH, content, StandardCharsets.UTF_8);
            cachedPromptTemplate = content;
            try {
                cachedPromptLastModified = Files.getLastModifiedTime(PROMPT_EXTERNAL_PATH).toMillis();
                cachedPromptLength = Files.size(PROMPT_EXTERNAL_PATH);
            } catch (IOException ignored) {
                // 缓存字段刷新失败不影响保存结果
            }
            log.info("[AI] 可调整 Prompt 段已保存到外部文件: {}", PROMPT_EXTERNAL_PATH.toAbsolutePath());
        } catch (IOException e) {
            throw new BusinessException(500, "保存 Prompt 文件失败：" + e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
