package review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import review.ReviewModels.FindingCategory;
import review.ReviewModels.GhReviewComment;
import review.ReviewModels.GhReviewPayload;
import review.ReviewModels.LineRef;
import review.ReviewModels.PullRequestRefs;
import review.ReviewModels.ReviewFinding;
import review.ReviewModels.ReviewFindingsPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
final class GithubReviewService {

    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path WORKSPACE_PATH = PROJECT_DIR.resolve("workspace/review-spring-ai");
    private static final Path REPO_PATH = WORKSPACE_PATH.resolve("repo");
    private static final Path PR_DIFF_PATH = WORKSPACE_PATH.resolve("pr.diff");
    private static final Path CHANGED_FILES_PATH = WORKSPACE_PATH.resolve("changed_files.txt");
    private static final Path REVIEW_PAYLOAD_PATH = WORKSPACE_PATH.resolve("review.json");
    private static final long MAX_DIFF_BYTES = 1_000_000;
    private static final int MAX_REVIEW_COMMENTS = 10;
    private static final int MAX_COMMENT_BODY_CHARS = 2_000;
    private static final Set<FindingCategory> BLOCKING_CATEGORIES = Set.of(
            FindingCategory.bug,
            FindingCategory.security,
            FindingCategory.data_loss
    );

    private final CommandRunner commandRunner = new CommandRunner();
    private final DiffParser diffParser = new DiffParser();
    private final ReviewAgent reviewAgent;
    private final ObjectMapper objectMapper;

    GithubReviewService(ReviewAgent reviewAgent, ObjectMapper objectMapper) {
        this.reviewAgent = reviewAgent;
        this.objectMapper = objectMapper;
    }

    GhReviewPayload run(String repo, int prNumber) {
        requireOpenAiKey();
        System.err.printf("Loading PR #%d from %s...%n", prNumber, repo);
        PullRequestRefs refs = prepareWorkspace(repo, prNumber);
        System.err.printf("Prepared workspace for %s#%d: %s...%s%n", repo, prNumber, refs.baseRefName(), refs.headRefName());

        String diff = readLimitedText(PR_DIFF_PATH, MAX_DIFF_BYTES);
        String changedFiles = readLimitedText(CHANGED_FILES_PATH, MAX_DIFF_BYTES);
        ReviewFindingsPayload findingsPayload = reviewAgent.review(REPO_PATH, diff, changedFiles);
        Set<LineRef> allowedLines = diffParser.extractRightSideLines(diff);
        return buildReviewPayload(findingsPayload, allowedLines);
    }

    void submitReview(String repo, int prNumber, GhReviewPayload review) {
        if (review.comments().isEmpty()) {
            System.err.println("No inline comments to submit; skipping GitHub review submission.");
            return;
        }

        GhReviewPayload submissionPayload = buildSubmissionPayload(review);
        writeJson(REVIEW_PAYLOAD_PATH, submissionPayload);
        System.err.printf("Submitting %d review comment(s) to %s#%d...%n", review.comments().size(), repo, prNumber);
        commandRunner.run(List.of(
                "gh", "api", "repos/%s/pulls/%d/reviews".formatted(repo, prNumber),
                "--method", "POST",
                "--input", REVIEW_PAYLOAD_PATH.toString()
        ));
    }

