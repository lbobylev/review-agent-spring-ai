package review;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ReviewModels {

    private ReviewModels() {
    }

    public enum FindingCategory {
        bug,
        security,
        data_loss,
        performance,
        duplication,
        test_gap
    }

    public record ReviewFinding(
            String path,
            int line,
            String body,
            boolean blocking,
            FindingCategory category
    ) {
        @JsonCreator
        public ReviewFinding(
                @JsonProperty("path") String path,
                @JsonProperty("line") int line,
                @JsonProperty("body") String body,
                @JsonProperty("blocking") boolean blocking,
                @JsonProperty("category") FindingCategory category
        ) {
            this.path = path == null ? "" : path;
            this.line = line;
            this.body = body == null ? "" : body;
            this.blocking = blocking;
            this.category = category == null ? FindingCategory.bug : category;
        }
    }

    public record ReviewFindingsPayload(String body, List<ReviewFinding> findings) {
        @JsonCreator
        public ReviewFindingsPayload(
                @JsonProperty("body") String body,
                @JsonProperty("findings") List<ReviewFinding> findings
        ) {
            this.body = body == null ? "" : body;
            this.findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    public record GhReviewComment(String path, int line, String side, String body) {
        public GhReviewComment(String path, int line, String body) {
            this(path, line, "RIGHT", body);
        }
    }

    public record GhReviewPayload(String body, String event, List<GhReviewComment> comments) {
        public GhReviewPayload {
            body = body == null ? "" : body;
            event = event == null ? "COMMENT" : event;
            comments = comments == null ? List.of() : List.copyOf(comments);
        }
    }

    public record LineRef(String path, int line) {
    }

    public record PullRequestRefs(String baseRefName, String headRefName) {
    }
}
