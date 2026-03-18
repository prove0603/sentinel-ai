package com.zhuangjie.sentinel.git;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API client.
 * Uses Bearer token authentication.
 */
@Slf4j
public class GitHubClient implements GitPlatformClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public List<BranchInfo> listBranches(String apiUrl, String token, String projectPath)
            throws IOException, InterruptedException {
        String url = apiUrl + "/repos/" + projectPath + "/branches?per_page=100";
        JSONArray arr = getJsonArray(url, token);

        List<BranchInfo> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject b = arr.getJSONObject(i);
            JSONObject commit = b.getJSONObject("commit");
            String sha = commit != null ? commit.getStr("sha") : null;

            String message = null;
            String date = null;
            if (commit != null) {
                JSONObject commitDetail = commit.getJSONObject("commit");
                if (commitDetail != null) {
                    message = commitDetail.getStr("message");
                    JSONObject committer = commitDetail.getJSONObject("committer");
                    if (committer != null) {
                        date = committer.getStr("date");
                    }
                }
            }

            result.add(new BranchInfo(b.getStr("name"), sha, message, date));
        }
        return result;
    }

    @Override
    public List<CommitInfo> listCommits(String apiUrl, String token, String projectPath, String branch, int limit)
            throws IOException, InterruptedException {
        String url = apiUrl + "/repos/" + projectPath + "/commits?sha=" + branch + "&per_page=" + Math.min(limit, 100);
        JSONArray arr = getJsonArray(url, token);

        List<CommitInfo> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String sha = c.getStr("sha");
            JSONObject commitDetail = c.getJSONObject("commit");

            String title = commitDetail != null ? commitDetail.getStr("message") : null;
            if (title != null && title.contains("\n")) {
                title = title.substring(0, title.indexOf('\n'));
            }

            String author = null;
            String createdAt = null;
            if (commitDetail != null) {
                JSONObject authorObj = commitDetail.getJSONObject("author");
                if (authorObj != null) {
                    author = authorObj.getStr("name");
                    createdAt = authorObj.getStr("date");
                }
            }

            List<String> parentIds = new ArrayList<>();
            JSONArray parents = c.getJSONArray("parents");
            if (parents != null) {
                for (int j = 0; j < parents.size(); j++) {
                    parentIds.add(parents.getJSONObject(j).getStr("sha"));
                }
            }

            String shortId = sha != null && sha.length() > 8 ? sha.substring(0, 8) : sha;
            result.add(new CommitInfo(sha, shortId, title, author, createdAt, parentIds));
        }
        return result;
    }

    @Override
    public DiffInfo compare(String apiUrl, String token, String projectPath, String fromCommit, String toCommit)
            throws IOException, InterruptedException {
        String url = apiUrl + "/repos/" + projectPath + "/compare/" + fromCommit + "..." + toCommit;
        JSONObject json = getJsonObject(url, token);

        List<DiffFile> files = new ArrayList<>();
        JSONArray fileArr = json.getJSONArray("files");
        if (fileArr != null) {
            for (int i = 0; i < fileArr.size(); i++) {
                JSONObject f = fileArr.getJSONObject(i);
                files.add(new DiffFile(
                        f.getStr("filename"),
                        f.getStr("status"),
                        f.getInt("additions", 0),
                        f.getInt("deletions", 0)
                ));
            }
        }

        return new DiffInfo(fromCommit, toCommit, files);
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
        log.debug("[GitHub] GET {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("GitHub API error " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
