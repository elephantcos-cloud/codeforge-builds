package com.codeforge.api;

import android.util.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The core engine:
 * 1. User writes HTML/React/JS
 * 2. Engine wraps it in a full Android project
 * 3. Pushes to GitHub
 * 4. Triggers Actions build
 * 5. Polls until done
 * 6. Returns download URL
 */
public class BuildEngine {

    private static final String TAG = "BuildEngine";
    private final GithubApi api = GithubApi.get();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled = false;

    public enum Lang { HTML, REACT, JAVASCRIPT }

    public interface Listener {
        void onStep(String message, int progress); // progress 0-100
        void onDone(String downloadUrl);
        void onFail(String reason);
    }

    public void build(String appName, String pkg, String code, Lang lang, Listener l) {
        cancelled = false;
        exec.execute(() -> {
            try {
                run(appName, pkg, code, lang, l);
            } catch (InterruptedException e) {
                l.onFail("Build cancelled");
            } catch (Exception e) {
                l.onFail("Error: " + e.getMessage());
            }
        });
    }

    public void cancel() { cancelled = true; exec.shutdownNow(); }

    // ── Main pipeline ──────────────────────────────────────────────────────

    private void run(String appName, String pkg, String code, Lang lang, Listener l)
        throws InterruptedException {

        l.onStep("Preparing your app...", 5);

        String html = toHtml(appName, code, lang);
        String pkgPath = pkg.replace('.', '/');

        // ── Push files ──────────────────────────────────────────────────
        l.onStep("Pushing project files to GitHub...", 15);

        pushFile("settings.gradle.kts", settingsGradle(appName), l);
        checkCancel(l);
        pushFile("build.gradle.kts", rootBuildGradle(), l);
        checkCancel(l);
        pushFile("gradle.properties", gradleProps(), l);
        checkCancel(l);
        pushFile("app/build.gradle.kts", appBuildGradle(pkg), l);
        checkCancel(l);
        pushFile("app/src/main/AndroidManifest.xml", manifest(appName, pkg), l);
        checkCancel(l);
        pushFile("app/src/main/kotlin/" + pkgPath + "/MainActivity.kt",
            mainActivity(pkg), l);
        checkCancel(l);
        pushFile("app/src/main/assets/index.html", html, l);
        checkCancel(l);
        pushFile("app/src/main/res/values/strings.xml", strings(appName), l);
        checkCancel(l);
        pushFile("app/src/main/res/values/themes.xml", themes(), l);
        checkCancel(l);
        pushFile(".github/workflows/build.yml", workflow(), l);
        checkCancel(l);

        l.onStep("Triggering GitHub Actions build...", 35);
        Thread.sleep(1500);

        GithubApi.Result<Void> trigger = api.triggerBuild();
        if (!trigger.ok) {
            l.onFail("Could not trigger build: " + trigger.error
                + "\n\nMake sure the repo '" + Config.OWNER + "/" + Config.REPO
                + "' exists on GitHub.");
            return;
        }

        l.onStep("Build queued, waiting for runner...", 40);
        Thread.sleep(6000);

        long runId = findRunId(l);
        if (runId < 0) {
            l.onFail("Could not find build run. Please try again.");
            return;
        }

        // ── Poll ────────────────────────────────────────────────────────
        String conclusion = poll(runId, l);
        if (!"success".equals(conclusion)) {
            l.onFail("Build " + conclusion
                + ".\n\nCheck Actions tab at:\ngithub.com/"
                + Config.OWNER + "/" + Config.REPO);
            return;
        }

        // ── Artifact ────────────────────────────────────────────────────
        l.onStep("Build complete! Fetching download link...", 95);
        GithubApi.Result<JsonObject> arts = api.getArtifacts(runId);
        if (!arts.ok) { l.onFail("Build succeeded but artifacts not found."); return; }

        JsonArray arr = arts.data.getAsJsonArray("artifacts");
        if (arr.size() == 0) { l.onFail("No APK artifacts found."); return; }

        long artId = arr.get(0).getAsJsonObject().get("id").getAsLong();
        String url = "https://github.com/" + Config.OWNER + "/" + Config.REPO
            + "/actions/runs/" + runId + "/artifacts";

        l.onStep("Done!", 100);
        l.onDone(url);
    }

