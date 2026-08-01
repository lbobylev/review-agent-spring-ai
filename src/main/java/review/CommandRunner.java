package review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class CommandRunner {

    private static final long COMMAND_TIMEOUT_MINUTES = 2;

    String run(List<String> command) {
        return run(command, null);
    }

    String run(List<String> command, Path cwd) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new ReviewException("Error: %s is not installed or not in PATH".formatted(command.getFirst()), error);
        }

        try {
            ExecutorService outputReader = Executors.newSingleThreadExecutor();
            Future<byte[]> stdoutFuture = outputReader.submit(() -> process.getInputStream().readAllBytes());
            byte[] stdout;
            int exitCode;
            try {
                boolean completed = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (!completed) {
                    process.destroyForcibly();
                    stdoutFuture.cancel(true);
                    throw new ReviewException("Error: command timed out: " + command.getFirst());
                }
                exitCode = process.exitValue();
                stdout = stdoutFuture.get();
            } finally {
                outputReader.shutdownNow();
            }

            String out = new String(stdout, StandardCharsets.UTF_8);
            if (exitCode != 0) {
                String message = !out.isBlank() ? out.strip() : "exit code " + exitCode;
                throw new ReviewException("Error: failed to run %s: %s".formatted(String.join(" ", command), message));
            }

            return out;
        } catch (ExecutionException error) {
            throw new ReviewException("Error: failed to read command output: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ReviewException("Error: command interrupted: " + String.join(" ", command), error);
        }
    }
}
