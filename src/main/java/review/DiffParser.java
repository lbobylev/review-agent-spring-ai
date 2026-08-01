package review;

import review.ReviewModels.LineRef;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiffParser {

    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    Set<LineRef> extractRightSideLines(String diff) {
        Set<LineRef> rightSideLines = new HashSet<>();
        String currentPath = null;
        Integer rightLine = null;

        for (String line : diff.split("\\R", -1)) {
            if (line.startsWith("+++ ")) {
                currentPath = diffPathFromHeader(line);
                rightLine = null;
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER_PATTERN.matcher(line);
            if (hunkMatcher.find()) {
                rightLine = Integer.parseInt(hunkMatcher.group(1));
                continue;
            }

            if (currentPath == null || rightLine == null) {
                continue;
            }

            if (line.startsWith("+") || line.startsWith(" ")) {
                rightSideLines.add(new LineRef(currentPath, rightLine));
                rightLine++;
            } else if (line.startsWith("-") || line.startsWith("\\")) {
                // Deleted lines do not exist on RIGHT. "No newline" markers do not affect numbering.
            } else {
                rightLine = null;
            }
        }

        return rightSideLines;
    }

    private String diffPathFromHeader(String line) {
        String path = line.substring(4).split("\\t", 2)[0].strip();
        if (path.equals("/dev/null")) {
            return null;
        }
        if (path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }
}
