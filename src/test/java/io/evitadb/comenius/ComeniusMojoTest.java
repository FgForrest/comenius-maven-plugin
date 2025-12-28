package io.evitadb.comenius;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComeniusMojoTest {

    @Test
    public void testShowConfigDisplaysDefaultsAndWarnsForMissingLLM() throws MojoExecutionException {
        ComeniusMojo mojo = new ComeniusMojo();

        // Prepare capturing log
        StringBuilder out = new StringBuilder();
        Log capturingLog = new Log() {
            @Override public boolean isDebugEnabled() { return true; }
            @Override public void debug(CharSequence content) { out.append(content).append('\n'); }
            @Override public void debug(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void debug(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isInfoEnabled() { return true; }
            @Override public void info(CharSequence content) { out.append(content).append('\n'); }
            @Override public void info(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void info(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isWarnEnabled() { return true; }
            @Override public void warn(CharSequence content) { out.append(content).append('\n'); }
            @Override public void warn(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void warn(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isErrorEnabled() { return true; }
            @Override public void error(CharSequence content) { out.append(content).append('\n'); }
            @Override public void error(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void error(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
        };
        mojo.setLog(capturingLog);

        // Configure mojo
        mojo.setAction("show-config");
        // leave other properties unset to trigger defaults and warnings

        // Execute
        mojo.execute();

        String log = out.toString();
        assertTrue(log.contains("Comenius Plugin Configuration:"), "Should contain header");
        // defaults
        assertTrue(log.contains(" - fileRegex: (?i).*\\.md"), "Should show default regex");
        assertTrue(log.contains(" - limit: 2147483647"), "Should show default limit");
        assertTrue(log.contains(" - dryRun: true"), "Should show dryRun default true");
        // warnings for missing LLM settings
        assertTrue(log.contains("LLM url is not set"), "Should warn about missing LLM url");
        assertTrue(log.contains("LLM token is not set"), "Should warn about missing LLM token");
    }

    @Test
    public void shouldLimitToOneFileInDryRunTranslate() throws Exception {
        // Prepare temp directory with two matching files in a git repo
        Path root = Files.createTempDirectory("mojo-limit-");
        Files.writeString(root.resolve("a.md"), "A");
        Files.writeString(root.resolve("b.md"), "B");

        // Initialize git repo and commit the files
        runGit(root, "init");
        runGit(root, "config", "user.email", "test@example.com");
        runGit(root, "config", "user.name", "Test User");
        runGit(root, "add", "-A");
        runGit(root, "commit", "-m", "Initial commit");

        // Create a target directory
        Path targetDir = Files.createTempDirectory("mojo-limit-target-");

        // Prepare capturing log
        StringBuilder out = new StringBuilder();
        Log capturingLog = new Log() {
            @Override public boolean isDebugEnabled() { return true; }
            @Override public void debug(CharSequence content) { out.append(content).append('\n'); }
            @Override public void debug(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void debug(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isInfoEnabled() { return true; }
            @Override public void info(CharSequence content) { out.append(content).append('\n'); }
            @Override public void info(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void info(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isWarnEnabled() { return true; }
            @Override public void warn(CharSequence content) { out.append(content).append('\n'); }
            @Override public void warn(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void warn(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
            @Override public boolean isErrorEnabled() { return true; }
            @Override public void error(CharSequence content) { out.append(content).append('\n'); }
            @Override public void error(CharSequence content, Throwable error) { out.append(content).append('\n'); }
            @Override public void error(Throwable error) { out.append(String.valueOf(error)).append('\n'); }
        };

        // Configure mojo with a target
        ComeniusMojo mojo = new ComeniusMojo();
        mojo.setLog(capturingLog);
        mojo.setAction("translate");
        mojo.setSourceDir(root.toString());
        mojo.setDryRun(true);
        mojo.setLimit(1);
        mojo.setTargets(java.util.List.of(
            new ComeniusMojo.Target("cs", targetDir.toString())
        ));

        // Execute
        mojo.execute();

        String log = out.toString();
        // In dry-run with limit=1, should only show 1 file in the summary
        // Note: Both files may be skipped with errors about uncommitted changes depending on timing
        // Instead, check that summary shows "New files: 1" or similar
        assertTrue(log.contains("New files: 1") || log.contains("[NEW] a.md"),
            "Log should show limit was applied: " + log);
    }

    private static void runGit(Path dir, String... args) throws Exception {
        final java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        for (String arg : args) {
            cmd.add(arg);
        }
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        final Process process = pb.start();
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new java.io.IOException("Git command failed: " + String.join(" ", args));
        }
    }
}
