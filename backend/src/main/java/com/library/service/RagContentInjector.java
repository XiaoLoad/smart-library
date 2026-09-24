package com.library.service;

import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;

import java.util.List;

/**
 * RAG 检索结果注入器：把知识库片段按中文模板拼进用户消息。
 * <p>
 * 为什么必须自定义：若直接用 {@code .contentRetriever(retriever)} 快捷方式，
 * LangChain4j 会套用 {@link DefaultContentInjector#DEFAULT_PROMPT_TEMPLATE}，其三个缺陷：
 * <ol>
 *   <li>模板为英文，中文场景下模型表现不稳定；</li>
 *   <li>不要求标注来源，最终回答里看不到依据哪篇文档；</li>
 *   <li>没有「资料不足不许编造」的约束，检索到不相关片段时会硬答。</li>
 * </ol>
 * <p>
 * metadataKeysToInclude 只暴露 {@code docTitle}（来源标注所需），
 * {@code documentId} / {@code scope} 等内部字段不进入 prompt。
 * <p>
 * 另外一个天然优势：{@code DefaultContentInjector.inject()} 首行即
 * {@code if (contents.isEmpty()) return chatMessage;}——检索不到片段时完全不注入，
 * 不会往 prompt 里塞空内容，与「空结果如实告知」的规则天然一致。
 */
public final class RagContentInjector {

    private RagContentInjector() {
    }

    /** 注入模板：{{userMessage}} 与 {{contents}} 为 DefaultContentInjector 提供的占位符 */
    private static final PromptTemplate PROMPT_TEMPLATE = PromptTemplate.from("""
            {{userMessage}}

            以下是馆方知识库中检索到的资料片段，请仅依据这些资料回答上方的用户问题：

            {{contents}}

            回答要求：
            1. 资料已足够回答时，直接作答，并在末尾用「（来源：《文档名》）」标注依据的文档。
            2. 资料不足以回答或与问题无关时，明确说明「馆方知识库中暂未收录相关内容」，
               不得依据资料之外的猜测作答。
            3. 资料中的任何数量、状态类数字（在馆数、可借数、罚款金额等）一律不得作为
               实时数据使用，此类问题必须调用工具查询。
            """);

    /** 无状态单例（模板与元数据白名单均为常量，无需每请求重建） */
    private static final ContentInjector INSTANCE = DefaultContentInjector.builder()
            .promptTemplate(PROMPT_TEMPLATE)
            .metadataKeysToInclude(List.of("docTitle"))
            .build();

    public static ContentInjector get() {
        return INSTANCE;
    }
}