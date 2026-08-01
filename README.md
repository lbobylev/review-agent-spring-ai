# review-spring-ai

Spring Boot 4 / Spring AI 2 Java 21 port of the Python PR review agent.

## Run

```sh
gradle bootRun --args="owner/repo 123"
```

Requirements:

- `OPENAI_API_KEY` must be set.
- `gh` must be installed and authenticated.
- `git` must be installed.
- The command is not a dry run: when inline comments are produced, it submits a GitHub PR review.

## Flow

```text
repo + PR number
-> gh repo clone into workspace/review-spring-ai/repo
-> gh pr checkout
-> gh pr diff + changed files
-> Spring AI ChatModel with user-controlled tool execution
-> structured review findings JSON
-> deterministic diff-coordinate validation/filtering
-> GitHub review payload
-> gh api submit
```

Spring AI 2 does not currently expose a built-in max tool-call recursion limit, so the agent uses an explicit user-controlled tool loop with a hard limit of 3 tool calls. See https://github.com/spring-projects/spring-ai/issues/3333.
