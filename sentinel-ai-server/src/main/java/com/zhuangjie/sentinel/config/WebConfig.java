package com.zhuangjie.sentinel.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // JS/CSS 带 hash 的静态资源：长期缓存（365天）
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    /**
     * SPA 路由 fallback：前端路由路径统一转发到 index.html，
     * 让 Vue Router 在客户端处理路由。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:[^\\.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:[^\\.]*}/{subpath:[^\\.]*}")
                .setViewName("forward:/index.html");
    }

    /**
     * index.html 禁止缓存，确保每次都拿到最新的 JS/CSS hash 引用。
     * assets/ 下的文件因为文件名自带 hash，可以长期缓存。
     */
    @Bean
    public FilterRegistrationBean<Filter> noCacheIndexHtmlFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                String uri = req.getRequestURI();
                boolean isHtmlEntry = uri.equals("/") || uri.equals("/index.html")
                        || (!uri.startsWith("/api/") && !uri.startsWith("/assets/")
                            && !uri.startsWith("/mcp") && !uri.contains("."));
                if (isHtmlEntry) {
                    HttpServletResponse res = (HttpServletResponse) response;
                    res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                    res.setHeader("Pragma", "no-cache");
                    res.setDateHeader("Expires", 0);
                }
                chain.doFilter(request, response);
            }
        });
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
