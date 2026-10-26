package com.library.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.library.common.PageResult;
import com.library.common.Result;
import com.library.common.exception.BusinessException;
import com.library.dto.RagConfigRequest;
import com.library.dto.RagTextRequest;
import com.library.entity.RagDocument;
import com.library.entity.RagSegment;
import com.library.mapper.RagDocumentMapper;
import com.library.mapper.RagSegmentMapper;
import com.library.security.CurrentUser;
import com.library.service.LibraryConfigService;
import com.library.service.RagIndexService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库管理控制器（仅管理员）。
 * <p>
 * 首期文档来源仅两类：上传 txt/md（只解析文本入库，不保留原文件，避免任意文件上传与路径穿越）、
 * 粘贴文本录入。书目同步（{@code source_type=book}）为预留。
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    /** 允许上传的扩展名白名单 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".txt", ".md");

    @Autowired
    private RagIndexService ragIndexService;
    @Autowired
    private RagDocumentMapper documentMapper;
    @Autowired
    private RagSegmentMapper segmentMapper;
    @Autowired
    private LibraryConfigService configService;

    /** 仅管理员可操作 */
    private void requireAdmin() {
        CurrentUser cu = CurrentUser.get();
        if (cu == null || cu.getUserType() == null || cu.getUserType() != 3) {
            throw new BusinessException(403, "仅管理员可操作");
        }
    }

    // 文档

    /**
     * 文档列表（分页）。
     */
    @GetMapping("/documents")
    public Result<PageResult<RagDocument>> listDocuments(@RequestParam(defaultValue = "1") Integer page,
                                                         @RequestParam(defaultValue = "10") Integer size,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String scope) {
        requireAdmin();
        PageHelper.startPage(page, size);
        List<RagDocument> list = documentMapper.selectPage(keyword, scope);
        PageInfo<RagDocument> info = new PageInfo<>(list);
        return Result.success(new PageResult<>(info.getList(), info.getTotal()));
    }

    /**
     * 上传 txt/md 文件入库（只解析文本，不保存原文件）。
     */
    @PostMapping("/documents/upload")
    public Result<RagDocument> uploadDocument(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(defaultValue = "public") String scope) {
        requireAdmin();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = filename.toLowerCase();
        if (ALLOWED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new BusinessException(400, "仅支持 txt / md 文本文件");
        }
        String docTitle = (title == null || title.isBlank())
                ? filename.replaceAll("\\.[^.]+$", "")
                : title.trim();
        return Result.success("文档已入库", ragIndexService.createDocument(docTitle, scope, readAsText(file)));
    }

    /**
     * 粘贴文本入库。
     */
    @PostMapping("/documents/text")
    public Result<RagDocument> createByText(@Valid @RequestBody RagTextRequest req) {
        requireAdmin();
        return Result.success("文档已入库",
                ragIndexService.createDocument(req.getTitle(), req.getScope(), req.getContent()));
    }

    /**
     * 启用 / 停用文档（停用后立即不再被检索命中，片段保留；重新启用按原向量回填索引）。
     */
    @PutMapping("/documents/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        requireAdmin();
        ragIndexService.setDocumentStatus(id, status != null && status == 1 ? 1 : 0);
        return Result.success("状态已更新", null);
    }

    /**
     * 删除文档（连带片段，并移出内存索引）。
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        requireAdmin();
        ragIndexService.remove(id);
        return Result.success("文档已删除", null);
    }

    /**
     * 重建单篇索引（按当前 chunkSize/chunkOverlap 重新切分）。
     */
    @PostMapping("/documents/{id}/reindex")
    public Result<Map<String, Object>> reindexDocument(@PathVariable Long id) {
        requireAdmin();
        int count = ragIndexService.reindex(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("segmentCount", count);
        return Result.success("索引已重建", data);
    }

    /**
     * 全量重建索引（切换切分参数或 embedding 模型后使用）。
     */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuildAll() {
        requireAdmin();
        int count = ragIndexService.rebuildAll();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("segmentCount", count);
        return Result.success("全量重建完成", data);
    }

    /**
     * 某文档的片段明细。
     */
    @GetMapping("/documents/{id}/segments")
    public Result<List<RagSegment>> listSegments(@PathVariable Long id) {
        requireAdmin();
        return Result.success(segmentMapper.listByDocumentId(id));
    }

    // 检索测试

    /**
     * 检索测试：输入 query，返回 Top N 片段与相似度分数。
     * <p>
     * 唯一能直观验证「切分与阈值调得对不对」的入口。
     *
     * @param scope 传 public 可模拟读者视角（验证 admin 文档不会被读者检索到）；不传表示不过滤
     */
    @PostMapping("/search-test")
    public Result<List<Map<String, Object>>> searchTest(@RequestParam String query,
                                                        @RequestParam(required = false) Integer maxResults,
                                                        @RequestParam(required = false) Double minScore,
                                                        @RequestParam(required = false) String scope) {
        requireAdmin();
        List<EmbeddingMatch<TextSegment>> matches =
                ragIndexService.search(query, maxResults, minScore, scope);
        List<Map<String, Object>> list = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentId", m.embeddingId());
            item.put("score", m.score());
            item.put("docTitle", m.embedded().metadata().getString("docTitle"));
            item.put("scope", m.embedded().metadata().getString("scope"));
            item.put("content", m.embedded().text());
            list.add(item);
        }
        return Result.success(list);
    }

    // 参数配置

    /**
     * 读取 RAG 参数（含当前索引状态）。
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig() {
        requireAdmin();
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", cfg);
        data.put("ready", ragIndexService.isReady());
        data.put("indexedSegmentCount", ragIndexService.indexedSegmentCount());
        return Result.success(data);
    }

    /**
     * 更新 RAG 参数（保存后立即生效；改切分参数需手动全量重建索引）。
     */
    @PostMapping("/config")
    public Result<LibraryConfigService.RagConfig> saveConfig(@RequestBody RagConfigRequest req) {
        requireAdmin();
        LibraryConfigService.RagConfig cfg = configService.getRagConfig();
        if (req.getEnabled() != null) {
            cfg.setEnabled(req.getEnabled());
        }
        if (req.getMaxResults() != null) {
            cfg.setMaxResults(req.getMaxResults());
        }
        if (req.getMinScore() != null) {
            cfg.setMinScore(req.getMinScore());
        }
        if (req.getChunkSize() != null) {
            cfg.setChunkSize(req.getChunkSize());
        }
        if (req.getChunkOverlap() != null) {
            cfg.setChunkOverlap(req.getChunkOverlap());
        }
        configService.saveRagConfig(cfg);
        return Result.success("参数已保存", cfg);
    }

    // 工具

    /** 按 UTF-8 读取上传文本（去掉可能的 BOM 头） */
    private static String readAsText(MultipartFile file) {
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            throw new BusinessException(400, "文件读取失败：" + e.getMessage());
        }
    }
}