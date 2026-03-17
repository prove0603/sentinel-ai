package com.zhuangjie.sentinel.knowledge;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the remote data query platform.
 * Handles SSO login, ticket validation, and SQL query execution.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sentinel.data-platform.enabled", havingValue = "true")
public class DataPlatformClient {

    private final DataPlatformConfig config;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile String sidCookie;

    public DataPlatformClient(DataPlatformConfig config) {
        this.config = config;
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        log.info("DataPlatformClient initialized: ssoUrl={}, platformUrl={}", config.getSsoUrl(), config.getPlatformUrl());
    }

    public void login() throws IOException, InterruptedException {
        String ssoBody = JSONUtil.toJsonStr(new JSONObject()
                .set("username", config.getUsername())
                .set("password", config.getPassword())
                .set("service", "")
                .set("platform", config.getPlatformName())
                .set("renew", "")
                .set("extra", ""));

        HttpRequest ssoRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getSsoUrl() + config.getSsoLoginPath()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json, text/plain, */*")
                .POST(HttpRequest.BodyPublishers.ofString(ssoBody))
                .build();

        HttpResponse<String> ssoResp = httpClient.send(ssoRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject ssoJson = JSONUtil.parseObj(ssoResp.body());

        String ticket = ssoJson.getByPath("results.ticket", String.class);
        if (ticket == null || ticket.isBlank()) {
            String redirectUrl = ssoJson.getStr("redirect_url");
            if (redirectUrl != null && redirectUrl.contains("ticket=")) {
                ticket = extractQueryParam(redirectUrl, "ticket");
            }
        }
        if (ticket == null || ticket.isBlank()) {
            throw new IOException("SSO login failed, no ticket found: " + ssoResp.body());
        }

        sidCookie = extractCookie(ssoResp, "sid");
        log.debug("SSO login successful, got ticket");

        HttpRequest validateRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getPlatformUrl() + config.getTicketValidatePath()
                        + "?ticket=" + ticket + "&service=&extra=&platform=" + config.getPlatformName()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("Cookie", "sid=" + sidCookie)
                .GET()
                .build();

        HttpResponse<String> validateResp = httpClient.send(validateRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject validateJson = JSONUtil.parseObj(validateResp.body());

        token = validateJson.getByPath("results.token", String.class);
        if (token == null || token.isBlank()) {
            throw new IOException("Ticket validation failed: " + validateResp.body());
        }

        log.info("Data platform login successful, token acquired");
    }

    /**
     * Executes a SQL command and returns result rows.
     * Each row is a List of String values, ordered by column.
     */
    public QueryResult executeQuery(String sql) throws IOException, InterruptedException {
        ensureLoggedIn();

        JSONObject body = new JSONObject()
                .set("command", sql)
                .set("hash_key", "")
                .set("domain", config.getDomain())
                .set("database", config.getDatabase())
                .set("source_type", config.getSourceType());

        HttpRequest batchRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getPlatformUrl() + config.getQueryBatchPath()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .header("Cookie", "sid=" + sidCookie + "; Admin-Token=" + token)
                .header("X-Token", token)
                .header("authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> batchResp = httpClient.send(batchRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject batchJson = JSONUtil.parseObj(batchResp.body());

        if (batchJson.getInt("code", -1) != 0) {
            throw new IOException("Query batch failed: " + batchResp.body());
        }

        JSONArray results = batchJson.getJSONArray("result");
        if (results == null || results.isEmpty()) {
            throw new IOException("No result in query batch response");
        }

        JSONObject firstResult = results.getJSONObject(0);
        String resultId = firstResult.getStr("result_id");
        String error = firstResult.getStr("error");
        if (error != null && !error.isBlank()) {
            throw new IOException("Query error: " + error);
        }

        return fetchResult(resultId);
    }

    private QueryResult fetchResult(String resultId) throws IOException, InterruptedException {
        HttpRequest resultRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.getPlatformUrl() + config.getQueryResultPath()
                        + "?result_id=" + resultId + "&page=1&page_size=10000"))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("Cookie", "sid=" + sidCookie + "; Admin-Token=" + token)
                .header("X-Token", token)
                .header("authorization", token)
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(resultRequest, HttpResponse.BodyHandlers.ofString());
        JSONObject json = JSONUtil.parseObj(resp.body());

        if (json.getInt("code", -1) != 0) {
            throw new IOException("Query result failed: " + resp.body());
        }

        JSONArray headers = json.getJSONArray("headers");
        JSONArray resultArray = json.getJSONArray("result");
        int total = json.getInt("total", 0);

        List<String> headerList = new ArrayList<>();
        if (headers != null) {
            for (int i = 0; i < headers.size(); i++) {
                headerList.add(headers.getStr(i));
            }
        }

        List<List<String>> rows = new ArrayList<>();
        if (resultArray != null) {
            for (int i = 0; i < resultArray.size(); i++) {
                String rowStr = resultArray.getStr(i);
                JSONArray rowArray = JSONUtil.parseArray(rowStr);
                List<String> row = new ArrayList<>();
                for (int j = 0; j < rowArray.size(); j++) {
                    row.add(rowArray.getStr(j));
                }
                rows.add(row);
            }
        }

        return new QueryResult(headerList, rows, total);
    }

    private void ensureLoggedIn() throws IOException, InterruptedException {
        if (token == null) {
            login();
        }
    }

    public void refreshToken() throws IOException, InterruptedException {
        token = null;
        sidCookie = null;
        login();
    }

    private String extractCookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1).split(";")[0])
                .findFirst()
                .orElse(null);
    }

    private String extractQueryParam(String url, String param) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) return null;
        String query = url.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    public record QueryResult(List<String> headers, List<List<String>> rows, int total) {
    }
}
