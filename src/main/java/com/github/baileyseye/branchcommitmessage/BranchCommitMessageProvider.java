package com.github.baileyseye.branchcommitmessage;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.ui.CommitMessage;
import com.intellij.ui.EditorTextField;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcs.commit.CommitMessageUi;
import com.intellij.vcs.commit.DelayedCommitMessageProvider;
import git4idea.GitLocalBranch;
import git4idea.repo.GitRepoInfo;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryStateChangeListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.Objects;

/**
 * Supplies the current Git branch as the default commit message.
 *
 * <p>The message is {@code branch + "\n\n"}: a subject line, a mandatory blank line,
 * and an empty body line. The caret is moved to the end, i.e. onto the body line.</p>
 */
public final class BranchCommitMessageProvider implements DelayedCommitMessageProvider {

    @Override
    public @Nullable String getCommitMessage(@NotNull LocalChangeList changeList,
                                              @NotNull Project project) {
        String branch = GitBranchResolver.resolve(project);
        return branch == null ? null : formatMessage(branch);
    }

    @Override
    public void init(@NotNull Project project,
                     @NotNull CommitMessageUi commitUi,
                     @NotNull Disposable disposable) {
        EditorTextField editorField = findEditorField(commitUi);
        new Controller(project, commitUi, editorField, disposable).start();
    }

    static @NotNull String formatMessage(@NotNull String branch) {
        // Subject, blank line, and an empty body line. Git uses the blank line
        // to separate the commit subject from the commit body.
        return branch + "\n\n";
    }

    private static @Nullable EditorTextField findEditorField(@NotNull CommitMessageUi commitUi) {
        if (commitUi instanceof CommitMessage commitMessage) {
            return commitMessage.getEditorField();
        }
        if (commitUi instanceof JComponent component) {
            return UIUtil.findComponentOfType(component, EditorTextField.class);
        }
        return null;
    }

    private static final class Controller {

        private final Project project;
        private final CommitMessageUi commitUi;
        private final @Nullable EditorTextField editorField;

        /**
         * Branch name that this plugin last wrote into the message editor.
         * Used to distinguish our own prefilled text from text typed by the user.
         */
        private @Nullable String lastAutoBranch;

        /** Prevents our own setText() call from being treated as a provider update. */
        private boolean applying;

        /** Set when the commit message policy owning this UI is disposed. */
        private boolean disposed;

        private Controller(@NotNull Project project,
                           @NotNull CommitMessageUi commitUi,
                           @Nullable EditorTextField editorField,
                           @NotNull Disposable disposable) {
            this.project = project;
            this.commitUi = commitUi;
            this.editorField = editorField;

            Disposer.register(disposable, () -> disposed = true);

            // The connection is disposed together with the commit UI message policy.
            MessageBusConnection connection = project.getMessageBus().connect(disposable);
            connection.subscribe(GitRepository.GIT_REPO_STATE_CHANGE, new GitRepositoryStateChangeListener() {
                @Override
                public void repositoryChanged(@NotNull GitRepository repository,
                                              @NotNull GitRepoInfo oldInfo,
                                              @NotNull GitRepoInfo newInfo) {
                    if (!Objects.equals(branchName(oldInfo), branchName(newInfo))) {
                        scheduleBranchRefresh();
                    }
                }

                @Override
                public void repositoryCreated(@NotNull GitRepository repository,
                                              @NotNull GitRepoInfo info) {
                    scheduleBranchRefresh();
                }
            });
        }

        private void start() {
            lastAutoBranch = GitBranchResolver.resolve(project);

            installDocumentListener();

            // The commit message policy sets the provider message right after
            // DelayedCommitMessageProvider.init(). Run after that to fix the caret.
            ApplicationManager.getApplication().invokeLater(this::applyInitialState);
        }

        private void applyInitialState() {
            if (isDisposed()) {
                return;
            }

            String branch = GitBranchResolver.resolve(project);
            if (branch == null) {
                return;
            }

            lastAutoBranch = branch;
            String currentText = commitUi.getText();
            if (currentText.isBlank()) {
                // Git repositories may not have been initialized when the provider
                // was queried. Fill the message as soon as the branch is known.
                applyBranch(branch);
            } else if (branch.equals(currentText)) {
                // The provider already put the branch into the message; only place
                // the caret after the mandatory blank line.
                moveCaretToEnd();
            }
            // Any other non-empty text belongs to the user (for example a Git
            // commit template). Do not overwrite it.
        }

        private void scheduleBranchRefresh() {
            ApplicationManager.getApplication().invokeLater(this::refreshAfterBranchChange);
        }

        private void refreshAfterBranchChange() {
            if (isDisposed()) {
                return;
            }

            String branch = GitBranchResolver.resolve(project);
            if (branch == null || branch.equals(lastAutoBranch)) {
                return;
            }

            String currentText = commitUi.getText();
            if (currentText.isBlank() || isOurAutoMessage(currentText)) {
                applyBranch(branch);
            }
            // If the user has started writing the commit message, do not destroy
            // their work on a branch switch.
        }

        private boolean isOurAutoMessage(@NotNull String text) {
            return lastAutoBranch != null && lastAutoBranch.equals(text);
        }

        private void applyBranch(@NotNull String branch) {
            String message = formatMessage(branch);
            boolean previousApplying = applying;
            applying = true;
            try {
                commitUi.setText(message);
                lastAutoBranch = branch;
                moveCaretToEnd();
            } finally {
                applying = previousApplying;
            }
        }

        private void moveCaretToEnd() {
            if (editorField == null) {
                return;
            }
            int offset = editorField.getDocument().getTextLength();
            editorField.setCaretPosition(offset);
        }

        private void installDocumentListener() {
            if (editorField == null) {
                return;
            }

            editorField.addDocumentListener(new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent event) {
                    if (applying || event.getOffset() != 0 || event.getOldLength() == 0) {
                        return;
                    }

                    int documentLength = editorField.getDocument().getTextLength();
                    if (event.getNewLength() != documentLength) {
                        return;
                    }

                    // The platform replaces the whole message after a commit or
                    // when it restores the default message. Put the caret back
                    // after the blank line, but only if the restored text is ours.
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (isDisposed()) {
                            return;
                        }
                        String currentText = commitUi.getText();
                        if (lastAutoBranch != null && lastAutoBranch.equals(currentText)) {
                            moveCaretToEnd();
                        }
                    });
                }
            });
        }

        private boolean isDisposed() {
            return disposed || project.isDisposed();
        }

        private static @Nullable String branchName(@NotNull GitRepoInfo info) {
            GitLocalBranch branch = info.getCurrentBranch();
            return branch == null ? null : branch.getName();
        }
    }
}
