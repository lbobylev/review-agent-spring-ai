package review;

import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class WorkspaceTools {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".angular", ".git", ".gradle", ".venv", "__pycache__", "build",
            "coverage", "dist", "node_modules", "out", "target"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".css", ".gradle", ".html", ".java", ".js", ".json", ".jsx", ".kt",
            ".kts", ".md", ".properties", ".py", ".sass", ".scss", ".toml", ".ts",
            ".tsx", ".txt", ".xml", ".yaml", ".yml"
    );
    private static final long MAX_FILE_BYTES = 200_000;
    private static final int MAX_SEARCH_MATCHES = 100;
    private static final int MAX_GLOB_MATCHES = 100;

    private final Path workspaceRoot;

    WorkspaceTools(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    @Tool(description = "Read a UTF-8 text file from the current workspace.")
    String readFile(String path) {
        Path filePath = resolve(path);
        String pathError = validateInsideWorkspace(filePath, path);
        if (pathError != null) {
            return pathError;
        }
        if (!Files.exists(filePath)) {
            return "Error: file does not exist: " + path;
        }
        if (!Files.isRegularFile(filePath)) {
            return "Error: path is not a file: " + path;
        }
        if (!isAllowedFile(filePath)) {
            return "Error: file extension is not allowed: " + path;
        }

        try {
            if (Files.size(filePath) > MAX_FILE_BYTES) {
                return "Error: file is too large: " + path;
            }
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "Error: failed to read file: " + error.getMessage();
        }
    }

    @Tool(description = "Search workspace text files for a literal string.")
    String searchText(String query, String path) {
        if (query == null) {
            return "Error: query is missing";
        }
        Path searchPath = resolve(path == null || path.isBlank() ? "." : path);
        String pathError = validateInsideWorkspace(searchPath, path);
        if (pathError != null) {
            return pathError;
        }
        if (!Files.exists(searchPath)) {
            return "Error: path does not exist: " + path;
        }

        List<String> matches = new ArrayList<>();
        Path rootReal;
        try {
            rootReal = workspaceRoot.toRealPath();
        } catch (IOException error) {
            return "Error: failed to validate workspace: " + error.getMessage();
        }
        try (Stream<Path> stream = Files.isRegularFile(searchPath) ? Stream.of(searchPath) : Files.walk(searchPath)) {
            for (Path filePath : stream.sorted().toList()) {
                if (matches.size() >= MAX_SEARCH_MATCHES) {
                    break;
                }
                Path realPath = safeWorkspaceFileRealPath(filePath, rootReal, true);
                if (realPath == null) {
                    continue;
                }

                List<String> lines;
                try {
                    lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                } catch (IOException error) {
                    continue;
                }
                Path relativePath = rootReal.relativize(realPath);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains(query)) {
                        matches.add("%s:%d: %s".formatted(relativePath, index + 1, line.substring(0, Math.min(line.length(), 500))));
                        if (matches.size() >= MAX_SEARCH_MATCHES) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException error) {
            return "Error: failed to search files: " + error.getMessage();
        }

        if (matches.isEmpty()) {
            return "No matches found.";
        }
        String result = String.join("\n", matches);
        if (matches.size() == MAX_SEARCH_MATCHES) {
            result += "\n... stopped after " + MAX_SEARCH_MATCHES + " matches";
        }
        return result;
    }

    @Tool(description = "Find workspace files matching a glob pattern.")
    String glob(String pattern, String path) {
        Path searchPath = resolve(path == null || path.isBlank() ? "." : path);
        String pathError = validateInsideWorkspace(searchPath, path);
        if (pathError != null) {
            return pathError;
        }
        if (!Files.exists(searchPath)) {
            return "Error: path does not exist: " + path;
        }
        if (!Files.isDirectory(searchPath)) {
            return "Error: path is not a directory: " + path;
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        Path rootReal;
        Path searchReal;
        try {
            rootReal = workspaceRoot.toRealPath();
            searchReal = searchPath.toRealPath();
        } catch (IOException error) {
            return "Error: failed to validate workspace: " + error.getMessage();
        }
        try (Stream<Path> stream = Files.walk(searchPath)) {
            for (Path filePath : stream.sorted(Comparator.naturalOrder()).toList()) {
                if (matches.size() >= MAX_GLOB_MATCHES) {
                    break;
                }
                Path resolved = safeWorkspaceFileRealPath(filePath, rootReal, false);
                if (resolved == null) {
                    continue;
                }
                Path relativeToSearchPath = searchReal.relativize(resolved);
                if (matcher.matches(relativeToSearchPath)) {
                    matches.add(rootReal.relativize(resolved).toString());
                }
            }
        } catch (IOException error) {
            return "Error: failed to glob files: " + error.getMessage();
        }

        if (matches.isEmpty()) {
            return "No matches found.";
        }
        String result = String.join("\n", matches);
        if (matches.size() == MAX_GLOB_MATCHES) {
            result += "\n... stopped after " + MAX_GLOB_MATCHES + " matches";
        }
        return result;
    }

    private Path resolve(String path) {
        String cleanPath = path == null || path.isBlank() ? "." : path;
        return workspaceRoot.resolve(cleanPath).toAbsolutePath().normalize();
    }

    private String validateInsideWorkspace(Path path, String originalPath) {
        if (!path.startsWith(workspaceRoot)) {
            return "Error: path is outside workspace: " + originalPath;
        }
        if (Files.isSymbolicLink(path)) {
            return "Error: symbolic links are not allowed: " + originalPath;
        }
        if (Files.exists(path)) {
            try {
                Path rootReal = workspaceRoot.toRealPath();
                Path pathReal = path.toRealPath();
                if (!pathReal.startsWith(rootReal)) {
                    return "Error: path is outside workspace: " + originalPath;
                }
            } catch (IOException error) {
                return "Error: failed to validate path: " + error.getMessage();
            }
        }
        return null;
    }

    private Path safeWorkspaceFileRealPath(Path path, Path rootReal, boolean enforceSizeLimit) {
        try {
            if (Files.isSymbolicLink(path)) {
                return null;
            }
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(rootReal) || isSkipped(realPath) || !Files.isRegularFile(realPath) || !isAllowedFile(realPath)) {
                return null;
            }
            if (enforceSizeLimit && Files.size(path) > MAX_FILE_BYTES) {
                return null;
            }
            return realPath;
        } catch (IOException error) {
            return null;
        }
    }

    private boolean isAllowedFile(Path path) {
        String fileName = path.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex < 0) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(fileName.substring(extensionIndex));
    }

    private boolean isSkipped(Path path) {
        for (Path part : path) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
