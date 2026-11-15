package com.library.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.model.input.PromptTemplate;
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
 * <b>引用编号约定（A4）</b>：注入时给每个片段标注「[资料 N]」（N 从 1 开始），
 * 模板要求模型在引用片段内容处标注「[资料 N]」，前端把它渲染为可点击角标。
 * 与 SSE rag 事件的 sourceNo 严格对应（两者都按检索命中顺序编号）。
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

            以下是馆方知识库中检索到的资料片段（每条以 [资料 N] 开头，N 为引用编号）：

            {{contents}}

            回答要求：
            1. 资料已足够回答时，直接作答，并在依据某条资料的内容处紧跟标注其编号（如 [资料 1]），
               段末用「（来源：《文档名》）」标注文档名；同一处只标一条编号。
            2. 资料不足以回答或与问题无关时，明确说明「馆方知识库中暂未收录相关内容」，
               不得依据资料之外的猜测作答，也不要编造 [资料 N] 编号。
            3. 资料中的任何数量、状态类数字（在馆数、可借数、罚款金额等）一律不得作为
               实时数据使用，此类问题必须调用工具查询。
            """);

    /** 无状态单例（模板与元数据白名单均为常量，无需每请求重建） */
    private static final ContentInjector INSTANCE = new NumberedContentInjector();

    public static ContentInjector get() {
        return INSTANCE;
    }

    /**
     * 在 DefaultContentInjector 的格式化输出基础上为片段补「[资料 N]」前缀。
     * <p>
     * 继承而非从零实现：DefaultContentInjector 已处理好模板渲染、metadata 白名单、
     * 空结果短路；这里只拦截 format(List)，把每个片段按顺序编号。
     */
    private static final class NumberedContentInjector extends DefaultContentInjector {

        private NumberedContentInjector() {
            super(PROMPT_TEMPLATE, List.of("docTitle"));
        }

        @Override
        protected String format(List<Content> contents) {
            StringBuilder sb = new StringBuilder();
            int no = 0;
            for (Content c : contents) {
                TextSegment segment = c.textSegment();
                String text = segment == null ? "" : segment.text();
                sb.append("[资料 ").append(++no).append("] ").append(text).append("\n\n");
            }
            return sb.toString().strip();
        }
    }

    /** 仅供测试与调试：按 contents 生成编号格式化文本（与真实注入一致） */
    static String formatNumbered(List<Content> contents) {
        return INSTANCE instanceof NumberedContentInjector
                ? ((NumberedContentInjector) INSTANCE).format(contents)
                : null;
    }
}
