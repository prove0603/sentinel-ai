package com.zhuangjie.sentinel.config;

import com.zhuangjie.sentinel.mcp.SqlAnalysisMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 配置。
 * <p>
 * 将 {@link SqlAnalysisMcpTools} 中的 @Tool 方法注册为 MCP 工具，
 * 通过 Streamable HTTP 协议（/mcp 端点）暴露给外部 MCP Client（如 business-qa）调用。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider sentinelMcpTools(SqlAnalysisMcpTools sqlAnalysisMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sqlAnalysisMcpTools)
                .build();
    }
}
