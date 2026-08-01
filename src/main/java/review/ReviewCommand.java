package review;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import review.ReviewModels.GhReviewPayload;

@Component
final class ReviewCommand implements CommandLineRunner {

    private final GithubReviewService githubReviewService;

    ReviewCommand(GithubReviewService githubReviewService) {
        this.githubReviewService = githubReviewService;
    }

    @Override
    public void run(String... args) {
        try {
            if (args.length != 2) {
                throw new ReviewException("Usage: review-spring-ai <owner/repo> <pr_number>");
            }
            String repo = args[0];
            int prNumber = parsePrNumber(args[1]);
            validateRepo(repo);

            GhReviewPayload review = githubReviewService.run(repo, prNumber);
            githubReviewService.submitReview(repo, prNumber, review);
            System.out.println(githubReviewService.toJson(review));
        } catch (ReviewException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private int parsePrNumber(String raw) {
        try {
            int prNumber = Integer.parseInt(raw);
            if (prNumber <= 0) {
                throw new NumberFormatException("not positive");
            }
            return prNumber;
        } catch (NumberFormatException error) {
            throw new ReviewException("Error: PR number must be a positive integer");
        }
    }

    private void validateRepo(String repo) {
        if (repo == null || repo.isBlank() || repo.startsWith("/") || repo.endsWith("/") || repo.chars().filter(ch -> ch == '/').count() != 1) {
            throw new ReviewException("Error: repository must use owner/name format");
        }
    }
}
