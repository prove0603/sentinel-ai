package com.zhuangjie.sentinel.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 企业微信群机器人通知配置。
 * <p>
 * 通过 sentinel.notification.wecom.* 配置项启用，
 * 未配置或 enabled=false 时自动禁用，不影响应用启动。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sentinel.notification.wecom")
public class WeComConfig {

    private boolean enabled = false;

    /** 企业微信群机器人 Webhook URL */
    private String webhookUrl;

    /** Sentinel AI 前端访问地址（用于消息中的跳转链接） */
    private String frontendUrl = "http://localhost:8090";
}
