package com.codeforge.api;

import android.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public class GithubApi {

    private static final String BASE = "https://api.github.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;

    private static volatile GithubApi instance;
    public static GithubApi get() {
        if (instance == null) synchronized (GithubApi.class) {
            if (instance == null) instance = new GithubApi();
        }
        return instance;
    }

    private GithubApi() {
        http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer " + Config.TOKEN)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "CodeForge/1.0")
                    .build()
            )).build();
    }

    /** Create or update a file in the repo */
    public Result<JsonObject> putFile(String path, String content, String message) {
        String sha = getFileSha(path);
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("branch", Config.BRANCH);
        body.addProperty("content",
            Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        if (sha != null) body.addProperty("sha", sha);

        String url = BASE + "/repos/" + Config.OWNER + "/" + Config.REPO + "/contents/" + path;
        return put(url, body);
    }

    private String getFileSha(String path) {
        try {
            String url = BASE + "/repos/" + Config.OWNER + "/" + Config.REPO
                + "/contents/" + path + "?ref=" + Config.BRANCH;
            Result<JsonObject> r = doGet(url);
            if (r.ok && r.data != null && r.data.has("sha"))
                return r.data.get("sha").getAsString();
        } catch (Exception ignored) {}
        return null;
    }

    /** Trigger workflow_dispatch */
    public Result<Void> triggerBuild() {
        JsonObject body = new JsonObject();
        body.addProperty("ref", Config.BRANCH);
        String url = BASE + "/repos/" + Config.OWNER + "/" + Config.REPO
            + "/actions/workflows/" + Config.WORKFLOW + "/dispatches";
        return postVoid(url, body);
    }

    /** Get latest workflow run */
    public Result<JsonObject> getLatestRun() {
        String url = BASE + "/repos/" + Config.OWNER + "/" + Config.REPO
            + "/actions/workflows/" + Config.WORKFLOW + "/runs?per_page=1";
        return doGet(url);
    }

    /** Get single run by ID */
    public Result<JsonObject> getRun(long id) {
        return doGet(BASE + "/repos/" + Config.OWNER + "/" + Config.REPO + "/actions/runs/" + id);
    }

    /** List artifacts of a run */
    public Result<JsonObject> getArtifacts(long runId) {
        return doGet(BASE + "/repos/" + Config.OWNER + "/" + Config.REPO
            + "/actions/runs/" + runId + "/artifacts");
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private Result<JsonObject> doGet(String url) {
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            String body = r.body() != null ? r.body().string() : "{}";
            if (r.isSuccessful()) return Result.ok(JsonParser.parseString(body).getAsJsonObject());
            return Result.fail(r.code(), errMsg(body));
        } catch (IOException e) { return Result.err(e.getMessage()); }
    }

    private Result<JsonObject> put(String url, JsonObject payload) {
        Request req = new Request.Builder().url(url)
            .put(RequestBody.create(payload.toString(), JSON)).build();
        try (Response r = http.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "{}";
            if (r.isSuccessful()) return Result.ok(JsonParser.parseString(body).getAsJsonObject());
            return Result.fail(r.code(), errMsg(body));
        } catch (IOException e) { return Result.err(e.getMessage()); }
    }

    private Result<Void> postVoid(String url, JsonObject payload) {
        Request req = new Request.Builder().url(url)
            .post(RequestBody.create(payload.toString(), JSON)).build();
        try (Response r = http.newCall(req).execute()) {
            if (r.isSuccessful() || r.code() == 204) return Result.ok(null);
            String body = r.body() != null ? r.body().string() : "";
            return Result.fail(r.code(), errMsg(body));
        } catch (IOException e) { return Result.err(e.getMessage()); }
    }

    private static String errMsg(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("message")) return o.get("message").getAsString();
        } catch (Exception ignored) {}
        return json.isEmpty() ? "Unknown error" : json;
    }

    // ── Result ─────────────────────────────────────────────────────────────

    public static class Result<T> {
        public final boolean ok;
        public final T data;
        public final int code;
        public final String error;

        private Result(boolean ok, T d, int c, String e) {
            this.ok = ok; this.data = d; this.code = c; this.error = e;
        }

        public static <T> Result<T> ok(T d)              { return new Result<>(true,  d,    200, null); }
        public static <T> Result<T> fail(int c, String e) { return new Result<>(false, null, c,   e);   }
        public static <T> Result<T> err(String e)         { return new Result<>(false, null, -1,  e);   }
    }
}
