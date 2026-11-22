package com.github.baileyseye.branchcommitmessage;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitLocalBranch;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves the Git branch whose name should be used as the commit subject.
 */
final class GitBranchResolver {

    private GitBranchResolver() {
    }

    static @Nullable String resolve(@NotNull Project project) {
        if (project.isDisposed()) {
            return null;
        }

        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        GitRepository repository = chooseRepository(project, repositories);
        if (repository == null) {
            return null;
        }

        GitLocalBranch branch = repository.getCurrentBranch();
        if (branch == null) {
            // Detached HEAD: there is no branch name to use.
            return null;
        }

        String name = branch.getName();
        if (name == null || name.isBlank() || "HEAD".equals(name)) {
            return null;
        }
        return name;
    }

    private static @Nullable GitRepository chooseRepository(@NotNull Project project,
                                                             @NotNull List<GitRepository> repositories) {
        if (repositories.isEmpty()) {
            return null;
        }

        String basePath = project.getBasePath();
        if (basePath != null) {
            for (GitRepository repository : repositories) {
                VirtualFile root = repository.getRoot();
                if (root != null && FileUtil.pathsEqual(root.getPath(), basePath)) {
                    return repository;
                }
            }
        }

        // A project can contain several repositories (e.g. a parent folder with
        // multiple Git roots). Use the first one as a deterministic fallback.
        return repositories.get(0);
    }
}
