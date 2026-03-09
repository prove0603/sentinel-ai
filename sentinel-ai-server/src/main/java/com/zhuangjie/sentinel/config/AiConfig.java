package com.zhuangjie.sentinel.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient sqlAnalysisChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        你是一个资深的 MySQL DBA，拥有 10 年以上数据库性能优化经验。
                        你的任务是分析 SQL 语句的性能风险，给出准确的判断和可执行的优化建议。
                        你需要结合表结构、索引信息、数据量来做出判断。
                        分析时请使用中文回复。
                        """)
                .build();
    }
}
