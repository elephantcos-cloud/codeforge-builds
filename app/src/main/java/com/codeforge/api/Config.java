package com.codeforge.api;

/**
 * Hardcoded config — user never needs to enter anything.
 * All builds go to the same GitHub repo.
 */
public final class Config {

    // GitHub credentials — hardcoded
    public static final String TOKEN    = "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";
    public static final String OWNER    = "elephantcos-cloud";
    public static final String REPO     = "codeforge-builds";
    public static final String BRANCH   = "main";
    public static final String WORKFLOW = "build.yml";

    private Config() {}
}
