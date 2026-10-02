package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument.Kind;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides, from a path alone, whether a file inside an archive is worth reading
 * and what role it plays. Deliberately generic — an archive may be a software
 * project in any language, course material, documentation, a creative project
 * or a folder of notes — so it only knows broad conventions: dependency/build
 * output folders, binaries, lockfiles and secrets are skipped; prose, code,
 * build files and config are read. No semantic analysis happens here; that is
 * OpenAI's job.
 */
public final class FileClassifier {

    private FileClassifier() {
    }

    /** Folders that hold generated output, dependencies or tooling state, never the author's knowledge. */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "bower_components", "target", "build", "dist", "out",
            ".gradle", ".mvn", ".idea", ".vscode", ".vs", "__pycache__", ".pytest_cache", ".mypy_cache",
            "venv", ".venv", "env", ".tox", ".next", ".nuxt", ".svelte-kit", ".cache", "coverage",
            ".terraform", "__macosx", ".dart_tool", "pods", "deriveddata", "obj", "bin", "vendor");

    /** Binary / media / archive formats — listed in the structure, never read. */
    private static final Set<String> BINARY_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tif", "tiff", "psd", "ai", "sketch", "fig",
            "heic", "svgz", "mp3", "wav", "ogg", "flac", "m4a", "mp4", "mov", "avi", "mkv", "webm",
            "zip", "jar", "war", "ear", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar",
            "class", "o", "so", "dll", "dylib", "exe", "bin", "dat", "pyc", "pyo", "wasm",
            "ttf", "otf", "woff", "woff2", "eot", "db", "sqlite", "sqlite3", "keystore", "jks", "p12",
            "xls", "xlsx", "ppt", "pptx", "doc", "odt", "ods", "odp", "epub", "iso", "dmg", "apk", "ipa");

    /** Low-value generated files that would only burn tokens. */
    private static final Set<String> SKIPPED_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "pipfile.lock",
            "cargo.lock", "composer.lock", "gemfile.lock", "go.sum", "mvnw", "mvnw.cmd", "gradlew",
            "gradlew.bat", ".ds_store", "thumbs.db");

    /** Never read: likely secrets. */
    private static final Set<String> SECRET_EXT = Set.of("pem", "key", "crt", "cer", "der", "pfx", "asc", "gpg");

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "package.json", "requirements.txt", "pyproject.toml", "setup.py", "setup.cfg", "pipfile",
            "cargo.toml", "go.mod", "gemfile", "composer.json", "dockerfile", "docker-compose.yml",
            "docker-compose.yaml", "makefile", "cmakelists.txt", "tsconfig.json", "angular.json",
            "vite.config.ts", "vite.config.js", "next.config.js", "next.config.ts", "pubspec.yaml");

    private static final Set<String> CONFIG_EXT = Set.of(
            "yml", "yaml", "properties", "toml", "ini", "cfg", "conf", "env.example", "editorconfig");

    private static final Set<String> DOC_EXT = Set.of("md", "markdown", "txt", "rst", "adoc", "org", "tex", "pdf", "docx", "rtf");

    private static final Set<String> DATA_EXT = Set.of("json", "csv", "tsv", "xml", "graphql", "proto", "sql");

    /** Extension → language name, for code and markup. */
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("scala", "scala"), Map.entry("groovy", "groovy"), Map.entry("py", "python"),
            Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("go", "go"), Map.entry("rs", "rust"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
            Map.entry("cs", "csharp"), Map.entry("fs", "fsharp"), Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"), Map.entry("hpp", "cpp"), Map.entry("swift", "swift"),
            Map.entry("m", "objective-c"), Map.entry("dart", "dart"), Map.entry("lua", "lua"), Map.entry("r", "r"),
            Map.entry("sh", "shell"), Map.entry("bash", "shell"), Map.entry("zsh", "shell"), Map.entry("ps1", "powershell"),
            Map.entry("sql", "sql"), Map.entry("html", "html"), Map.entry("htm", "html"), Map.entry("css", "css"),
            Map.entry("scss", "scss"), Map.entry("sass", "sass"), Map.entry("less", "less"), Map.entry("vue", "vue"),
            Map.entry("svelte", "svelte"), Map.entry("xml", "xml"), Map.entry("json", "json"), Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"), Map.entry("toml", "toml"), Map.entry("properties", "properties"),
            Map.entry("gradle", "groovy"), Map.entry("tf", "terraform"), Map.entry("ex", "elixir"),
            Map.entry("exs", "elixir"), Map.entry("erl", "erlang"), Map.entry("hs", "haskell"),
            Map.entry("clj", "clojure"), Map.entry("svg", "svg"), Map.entry("ipynb", "jupyter"));

    /** Why a path is skipped, or null when it should be read. */
    public static String skipReason(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (SKIPPED_DIRS.contains(parts[i])) return "generated/dependency folder (" + parts[i] + "/)";
        }
        String name = parts[parts.length - 1];
        if (name.isEmpty()) return "folder";
        if (SKIPPED_FILES.contains(name)) return "generated file";
        if (name.startsWith("._")) return "OS metadata file";
        if (isSecretFile(name)) return "possible secrets — never read";
        String ext = extension(name);
        if (BINARY_EXT.contains(ext)) return "binary/media file";
        if (name.endsWith(".min.js") || name.endsWith(".min.css") || ext.equals("map")) return "minified/generated file";
        return null;
    }

    static boolean isSecretFile(String name) {
        if (name.equals(".env.example") || name.equals(".env.sample") || name.equals(".env.template")) return false;
        if (name.equals(".env") || name.startsWith(".env.")) return true;
        if (name.equals("id_rsa") || name.equals("id_ed25519") || name.equals(".npmrc") || name.equals(".pypirc")
                || name.equals(".netrc") || name.equals("credentials") || name.equals("credentials.json")) return true;
        return SECRET_EXT.contains(extension(name));
    }

    /** Role of a readable file. */
    public static Kind kindOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        String ext = extension(name);
        if (BUILD_FILES.contains(name) || name.endsWith(".gradle") || name.endsWith(".csproj")) return Kind.BUILD;
        if (isTest(lower)) return Kind.TEST;
        if (DOC_EXT.contains(ext) || name.startsWith("readme") || name.startsWith("changelog")
                || name.startsWith("license") || name.startsWith("contributing")) return Kind.DOCUMENT;
        if (CONFIG_EXT.contains(ext) || name.startsWith(".")) return Kind.CONFIG;
        if (DATA_EXT.contains(ext) && !ext.equals("sql")) return Kind.DATA;
        if (LANGUAGES.containsKey(ext)) return Kind.CODE;
        return Kind.DATA;
    }

    private static boolean isTest(String lowerPath) {
        String name = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        return lowerPath.contains("/test/") || lowerPath.startsWith("test/") || lowerPath.contains("/tests/")
                || lowerPath.startsWith("tests/") || lowerPath.contains("/__tests__/")
                || name.matches(".*(test|tests|spec)\\.[a-z0-9]+$") || name.startsWith("test_");
    }

    public static String languageOf(String path) {
        return LANGUAGES.get(extension(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)));
    }

    public static String extension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return (dot <= 0 || dot == base.length() - 1) ? "" : base.substring(dot + 1);
    }
}
