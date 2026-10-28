package com.library.config;

import com.library.security.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置（CORS + JWT 拦截器注册 + 上传文件静态映射）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    /** CORS 白名单：逗号分隔的允许来源，默认仅本机前端开发地址 */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/auth/wechat-login", "/error", "/uploads/**");
        // 限流拦截器须在 JWT 之后注册：依赖 CurrentUser 识别用户身份做限流 key
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传的图片以静态资源方式对外访问（无鉴权，img 标签可直接加载）
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + Paths.get(uploadDir).toAbsolutePath().normalize() + "/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // CORS 收敛为白名单制：仅放行 app.cors.allowed-origins 中配置的来源。
        // 原先 allowedOriginPatterns("*") + allowCredentials(true) 等于对任意站点
        // 开放携带凭据的跨域访问，属安全问题；局域网设备请把对应来源加入白名单配置。
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
