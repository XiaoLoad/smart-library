package com.library.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4：注入器引用编号格式单测。
 * 验证 [资料 N] 前缀按检索命中顺序连续编号，与 SSE rag 事件的 sourceNo 约定一致。
 */
class RagContentInjectorTest {

    private static Content content(String title, String text) {
        return Content.from(TextSegment.from(text,
                dev.langchain4j.data.document.Metadata.from(Map.of("docTitle", title))));
    }

    @Test
    @DisplayName("多片段按顺序编号：[资料 1]、[资料 2] 连续无跳号")
    void numberedPrefixesAreSequential() {
        String formatted = RagContentInjector.formatNumbered(List.of(
                content("借阅规则", "每次最多借 5 本"),
                content("罚款规则", "逾期每日 0.1 元")));
        assertThat(formatted).startsWith("[资料 1] 每次最多借 5 本");
        assertThat(formatted).contains("[资料 2] 逾期每日 0.1 元");
    }

    @Test
    @DisplayName("空片段列表：格式化为空串（配合模板空注入短路）")
    void emptyContentsFormatToBlank() {
        assertThat(RagContentInjector.formatNumbered(List.of())).isBlank();
    }

    @Test
    @DisplayName("编号连续：10 个片段编号无跳号")
    void tenSegmentsNumberedSequentially() {
        List<Content> contents = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            contents.add(content("文档" + i, "片段" + i));
        }
        String formatted = RagContentInjector.formatNumbered(contents);
        for (int i = 1; i <= 10; i++) {
            assertThat(formatted).contains("[资料 " + i + "] 片段" + (i - 1));
        }
    }

    @Test
    @DisplayName("模板包含编号引用约定与「资料不足不得编造」约束")
    void promptTemplateContainsCitationContract() {
        // 通过反射读取模板常量做契约校验，防止后续改模板时丢掉关键约束
        try {
            java.lang.reflect.Field f = RagContentInjector.class.getDeclaredField("PROMPT_TEMPLATE");
            f.setAccessible(true);
            dev.langchain4j.model.input.PromptTemplate tpl =
                    (dev.langchain4j.model.input.PromptTemplate) f.get(null);
            String text = tpl.apply(java.util.Map.of(
                    "userMessage", (Object) "q",
                    "contents", "c")).text();
            assertThat(text).contains("[资料 N]");
            assertThat(text).contains("馆方知识库中暂未收录相关内容");
            assertThat(text).contains("（来源：《文档名》）");
            assertThat(text).contains("不得作为");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
