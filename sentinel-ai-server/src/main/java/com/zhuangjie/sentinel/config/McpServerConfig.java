package com.zhuangjie.sentinel.config;

import com.zhuangjie.sentinel.mcp.SqlAnalysisMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider sentinelMcpTools(SqlAnalysisMcpTools sqlAnalysisMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(sqlAnalysisMcpTools)
                .build();
    }
}
