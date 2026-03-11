package com.zhuangjie.sentinel.config;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnProperty(name = "sentinel.ai.enabled", havingValue = "true")
    public MultiModalConversation multiModalConversation() {
        return new MultiModalConversation();
    }
}
