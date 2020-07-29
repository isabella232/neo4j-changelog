package org.neo4j.changelog.github;

import org.neo4j.changelog.Change;
import org.neo4j.changelog.Util;
import org.neo4j.changelog.config.GithubLabelsConfig;
import retrofit2.Call;
import retrofit2.Response;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Miscellaneous utility functions related to GitHub specific things.
 */
public class GitHubHelper {
    private static Pattern PAGE_PATTERN = Pattern.compile("page=([0-9]+)>");

    private final GitHubService service;
    private final List<String> users;
    private final String repo;
    @Nonnull
    private final GithubLabelsConfig labels;
    private final boolean includeAuthor;
    private final boolean includeLink;

    public GitHubHelper(@Nonnull String token, @Nonnull List<String> users, @Nonnull String repo, boolean includeAuthor,
                        boolean includeLink, @Nonnull GithubLabelsConfig labels) {
        service = GitHubService.GetService(token);
        this.users = users;
        this.repo = repo;
        this.includeLink = includeLink;
        this.labels = labels;
        this.includeAuthor = includeAuthor;

        if (!labels.getVersionPrefix().isEmpty() && !Util.isSemanticVersion(labels.getVersionPrefix())) {
            throw new IllegalArgumentException("version_prefix is not a semantic version: '"
                    + labels.getVersionPrefix() + "'");
        }
    }

    public static boolean isIncludedInVersion(@Nonnull PullRequest pr, @Nonnull String changelogVersion) {
        // Special case if no filter, then always true
        if (pr.getVersionFilter().isEmpty() || changelogVersion.isEmpty()) {
            return true;
        }

        for (String version : pr.getVersionFilter()) {
            if (changelogVersion.startsWith(version)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    public static Change convertToChange(@Nonnull PullRequest pr, @Nonnull String version) {
        return new Change() {
            @Override
            public int getSortingNumber() {
                return pr.getNumber();
            }

            @Nonnull
            @Override
            public List<String> getLabels() {
                return pr.getLabelFilter();
            }

            @Nonnull
            @Override
            public String getVersion() {
                return version;
            }

            @Override
            public String toString() {
                return pr.getChangeTextHeader();
            }
        };
    }

    @Nonnull
    public List<PullRequest> getChangeLogPullRequests() {
        List<PullRequest> issues = new ArrayList<>();
        for (String user : users) {
            List<PRIssue> collect = listChangeLogIssues(user).stream()
                    // Only consider pull requests, not issues
                    .filter(i -> i.pull_request != null)
                    // Can not have any of the exclusion labels
                    .filter(i -> Collections.disjoint(labels.getExclude(),
                            i.labels.stream().map(l -> l.name).collect(Collectors.toList())))
                    // Can only be unlabeled if that is allowed
                    .filter(i -> !(labels.getExcludeUnlabeled() && i.labels.isEmpty()))
                    // Must contain one of the inclusion labels
                    .filter(i -> {
                        // either the no inclusion labels have been specified
                        return labels.getInclude().isEmpty() ||
                                // or one of the labels are present in the inclusion list
                                !Collections.disjoint(labels.getInclude(),
                                        i.labels.stream().map(l -> l.name).collect(Collectors.toList()));
                    })
                    .map(issue -> {
                        GitHubService.PR pr = getPr(user, issue.number);
                        return new PRIssue(issue, pr, labels.getCategoryMap(), includeAuthor, includeLink);
                    })
                    .filter(pr -> isIncludedInVersion(pr, labels.getVersionPrefix()))
                    .collect(Collectors.toList());
            System.out.println("Fetched " + collect.size() + " issues from " + user);
            issues.addAll(collect);
        }

        System.out.println("Fetched " + issues.size() + " issues");

        return issues;
    }

    @Nonnull
    private GitHubService.PR getPr(String user, int number) {
        try {
            Call<GitHubService.PR> call = service.getPR(user, repo, number);
            Response<GitHubService.PR> response = call.execute();
            if (response.isSuccessful()) {
                return response.body();
            }
            System.out.println("GetPR call failed for user " + user + ", repo " + repo + " and number " + number);
            throw new RuntimeException(response.message());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nonnull
    private List<GitHubService.Issue> listChangeLogIssues(String user) {
        List<GitHubService.Issue> issues = new LinkedList<>();

        OptionalInt nextPage = OptionalInt.of(1);
        while (nextPage.isPresent()) {
            Response<List<GitHubService.Issue>> response = listChangeLogIssues(user, nextPage.getAsInt());
            issues.addAll(response.body());
            nextPage = getNextPage(response);
        }

        return issues;
    }

    @Nonnull
    private Response<List<GitHubService.Issue>> listChangeLogIssues(String user, int page) {
        try {
            Call<List<GitHubService.Issue>> call = service.listChangeLogIssues(user, repo, labels.getRequired(), page);
            Response<List<GitHubService.Issue>> response = call.execute();
            if (response.isSuccessful()) {
                return response;
            }
            System.out.println("listChangeLogIssues call failed for user " + user + ", repo " + repo + " and page " + page);

            throw new RuntimeException(response.message());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nonnull
    private OptionalInt getNextPage(@Nonnull Response result) {
        if (result.headers().get("Link") != null) {
            String link = result.headers().get("Link");
            String parsedPage = null;
            for (String part : link.split(",")) {
                for (String piece : part.split(";")) {
                    if ("rel=\"next\"".equals(piece.trim()) && parsedPage != null) {
                        // Previous piece pointed to next
                        return OptionalInt.of(Integer.parseInt(parsedPage));
                    } else if (piece.contains("&page=")) {
                        Matcher match = PAGE_PATTERN.matcher(piece);
                        if (match.find()) {
                            parsedPage = match.group(1);
                        }
                    }
                }
            }
        }
        return OptionalInt.empty();
    }

    public GitHubService getService() {
        return service;
    }
}