    String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ReviewException("Error: failed to serialize JSON: " + error.getOriginalMessage(), error);
        }
    }

    private PullRequestRefs prepareWorkspace(String repo, int prNumber) {
        PullRequestRefs refs = loadPrBranches(repo, prNumber);
        deleteDirectory(WORKSPACE_PATH);
        createDirectory(WORKSPACE_PATH);

        System.err.printf("Cloning repository %s into workspace...%n", repo);
        commandRunner.run(List.of("gh", "repo", "clone", repo, REPO_PATH.toString()));
        System.err.printf("Checking out PR #%d (%s)...%n", prNumber, refs.headRefName());
        commandRunner.run(List.of("gh", "pr", "checkout", Integer.toString(prNumber), "--repo", repo), REPO_PATH);

        writeText(PR_DIFF_PATH, commandRunner.run(List.of("gh", "pr", "diff", Integer.toString(prNumber), "--repo", repo)));
        writeText(CHANGED_FILES_PATH, commandRunner.run(List.of("gh", "pr", "diff", Integer.toString(prNumber), "--repo", repo, "--name-only")));
        return refs;
    }

    private PullRequestRefs loadPrBranches(String repo, int prNumber) {
        String output = commandRunner.run(List.of(
                "gh", "pr", "view", Integer.toString(prNumber),
                "--repo", repo,
                "--json", "baseRefName,headRefName"
        ));
        try {
            PullRequestRefs refs = objectMapper.readValue(output, PullRequestRefs.class);
            if (refs.baseRefName() == null || refs.baseRefName().isBlank()) {
                throw new ReviewException("Error: PR base branch is missing");
            }
            if (refs.headRefName() == null || refs.headRefName().isBlank()) {
                throw new ReviewException("Error: PR head branch is missing");
            }
            return refs;
        } catch (JsonProcessingException error) {
            throw new ReviewException("Error: failed to read PR base/head branches from gh output", error);
        }
    }

    private GhReviewPayload buildReviewPayload(ReviewFindingsPayload findingsPayload, Set<LineRef> allowedLines) {
        List<ReviewFinding> validFindings = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ReviewFinding finding : findingsPayload.findings()) {
            String path = finding.path().strip();
            String body = finding.body().strip();
            if (path.isBlank() || body.isBlank()) {
                continue;
            }
            if (body.length() > MAX_COMMENT_BODY_CHARS) {
                body = body.substring(0, MAX_COMMENT_BODY_CHARS).stripTrailing();
            }
            if (!allowedLines.contains(new LineRef(path, finding.line()))) {
                continue;
            }

            String dedupeKey = path + "\0" + finding.line() + "\0" + body.toLowerCase();
            if (!seen.add(dedupeKey)) {
                continue;
            }

            validFindings.add(new ReviewFinding(path, finding.line(), body, finding.blocking(), finding.category()));
            if (validFindings.size() >= MAX_REVIEW_COMMENTS) {
                break;
            }
        }

        List<GhReviewComment> comments = validFindings.stream()
                .map(finding -> new GhReviewComment(finding.path(), finding.line(), finding.body()))
                .toList();
        boolean hasBlockingFinding = validFindings.stream()
                .anyMatch(finding -> finding.blocking() && BLOCKING_CATEGORIES.contains(finding.category()));

        return new GhReviewPayload(
                findingsPayload.body(),
                hasBlockingFinding ? "REQUEST_CHANGES" : "COMMENT",
                comments
        );
    }

    private GhReviewPayload buildSubmissionPayload(GhReviewPayload review) {
        return new GhReviewPayload(
                review.body(),
                review.event(),
                review.comments().stream()
                        .map(comment -> new GhReviewComment(comment.path(), comment.line(), comment.side(), "✨ " + comment.body()))
                        .toList()
        );
    }

    private void requireOpenAiKey() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ReviewException("Error: OPENAI_API_KEY is not set");
        }
    }

    private String readLimitedText(Path path, long maxBytes) {
        if (!Files.exists(path)) {
            throw new ReviewException("Error: required file is missing: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ReviewException("Error: path is not a file: " + path);
        }
        try {
            if (Files.size(path) > maxBytes) {
                throw new ReviewException("Error: file is too large: " + path);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ReviewException("Error: failed to read file: " + error.getMessage(), error);
        }
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ReviewException("Error: failed to write file: " + error.getMessage(), error);
        }
    }

    private void writeText(Path path, String value) {
        try {
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ReviewException("Error: failed to write file: " + error.getMessage(), error);
        }
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException error) {
            throw new ReviewException("Error: failed to create directory: " + error.getMessage(), error);
        }
    }

    private void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (IOException error) {
            throw new ReviewException("Error: failed to delete workspace: " + error.getMessage(), error);
        }
    }
}
