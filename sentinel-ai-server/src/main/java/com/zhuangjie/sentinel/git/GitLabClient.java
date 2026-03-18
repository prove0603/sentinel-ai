package com.zhuangjie.sentinel.git;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitLab REST API v4 client.
 * Uses PRIVATE-TOKEN header for authentication.
 */
@Slf4j
public class GitLabClient implements GitPlatformClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public List<BranchInfo> listBranches(String apiUrl, String token, String projectPath)
            throws IOException, InterruptedException {
        String url = apiUrl + "/api/v4/projects/" + encodePath(projectPath) + "/repository/branches?per_page=100";
        JSONArray arr = getJsonArray(url, token);

        List<BranchInfo> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject b = arr.getJSONObject(i);
            JSONObject commit = b.getJSONObject("commit");
            result.add(new BranchInfo(
                    b.getStr("name"),
                    commit != null ? commit.getStr("id") : null,
                    commit != null ? commit.getStr("title") : null,
                    commit != null ? commit.getStr("committed_date") : null
            ));
        }
        return result;
    }

    @Override
    public List<CommitInfo> listCommits(String apiUrl, String token, String projectPath, String branch, int limit)
            throws IOException, InterruptedException {
        String url = apiUrl + "/api/v4/projects/" + encodePath(projectPath)
                + "/repository/commits?ref_name=" + URLEncoder.encode(branch, StandardCharsets.UTF_8)
                + "&per_page=" + Math.min(limit, 100);
        JSONArray arr = getJsonArray(url, token);

        List<CommitInfo> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            List<String> parentIds = new ArrayList<>();
            JSONArray parents = c.getJSONArray("parent_ids");
            if (parents != null) {
                for (int j = 0; j < parents.size(); j++) {
                    parentIds.add(parents.getStr(j));
                }
            }
            result.add(new CommitInfo(
                    c.getStr("id"),
                    c.getStr("short_id"),
                    c.getStr("title"),
                    c.getStr("author_name"),
                    c.getStr("committed_date"),
                    parentIds
            ));
        }
        return result;
    }

    @Override
    public DiffInfo compare(String apiUrl, String token, String projectPath, String fromCommit, String toCommit)
            throws IOException, InterruptedException {
        String url = apiUrl + "/api/v4/projects/" + encodePath(projectPath)
                + "/repository/compare?from=" + fromCommit + "&to=" + toCommit;
        JSONObject json = getJsonObject(url, token);

        List<DiffFile> files = new ArrayList<>();
        JSONArray diffs = json.getJSONArray("diffs");
        if (diffs != null) {
            for (int i = 0; i < diffs.size(); i++) {
                JSONObject d = diffs.getJSONObject(i);
                String path = d.getStr("new_path");
                boolean newFile = d.getBool("new_file", false);
                boolean deleted = d.getBool("deleted_file", false);
                boolean renamed = d.getBool("renamed_file", false);
                String status = deleted ? "deleted" : newFile ? "added" : renamed ? "renamed" : "modified";

                String diff = d.getStr("diff");
                int additions = 0, deletions = 0;
                if (diff != null) {
                    for (String line : diff.split("\n")) {
                        if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                        else if (line.startsWith("-") && !line.startsWith("---")) deletions++;
                    }
                }
                files.add(new DiffFile(path, status, additions, deletions));
            }
        }

        return new DiffInfo(
                json.getByPath("commit.id", String.class) != null ? fromCommit : fromCommit,
                toCommit,
                files
        );
    }

    private String encodePath(String projectPath) {
        String path = projectPath.startsWith("/") ? projectPath.substring(1) : projectPath;
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private JSONArray getJsonArray(String url, String token) throws IOException, InterruptedException {
        String body = doGet(url, token);
        return JSONUtil.parseArray(body);
    }

    private JSONObject getJsonObject(String url, String token) throws IOException, InterruptedException {
        String body = doGet(url, token);
        return JSONUtil.parseObj(body);
    }

    private String doGet(String url, String token) throws IOException, InterruptedException {
        log.debug("[GitLab] GET {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("GitLab API error " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