    private void pushFile(String path, String content, Listener l) {
        GithubApi.Result<JsonObject> r = api.putFile(path, content, "CodeForge: update " + path);
        if (!r.ok) Log.w(TAG, "Push warning for " + path + ": " + r.error);
    }

    private long findRunId(Listener l) throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            GithubApi.Result<JsonObject> r = api.getLatestRun();
            if (r.ok && r.data.has("workflow_runs")) {
                JsonArray runs = r.data.getAsJsonArray("workflow_runs");
                if (runs.size() > 0) return runs.get(0).getAsJsonObject().get("id").getAsLong();
            }
        }
        return -1;
    }

    private String poll(long runId, Listener l) throws InterruptedException {
        int polls = 0;
        while (polls++ < 80) {
            Thread.sleep(10000);
            checkCancel(l);

            GithubApi.Result<JsonObject> r = api.getRun(runId);
            if (!r.ok) continue;

            String status = r.data.get("status").getAsString();
            String conc = r.data.has("conclusion") && !r.data.get("conclusion").isJsonNull()
                ? r.data.get("conclusion").getAsString() : "";

            int pct = "queued".equals(status) ? 45
                : "in_progress".equals(status) ? Math.min(45 + polls, 92)
                : 95;

            l.onStep("Building APK... " + pct + "%", pct);

            if ("completed".equals(status)) return conc;
        }
        return "timed_out";
    }

    private void checkCancel(Listener l) {
        if (cancelled) { l.onFail("Cancelled"); throw new RuntimeException("Cancelled"); }
    }

    // ── HTML conversion ────────────────────────────────────────────────────

    private String toHtml(String appName, String code, Lang lang) {
        if (lang == Lang.HTML) return code;

        String head = "<!DOCTYPE html>\n<html>\n<head>\n"
            + "<meta charset='utf-8'>\n"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>\n"
            + "<title>" + appName + "</title>\n";

        if (lang == Lang.REACT) {
            return head
                + "<script src='https://unpkg.com/react@18/umd/react.production.min.js'></script>\n"
                + "<script src='https://unpkg.com/react-dom@18/umd/react-dom.production.min.js'></script>\n"
                + "<script src='https://unpkg.com/@babel/standalone/babel.min.js'></script>\n"
                + "</head>\n<body>\n<div id='root'></div>\n"
                + "<script type='text/babel'>\n" + code + "\n</script>\n</body>\n</html>";
        }

        // JavaScript
        return head + "</head>\n<body>\n<script>\n" + code + "\n</script>\n</body>\n</html>";
    }

    // ── Template strings ───────────────────────────────────────────────────

    private String settingsGradle(String appName) {
        return "pluginManagement {\n"
            + "    repositories { google(); mavenCentral(); gradlePluginPortal() }\n"
            + "}\n"
            + "dependencyResolutionManagement {\n"
            + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
            + "    repositories { google(); mavenCentral() }\n"
            + "}\n"
            + "rootProject.name = \"" + appName + "\"\n"
            + "include(\":app\")\n";
    }

    private String rootBuildGradle() {
        return "plugins {\n"
            + "    id(\"com.android.application\") version \"8.3.2\" apply false\n"
            + "    id(\"org.jetbrains.kotlin.android\") version \"1.9.22\" apply false\n"
            + "}\n";
    }

    private String gradleProps() {
        return "android.useAndroidX=true\n"
            + "android.enableJetifier=true\n"
            + "org.gradle.jvmargs=-Xmx3072m\n"
            + "org.gradle.daemon=false\n";
    }

    private String appBuildGradle(String pkg) {
        return "plugins {\n"
            + "    id(\"com.android.application\")\n"
            + "    id(\"org.jetbrains.kotlin.android\")\n"
            + "}\n"
            + "android {\n"
            + "    namespace = \"" + pkg + "\"\n"
            + "    compileSdk = 34\n"
            + "    defaultConfig {\n"
            + "        applicationId = \"" + pkg + "\"\n"
            + "        minSdk = 24\n"
            + "        targetSdk = 34\n"
            + "        versionCode = 1\n"
            + "        versionName = \"1.0\"\n"
            + "    }\n"
            + "    buildTypes { release { isMinifyEnabled = false } }\n"
            + "    compileOptions {\n"
            + "        sourceCompatibility = JavaVersion.VERSION_17\n"
            + "        targetCompatibility = JavaVersion.VERSION_17\n"
            + "    }\n"
            + "    kotlinOptions { jvmTarget = \"17\" }\n"
            + "}\n"
            + "dependencies {\n"
            + "    implementation(\"androidx.core:core-ktx:1.12.0\")\n"
            + "    implementation(\"androidx.appcompat:appcompat:1.6.1\")\n"
            + "}\n";
    }

    private String manifest(String appName, String pkg) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <uses-permission android:name=\"android.permission.INTERNET\"/>\n"
            + "    <application\n"
            + "        android:label=\"" + appName + "\"\n"
            + "        android:theme=\"@style/AppTheme\"\n"
            + "        android:supportsRtl=\"true\">\n"
            + "        <activity android:name=\".MainActivity\" android:exported=\"true\"\n"
            + "            android:configChanges=\"orientation|screenSize\">\n"
            + "            <intent-filter>\n"
            + "                <action android:name=\"android.intent.action.MAIN\"/>\n"
            + "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n"
            + "            </intent-filter>\n"
            + "        </activity>\n"
            + "    </application>\n"
            + "</manifest>\n";
    }

    private String mainActivity(String pkg) {
        return "package " + pkg + "\n\n"
            + "import android.os.Bundle\n"
            + "import android.webkit.WebChromeClient\n"
            + "import android.webkit.WebSettings\n"
            + "import android.webkit.WebView\n"
            + "import android.webkit.WebViewClient\n"
            + "import androidx.appcompat.app.AppCompatActivity\n\n"
            + "class MainActivity : AppCompatActivity() {\n"
            + "    override fun onCreate(s: Bundle?) {\n"
            + "        super.onCreate(s)\n"
            + "        val wv = WebView(this).also {\n"
            + "            it.settings.apply {\n"
            + "                javaScriptEnabled = true\n"
            + "                domStorageEnabled = true\n"
            + "                allowFileAccess = true\n"
            + "                useWideViewPort = true\n"
            + "                loadWithOverviewMode = true\n"
            + "                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW\n"
            + "            }\n"
            + "            it.webViewClient = WebViewClient()\n"
            + "            it.webChromeClient = WebChromeClient()\n"
            + "        }\n"
            + "        setContentView(wv)\n"
            + "        val html = assets.open(\"index.html\").bufferedReader().readText()\n"
            + "        wv.loadDataWithBaseURL(\"file:///android_asset/\", html, \"text/html\", \"UTF-8\", null)\n"
            + "    }\n"
            + "}\n";
    }

    private String strings(String appName) {
        return "<resources><string name=\"app_name\">" + appName + "</string></resources>\n";
    }

    private String themes() {
        return "<resources>\n"
            + "    <style name=\"AppTheme\" parent=\"Theme.AppCompat.Light.NoActionBar\">\n"
            + "        <item name=\"android:windowBackground\">@android:color/black</item>\n"
            + "    </style>\n"
            + "</resources>\n";
    }

    private String workflow() {
        return "name: Build APK\n"
            + "on:\n"
            + "  workflow_dispatch:\n"
            + "  push:\n"
            + "    branches: [ main ]\n"
            + "jobs:\n"
            + "  build:\n"
            + "    runs-on: ubuntu-latest\n"
            + "    timeout-minutes: 25\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n"
            + "          java-version: '17'\n"
            + "          distribution: 'temurin'\n"
            + "      - uses: gradle/actions/setup-gradle@v3\n"
            + "      - run: gradle wrapper --gradle-version 8.4\n"
            + "      - run: chmod +x gradlew\n"
            + "      - run: ./gradlew assembleDebug --no-daemon -Dorg.gradle.jvmargs=\"-Xmx3g\"\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n"
            + "          name: app-debug\n"
            + "          path: app/build/outputs/apk/debug/*.apk\n"
            + "          retention-days: 7\n";
    }
}
