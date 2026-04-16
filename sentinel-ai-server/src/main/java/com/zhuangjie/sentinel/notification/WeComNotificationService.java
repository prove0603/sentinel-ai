package com.zhuangjie.sentinel.notification;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhuangjie.sentinel.db.entity.ScanBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 企业微信群机器人通知服务。
 * <p>
 * 通过在 Markdown content 中拼接 {@code <@userid>} 实现真正的 @ 提醒效果。
 * 未配置时（enabled=false）不加载，不影响应用启动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sentinel.notification.wecom.enabled", havingValue = "true")
public class WeComNotificationService {

    private final WeComConfig config;
    private final UserMappingService userMappingService;

    /**
     * 发送"提醒开发人员"通知，通过 {@code <@userid>} 实现群内 @ 提醒。
     * wecom-name 字段存储的就是企业微信成员 UserId（如 suxinfa），用于 {@code <@userid>} 语法。
     */
    public void sendRemindNotification(String owner, String riskLevel, String sqlText,
                                       String sourceLocation, String projectName, Long sqlRecordId) {
        try {
            String truncatedSql = sqlText != null && sqlText.length() > 200
                    ? sqlText.substring(0, 200) + "..." : sqlText;
            String wecomUserId = userMappingService.getWecomName(owner);

            StringBuilder sb = new StringBuilder();
            sb.append("### Sentinel AI 风险 SQL 提醒\n");
            sb.append("> **项目：** ").append(projectName).append("\n");
            sb.append("> **风险等级：** <font color=\"warning\">").append(riskLevel).append("</font>\n");
            sb.append("> **来源：** ").append(sourceLocation).append("\n");
            sb.append("> **SQL：** `").append(truncatedSql).append("`\n");
            sb.append("> [查看详情](").append(config.getFrontendUrl())
                    .append("/#/sql-records?recordId=").append(sqlRecordId).append(")\n");
            String mentionId = wecomUserId != null ? wecomUserId : owner;
            if (mentionId != null && !mentionId.isBlank()) {
                sb.append(" \n <@").append(mentionId).append("> 请尽快处理，谢谢！");
            } else {
                sb.append("\n请尽快处理，谢谢！");
            }

            sendMarkdown(sb.toString());
            log.info("[WeCom] 提醒通知已发送: owner={}, wecomUserId={}, sqlRecordId={}", owner, wecomUserId, sqlRecordId);
        } catch (Exception e) {
            log.warn("[WeCom] 提醒通知发送失败: {}", e.getMessage());
            throw new RuntimeException("企业微信通知发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送扫描完成通知。
     */
    public void sendScanNotification(ScanBatch batch, String projectName, String gitAuthor) {
        try {
            String markdown = buildScanMarkdown(batch, projectName, gitAuthor);
            sendMarkdown(markdown);
            log.info("[WeCom] 扫描通知已发送: project={}, batchId={}", projectName, batch.getId());
        } catch (Exception e) {
            log.warn("[WeCom] 扫描通知发送失败: {}", e.getMessage());
        }
    }

    private String buildScanMarkdown(ScanBatch batch, String projectName, String gitAuthor) {
        boolean ok = "COMPLETED".equals(batch.getStatus());
        String wecomUserId = userMappingService.getWecomNameByGitName(gitAuthor);

        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "### ✅ Sentinel AI 扫描完成\n" : "### ❌ Sentinel AI 扫描失败\n");
        sb.append("> **项目：** ").append(projectName).append("\n");
        sb.append("> **类型：** ").append(batch.getScanType()).append("\n");
        if (batch.getScanDurationMs() != null) {
            sb.append("> **耗时：** ").append(formatDuration(batch.getScanDurationMs())).append("\n");
        }
        if (ok) {
            sb.append("> **SQL 总数：** ").append(n(batch.getTotalSqlCount())).append("\n");
            sb.append("> **新发现：** ").append(n(batch.getNewSqlCount())).append("\n");
            int risk = n(batch.getRiskSqlCount());
            sb.append(risk > 0
                    ? "> **<font color=\"warning\">风险 SQL：" + risk + "</font>**\n"
                    : "> **风险 SQL：** 0\n");
        }
        if (gitAuthor != null) sb.append("> **提交者：** ").append(gitAuthor).append("\n");
        if (wecomUserId != null && !wecomUserId.isBlank()) {
            sb.append(" \n <@").append(wecomUserId).append(">");
        }
        return sb.toString();
    }

    private void sendMarkdown(String content) {
        JSONObject markdown = new JSONObject();
        markdown.set("content", content);

        JSONObject body = new JSONObject();
        body.set("msgtype", "markdown");
        body.set("markdown", markdown);

        String resp = HttpUtil.post(config.getWebhookUrl(), body.toString());
        JSONObject result = JSONUtil.parseObj(resp);
        int errCode = result.getInt("errcode", -1);
        if (errCode != 0) {
            log.warn("[WeCom] API 返回错误: errcode={}, errmsg={}", errCode, result.getStr("errmsg"));
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
    }

    private int n(Integer v) { return v != null ? v : 0; }
}
