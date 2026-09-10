package com.library.service;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek 关闭思考模式的 HTTP 客户端装饰器。
 * <p>
 * 背景：DeepSeek V3.1+ / V4 系列模型<b>默认开启</b> thinking 模式，且无法通过
 * LangChain4j 的 builder 参数关闭（不传 thinking 字段即按默认开启处理）。
 * 当管理员在 AI 渠道中把「深度思考」开关关闭时，需要在请求体显式注入
 * {@code "thinking":{"type":"disabled"}} 才能真正关闭思维链输出
 * （更快、更省 Token，适合简单查询场景）。
 * <p>
 * 实现方式：装饰 langchain4j-http-client 的 HttpClient（与官方 LoggingHttpClient 同模式），
 * 仅对 DeepSeek 模型的请求体做字符串级注入；其他厂商请求体不加未知字段。
 * <p>
 * 注意：思考模式开启时不使用本装饰器——reasoning_content 的解析与多轮回传
 * 由 LangChain4j 1.20 的 returnThinking/sendThinking 原生处理。
 */
public class DeepSeekThinkingDisableHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekThinkingDisableHttpClient.class);

    /** 从请求体提取 model 字段值 */
    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient delegate;

    public DeepSeekThinkingDisableHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(patch(request));
    }

    @Override
    public void execute(HttpRequest request,
                        ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        delegate.execute(patch(request), parser, listener);
    }

    /**
     * 流式请求（LangChain4j 1.20 的流式对话走此路径，非 execute 方法）。
     * <p>
     * 必须重写并委托给 delegate，否则会落入 HttpClient 接口的 default 实现
     * （返回 failing publisher，导致流式对话报错）。
     */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request) {
        return delegate.stream(patch(request));
    }

    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request,
                                                      ServerSentEventParser parser) {
        return delegate.stream(patch(request), parser);
    }

    /** 按需改写请求 */
    private HttpRequest patch(HttpRequest request) {
        String body = request.body();
        String patched = injectThinkingDisabled(body);
        if (patched == body) {
            return request;
        }
        log.debug("[DeepSeekCompat] 深度思考已关闭，请求体注入 thinking:disabled");
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(request.headers())
                .body(patched)
                .build();
    }

    /**
     * 对 DeepSeek 系列模型在 JSON 请求体顶层注入 {@code "thinking":{"type":"disabled"}}。
     * 已存在 thinking 字段或非 DeepSeek 模型时原样返回。
     */
    static String injectThinkingDisabled(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (body.contains("\"thinking\"")) {
            return body; // 已显式配置，不重复注入
        }
        Matcher m = MODEL_PATTERN.matcher(body);
        if (!m.find()) {
            return body;
        }
        String model = m.group(1).toLowerCase();
        if (!model.contains("deepseek")) {
            return body; // 仅对 DeepSeek 模型生效，其他厂商请求体不加未知字段
        }
        int idx = body.indexOf('{');
        if (idx < 0) {
            return body;
        }
        return body.substring(0, idx + 1)
                + "\"thinking\":{\"type\":\"disabled\"},"
                + body.substring(idx + 1);
    }

    /**
     * 配套 Builder：内部构建 JdkHttpClient 并用兼容装饰器包装，
     * 超时配置透传给底层 JDK builder（LangChain4j 的 timeout() 会作用到此 builder）。
     */
    public static class Builder implements HttpClientBuilder {
        private final JdkHttpClientBuilder inner = new JdkHttpClientBuilder();

        @Override
        public HttpClient build() {
            return new DeepSeekThinkingDisableHttpClient(inner.build());
        }

        @Override
        public Duration connectTimeout() {
            return inner.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            inner.connectTimeout(timeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return inner.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            inner.readTimeout(timeout);
            return this;
        }
    }
}
