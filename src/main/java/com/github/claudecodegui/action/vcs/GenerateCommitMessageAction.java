package com.github.claudecodegui.action.vcs;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.service.GitCommitMessageService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Action to generate Git commit messages using AI.
 * Supports cancel-on-second-click and loading state feedback.
 */
public class GenerateCommitMessageAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);

    private static final Icon ICON_DEFAULT = IconLoader.getIcon("/icons/ai-commit.svg", GenerateCommitMessageAction.class);
    private static final Icon ICON_LOADING = IconLoader.getIcon("/icons/ai-commit-loading.svg", GenerateCommitMessageAction.class);

    private final AtomicBoolean generating = new AtomicBoolean(false);
    private volatile boolean cancelled = false;
    private volatile String originalMessage = "";

    public GenerateCommitMessageAction() {
        super();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        // If already generating, treat second click as cancel
        if (generating.get()) {
            cancelled = true;
            generating.set(false);
            ApplicationManager.getApplication().invokeLater(() -> {
                CommitMessageI panel = getCommitMessagePanel(e);
                if (panel != null) {
                    panel.setCommitMessage(originalMessage);
                }
                e.getPresentation().setIcon(ICON_DEFAULT);
                ClaudeNotifier.showWarning(project, ClaudeCodeGuiBundle.message("commit.cancelGeneration"));
            });
            return;
        }

        CommitMessageI commitMessagePanel = getCommitMessagePanel(e);
        Collection<Change> changes = getUserSelectedChanges(e, project);

        if (commitMessagePanel == null) {
            ClaudeNotifier.showWarning(project, ClaudeCodeGuiBundle.message("commit.cannotAccessPanel"));
            return;
        }

        if (changes == null || changes.isEmpty()) {
            ClaudeNotifier.showWarning(project, ClaudeCodeGuiBundle.message("commit.noChanges"));
            return;
        }

        // Enter generating state
        generating.set(true);
        cancelled = false;
        originalMessage = "";

        // Visual feedback: switch to loading icon and show placeholder
        e.getPresentation().setIcon(ICON_LOADING);
        commitMessagePanel.setCommitMessage(ClaudeCodeGuiBundle.message("commit.generating"));

        final CommitMessageI finalPanel = commitMessagePanel;
        final Collection<Change> finalChanges = changes;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                GitCommitMessageService service = new GitCommitMessageService(project);
                service.generateCommitMessage(finalChanges, new GitCommitMessageService.CommitMessageCallback() {
                    @Override
                    public void onSuccess(String commitMessage) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            generating.set(false);
                            e.getPresentation().setIcon(ICON_DEFAULT);
                            if (cancelled) {
                                return;
                            }
                            finalPanel.setCommitMessage(commitMessage);
                            ClaudeNotifier.showSuccess(project, ClaudeCodeGuiBundle.message("commit.generateSuccess"));
                        });
                    }

                    @Override
                    public void onError(String error) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            generating.set(false);
                            e.getPresentation().setIcon(ICON_DEFAULT);
                            if (cancelled) {
                                return;
                            }
                            finalPanel.setCommitMessage(originalMessage);
                            ClaudeNotifier.showError(project, ClaudeCodeGuiBundle.message("commit.generateFailed") + ": " + error);
                        });
                    }
                });
            } catch (Exception ex) {
                LOG.error("Failed to generate commit message", ex);
                ApplicationManager.getApplication().invokeLater(() -> {
                    generating.set(false);
                    e.getPresentation().setIcon(ICON_DEFAULT);
                    if (cancelled) {
                        return;
                    }
                    finalPanel.setCommitMessage(originalMessage);
                    ClaudeNotifier.showError(project, ClaudeCodeGuiBundle.message("commit.generateFailed") + ": " + ex.getMessage());
                });
            }
        });
    }

    /**
     * Get CommitMessageI from available data sources.
     */
    @Nullable
    private CommitMessageI getCommitMessagePanel(@NotNull AnActionEvent e) {
        Object workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (workflowHandler instanceof CommitMessageI) {
            return (CommitMessageI) workflowHandler;
        }

        CommitMessageI messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        if (messageControl != null) {
            return messageControl;
        }

        return null;
    }

    /**
     * Get user-selected changes from the commit dialog.
     * Uses a fallback chain to support different IDEA versions:
     * 1. COMMIT_WORKFLOW_HANDLER.ui.getIncludedChanges() - preferred, gets user-checked files
     * 2. CheckinProjectPanel.getSelectedChanges() - legacy fallback
     * 3. VcsDataKeys.CHANGES - context-based fallback
     * 4. ChangeListManager.getAllChanges() - last resort fallback
     */
    @Nullable
    private Collection<Change> getUserSelectedChanges(@NotNull AnActionEvent e, @NotNull Project project) {
        Collection<Change> changes;

        Object workflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (workflowHandler != null) {
            changes = getIncludedChangesViaReflection(workflowHandler);
            if (changes != null && !changes.isEmpty()) {
                return changes;
            }
        }

        Object messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        if (messageControl instanceof CheckinProjectPanel checkinPanel) {
            changes = checkinPanel.getSelectedChanges();
            if (changes != null && !changes.isEmpty()) {
                return changes;
            }
        }

        Change[] changesArray = e.getData(VcsDataKeys.CHANGES);
        if (changesArray != null && changesArray.length > 0) {
            return java.util.Arrays.asList(changesArray);
        }

        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        Collection<Change> allChanges = changeListManager.getAllChanges();
        if (!allChanges.isEmpty()) {
            return allChanges;
        }

        return null;
    }

    /**
     * Get included changes from AbstractCommitWorkflowHandler via reflection.
     * This method uses reflection to call handler.ui.getIncludedChanges() which returns
     * only the files that the user has checked in the commit dialog.
     * <p>
     * The reflection approach is necessary because:
     * - AbstractCommitWorkflowHandler.ui.getIncludedChanges() was introduced in newer IDEA versions
     * - Direct method call would cause ClassNotFoundException in older IDEA versions
     * - This allows graceful degradation when the API is unavailable
     */
    @Nullable
    private Collection<Change> getIncludedChangesViaReflection(@NotNull Object workflowHandler) {
        try {
            // Get the 'ui' property from AbstractCommitWorkflowHandler
            // The ui property is of type CommitWorkflowUi which has getIncludedChanges() method
            Method getUiMethod = workflowHandler.getClass().getMethod("getUi");
            Object ui = getUiMethod.invoke(workflowHandler);

            if (ui == null) {
                LOG.debug("workflowHandler.getUi() returned null");
                return null;
            }

            // Call getIncludedChanges() on the ui object
            // This returns List<Change> containing only user-checked files
            Method getIncludedChangesMethod = ui.getClass().getMethod("getIncludedChanges");
            Object result = getIncludedChangesMethod.invoke(ui);

            if (result instanceof Collection<?> col) {
                List<Change> changes = new ArrayList<>();
                for (Object item : col) {
                    if (item instanceof Change change) {
                        changes.add(change);
                    }
                }
                LOG.debug("Successfully retrieved " + changes.size() + " included changes via reflection");
                return changes;
            }

            return null;
        } catch (NoSuchMethodException e) {
            // Expected on older IDEA versions that don't have this API
            LOG.debug("getIncludedChanges() method not available (older IDEA version): " + e.getMessage());
            return null;
        } catch (Exception e) {
            // Log other reflection errors for debugging
            LOG.debug("Failed to get included changes via reflection: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);

        if (generating.get()) {
            e.getPresentation().setIcon(ICON_LOADING);
            e.getPresentation().setText(ClaudeCodeGuiBundle.message("commit.generating"));
        } else {
            e.getPresentation().setIcon(ICON_DEFAULT);
            e.getPresentation().setText(ClaudeCodeGuiBundle.message("action.generateCommitMessage.text"));
            e.getPresentation().setDescription(ClaudeCodeGuiBundle.message("action.generateCommitMessage.description"));
        }
    }
}
