package com.library.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RagSource/RagSearchResult 模型契约单测：编号连续性、截断、空结果语义 */
class RagSourceTest {

    @Test
    @DisplayName("of()：标题缺失兜底「未命名文档」")
    void nullTitleFallback() {
        RagSource s = RagSource.of(1, null, "片段内容");
        assertThat(s.docTitle()).isEqualTo("未命名文档");
        assertThat(s.sourceNo()).isEqualTo(1);
        assertThat(s.score()).isNull();
    }

    @Test
    @DisplayName("of()：带分数构造保留 score（检索测试展示相似度）")
    void scorePreserved() {
        RagSource s = RagSource.of(1, "文档", "片段", 0.83);
        assertThat(s.score()).isEqualTo(0.83);
    }

    @Test
    @DisplayName("of()：空白标题兜底；摘要超长截断加省略号")
    void blankTitleAndSnippetTruncation() {
        RagSource s = RagSource.of(2, "  ", "a".repeat(200));
        assertThat(s.docTitle()).isEqualTo("未命名文档");
        assertThat(s.snippet()).hasSize(121); // 120 + "…"
        assertThat(s.snippet()).endsWith("…");
    }

    @Test
    @DisplayName("摘要未超长时原样保留")
    void snippetWithinLimitUnchanged() {
        assertThat(RagSource.of(1, "t", "hello").snippet()).isEqualTo("hello");
    }

    @Test
    @DisplayName("empty()：空结果且未截断；isEmpty 判定正确")
    void emptyResult() {
        RagSearchResult r = RagSearchResult.empty();
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.truncated()).isFalse();
        assertThat(r.sources()).isEmpty();
    }

    @Test
    @DisplayName("带来源且 truncated=true 的结果非空")
    void truncatedResultNotEmpty() {
        RagSearchResult r = new RagSearchResult(
                java.util.List.of(RagSource.of(1, "d", "x")), true);
        assertThat(r.isEmpty()).isFalse();
        assertThat(r.truncated()).isTrue();
    }
}
