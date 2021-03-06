/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.history.browser;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.ui.CommitHelper;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.WaitForProgressToShow;
import git4idea.PlatformFacade;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitMessageWithFilesDetector;
import git4idea.commands.GitSimpleEventDetector;
import git4idea.merge.GitConflictResolver;
import git4idea.repo.GitRepository;
import git4idea.util.UntrackedFilesNotifier;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.openapi.util.text.StringUtil.pluralize;
import static git4idea.commands.GitMessageWithFilesDetector.Event.UNTRACKED_FILES_OVERWRITTEN_BY;
import static git4idea.commands.GitSimpleEventDetector.Event.CHERRY_PICK_CONFLICT;
import static git4idea.commands.GitSimpleEventDetector.Event.LOCAL_CHANGES_OVERWRITTEN_BY_CHERRY_PICK;

public class CherryPicker {

  private static final Logger LOG = Logger.getInstance(CherryPicker.class);

  @NotNull private final Project myProject;
  @NotNull private final Git myGit;
  @NotNull private final PlatformFacade myPlatformFacade;
  @NotNull private final ChangeListManager myChangeListManager;
  private final boolean myAutoCommit;

  public CherryPicker(@NotNull Project project, @NotNull Git git, @NotNull PlatformFacade platformFacade, boolean autoCommit) {
    myProject = project;
    myGit = git;
    myPlatformFacade = platformFacade;
    myAutoCommit = autoCommit;
    myChangeListManager = myPlatformFacade.getChangeListManager(myProject);
  }

  public void cherryPick(@NotNull Map<GitRepository, List<GitCommit>> commitsInRoots) {
    List<GitCommit> successfulCommits = new ArrayList<GitCommit>();
    for (Map.Entry<GitRepository, List<GitCommit>> entry : commitsInRoots.entrySet()) {
      if (!cherryPick(entry.getKey(), entry.getValue(), successfulCommits)) {
        return;
      }
    }
    notifySuccess(successfulCommits);
  }

  // return true to continue with other roots, false to break execution
  private boolean cherryPick(@NotNull GitRepository repository, @NotNull List<GitCommit> commits,
                             @NotNull List<GitCommit> successfulCommits) {
    for (GitCommit commit : commits) {
      GitSimpleEventDetector conflictDetector = new GitSimpleEventDetector(CHERRY_PICK_CONFLICT);
      GitSimpleEventDetector localChangesOverwrittenDetector = new GitSimpleEventDetector(LOCAL_CHANGES_OVERWRITTEN_BY_CHERRY_PICK);
      GitMessageWithFilesDetector untrackedFilesDetector = new GitMessageWithFilesDetector(UNTRACKED_FILES_OVERWRITTEN_BY,
                                                                                           repository.getRoot());
      GitCommandResult result = myGit.cherryPick(repository, commit.getHash().getValue(), myAutoCommit,
                                                 conflictDetector, localChangesOverwrittenDetector, untrackedFilesDetector);
      if (result.success()) {
        if (myAutoCommit) {
          successfulCommits.add(commit);
        }
        else {
          boolean committed = updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(repository, commit, successfulCommits);
          if (!committed) {
            notifyCommitCancelled(commit, successfulCommits);
            return false;
          }
        }
      }
      else if (conflictDetector.hasHappened()) {
        boolean mergeCompleted = new CherryPickConflictResolver(myProject, myGit, myPlatformFacade, repository.getRoot(),
                                                                commit.getShortHash().getString(), commit.getAuthor(),
                                                                commit.getSubject()).merge();

        if (mergeCompleted) {
          boolean committed = updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(repository, commit, successfulCommits);
          if (!committed) {
            notifyCommitCancelled(commit, successfulCommits);
            return false;
          }
        }
        else {
          updateChangeListManager(commit);
          notifyConflictWarning(repository, commit, successfulCommits);
          return false;
        }
      }
      else if (untrackedFilesDetector.wasMessageDetected()) {
        String description = commitDetails(commit)
                             + "<br/>Some untracked working tree files would be overwritten by cherry-pick.<br/>" +
                             "Please move, remove or add them before you can cherry-pick. <a href='view'>View them</a>";
        description += getSuccessfulCommitDetailsIfAny(successfulCommits);

        UntrackedFilesNotifier.notifyUntrackedFilesOverwrittenBy(myProject, myPlatformFacade, untrackedFilesDetector.getFiles(),
                                                                 "cherry-pick", description);
        return false;
      }
      else if (localChangesOverwrittenDetector.hasHappened()) {
        notifyError("Your local changes would be overwritten by cherry-pick.<br/>Commit your changes or stash them to proceed.",
                    commit, successfulCommits);
        return false;
      }
      else {
        notifyError(result.getErrorOutputAsHtmlString(), commit, successfulCommits);
        return false;
      }
    }
    return true;
  }

  private boolean updateChangeListManagerShowCommitDialogAndRemoveChangeListOnSuccess(@NotNull GitRepository repository,
                                                                                      @NotNull GitCommit commit,
                                                                                      @NotNull List<GitCommit> successfulCommits) {
    CherryPickData data = updateChangeListManager(commit);
    boolean committed = showCommitDialog(repository, commit, data.myChangeList, data.myCommitMessage);
    if (committed) {
      removeChangeList(data);
      successfulCommits.add(commit);
      return true;
    }
    return false;
  }

  private void removeChangeList(CherryPickData list) {
    myChangeListManager.setDefaultChangeList(list.myPreviouslyDefaultChangeList);
    if (!myChangeListManager.getDefaultChangeList().equals(list.myChangeList)) {
      myChangeListManager.removeChangeList(list.myChangeList);
    }
  }

  private void notifyConflictWarning(@NotNull GitRepository repository, @NotNull GitCommit commit,
                                     @NotNull List<GitCommit> successfulCommits) {
    NotificationListener resolveLinkListener = new ResolveLinkListener(myProject, myGit, myPlatformFacade, repository.getRoot(),
                                                                       commit.getShortHash().getString(), commit.getAuthor(),
                                                                       commit.getSubject());
    String description = commitDetails(commit)
                         + "<br/>Unresolved conflicts remain in the working tree. <a href='resolve'>Resolve them.<a/>";
    description += getSuccessfulCommitDetailsIfAny(successfulCommits);
    myPlatformFacade.getNotificator(myProject).notifyStrongWarning("Cherry-picked with conflicts", description, resolveLinkListener);
  }

  private void notifyCommitCancelled(@NotNull GitCommit commit, @NotNull List<GitCommit> successfulCommits) {
    String description = commitDetails(commit);
    description += getSuccessfulCommitDetailsIfAny(successfulCommits);
    myPlatformFacade.getNotificator(myProject).notifyWeakWarning("Cherry-pick cancelled", description, null);
  }

  private CherryPickData updateChangeListManager(@NotNull final GitCommit commit) {
    final Collection<FilePath> paths = ChangesUtil.getPaths(commit.getChanges());
    refreshChangedFiles(paths);
    final String commitMessage = createCommitMessage(commit, paths);
    LocalChangeList previouslyDefaultChangeList = myChangeListManager.getDefaultChangeList();
    LocalChangeList changeList = createChangeListAfterUpdate(commit.getChanges(), paths, commitMessage);
    return new CherryPickData(changeList, commitMessage, previouslyDefaultChangeList);
  }

  @NotNull
  private LocalChangeList createChangeListAfterUpdate(@NotNull final List<Change> changes, @NotNull final Collection<FilePath> paths,
                                                      @NotNull final String commitMessage) {
    final AtomicReference<LocalChangeList> changeList = new AtomicReference<LocalChangeList>();
    myPlatformFacade.invokeAndWait(new Runnable() {
      @Override
      public void run() {
        myChangeListManager.invokeAfterUpdate(new Runnable() {
                                                public void run() {
                                                  changeList.set(createChangeList(changes, commitMessage));
                                                }
                                              }, InvokeAfterUpdateMode.SYNCHRONOUS_NOT_CANCELLABLE, "",
                                              new Consumer<VcsDirtyScopeManager>() {
                                                public void consume(VcsDirtyScopeManager vcsDirtyScopeManager) {
                                                  vcsDirtyScopeManager.filePathsDirty(paths, null);
                                                }
                                              }, ModalityState.NON_MODAL
        );
      }
    }, ModalityState.NON_MODAL);


    return changeList.get();
  }

  @NotNull
  private String createCommitMessage(@NotNull GitCommit commit, @NotNull Collection<FilePath> paths) {
    CheckinEnvironment ce = myPlatformFacade.getVcs(myProject).getCheckinEnvironment();
    String message = ce == null ? null : ce.getDefaultMessageFor(ArrayUtil.toObjectArray(paths, FilePath.class));
    message = message == null ? commit.getDescription() + "\n(cherry-picked from " + commit.getShortHash().getString() + ")" : message;
    return message;
  }

  private boolean showCommitDialog(@NotNull final GitRepository repository, @NotNull final GitCommit commit,
                                   @NotNull final LocalChangeList changeList, @NotNull final String commitMessage) {
    final AtomicBoolean commitSucceeded = new AtomicBoolean();
    myPlatformFacade.invokeAndWait(new Runnable() {
      @Override
      public void run() {
        cancelCherryPick(repository);
        List<Change> changes = commit.getChanges();
        CherryPickCommitExecutor executor = new CherryPickCommitExecutor(myProject, myPlatformFacade, changes, commitMessage);
        boolean commitNotCancelled = myPlatformFacade.getVcsHelper(myProject).commitChanges(changes, changeList, commitMessage, executor);
        commitSucceeded.set(commitNotCancelled && !executor.hasCommitFailed());
      }
    }, ModalityState.NON_MODAL);
    return commitSucceeded.get();
  }

  /**
   * We control the cherry-pick workflow ourselves + we want to use partial commits ('git commit --only'), which is prohibited during
   * cherry-pick, i.e. until the CHERRY_PICK_HEAD exists.
   */
  private void cancelCherryPick(@NotNull GitRepository repository) {
    if (myAutoCommit) {
      removeCherryPickHead(repository);
    }
  }

  private void removeCherryPickHead(@NotNull GitRepository repository) {
    File cherryPickHeadFile = new File(repository.getGitDir().getPath(), "CHERRY_PICK_HEAD");
    final VirtualFile cherryPickHead = myPlatformFacade.getLocalFileSystem().refreshAndFindFileByIoFile(cherryPickHeadFile);

    if (cherryPickHead != null && cherryPickHead.exists()) {
      myPlatformFacade.runWriteAction(new Runnable() {
        @Override
        public void run() {
          try {
            cherryPickHead.delete(this);
          }
          catch (IOException e) {
            // if CHERRY_PICK_HEAD is not deleted, the partial commit will fail, and the user will be notified anyway.
            // So here we just log the fact. It is happens relatively often, maybe some additional solution will follow.
            LOG.error(e);
          }
        }
      });
    }
    else {
      LOG.info("Cancel cherry-pick in " + repository.getPresentableUrl() + ": no CHERRY_PICK_HEAD found");
    }
  }

  private void notifyError(@NotNull String content, @NotNull GitCommit failedCommit, @NotNull List<GitCommit> successfulCommits) {
    String description = commitDetails(failedCommit) + "<br/>" + content;
    description += getSuccessfulCommitDetailsIfAny(successfulCommits);
    myPlatformFacade.getNotificator(myProject).notifyError("Cherry-pick failed", description);
  }

  @NotNull
  private static String getSuccessfulCommitDetailsIfAny(@NotNull List<GitCommit> successfulCommits) {
    String description = "";
    if (!successfulCommits.isEmpty()) {
      description += "<hr/>However cherry-pick succeeded for the following " + pluralize("commit", successfulCommits.size()) + ":<br/>";
      description += getCommitsDetails(successfulCommits);
    }
    return description;
  }

  private void notifySuccess(@NotNull List<GitCommit> successfulCommits) {
    String description = getCommitsDetails(successfulCommits);
    myPlatformFacade.getNotificator(myProject).notifySuccess("Cherry-pick successful", description);
  }

  @NotNull
  private static String getCommitsDetails(@NotNull List<GitCommit> successfulCommits) {
    String description = "";
    for (GitCommit commit : successfulCommits) {
      description += commitDetails(commit) + "<br/>";
    }
    return description.substring(0, description.length() - "<br/>".length());
  }

  @NotNull
  private static String commitDetails(@NotNull GitCommit commit) {
    return commit.getShortHash().toString() + " \"" + commit.getSubject() + "\"";
  }

  private void refreshChangedFiles(@NotNull Collection<FilePath> filePaths) {
    for (FilePath file : filePaths) {
      VirtualFile vf = myPlatformFacade.getLocalFileSystem().refreshAndFindFileByPath(file.getPath());
      if (vf != null) {
        vf.refresh(false, false);
      }
    }
  }

  @NotNull
  private LocalChangeList createChangeList(@NotNull List<Change> changes, @NotNull String commitMessage) {
    if (!changes.isEmpty()) {
      final LocalChangeList changeList = myChangeListManager.addChangeList(commitMessage, commitMessage);
      myChangeListManager.moveChangesTo(changeList, changes.toArray(new Change[changes.size()]));
      myChangeListManager.setDefaultChangeList(changeList);
      return changeList;
    }
    return myChangeListManager.getDefaultChangeList();
  }

  private static class CherryPickData {
    private final LocalChangeList myChangeList;
    private final String myCommitMessage;
    private final LocalChangeList myPreviouslyDefaultChangeList;

    private CherryPickData(LocalChangeList list, String message, LocalChangeList previouslyDefaultChangeList) {
      myChangeList = list;
      myCommitMessage = message;
      myPreviouslyDefaultChangeList = previouslyDefaultChangeList;
    }
  }

  private static class CherryPickConflictResolver extends GitConflictResolver {

    public CherryPickConflictResolver(@NotNull Project project, @NotNull Git git, @NotNull PlatformFacade facade, @NotNull VirtualFile root,
                                      @NotNull String commitHash, @NotNull String commitAuthor, @NotNull String commitMessage) {
      super(project, git, facade, Collections.singleton(root), makeParams(commitHash, commitAuthor, commitMessage));
    }

    private static Params makeParams(String commitHash, String commitAuthor, String commitMessage) {
      Params params = new Params();
      params.setErrorNotificationTitle("Cherry-picked with conflicts");
      params.setMergeDialogCustomizer(new CherryPickMergeDialogCustomizer(commitHash, commitAuthor, commitMessage));
      return params;
    }

    @Override
    protected void notifyUnresolvedRemain() {
      // we show a [possibly] compound notification after cherry-picking all commits.
    }

  }

  private static class ResolveLinkListener implements NotificationListener {
    @NotNull private final Project myProject;
    @NotNull private final Git myGit;
    @NotNull private final PlatformFacade myFacade;
    @NotNull private final VirtualFile myRoot;
    @NotNull private final String myHash;
    @NotNull private final String myAuthor;
    @NotNull private final String myMessage;

    public ResolveLinkListener(@NotNull Project project, @NotNull Git git, @NotNull PlatformFacade facade, @NotNull VirtualFile root,
                               @NotNull String commitHash, @NotNull String commitAuthor, @NotNull String commitMessage) {

      myProject = project;
      myGit = git;
      myFacade = facade;
      myRoot = root;
      myHash = commitHash;
      myAuthor = commitAuthor;
      myMessage = commitMessage;
    }

    @Override
    public void hyperlinkUpdate(@NotNull Notification notification,
                                @NotNull HyperlinkEvent event) {
      if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        if (event.getDescription().equals("resolve")) {
          new CherryPickConflictResolver(myProject, myGit, myFacade, myRoot, myHash, myAuthor, myMessage).mergeNoProceed();
        }
      }
    }
  }

  private static class CherryPickMergeDialogCustomizer extends MergeDialogCustomizer {

    private String myCommitHash;
    private String myCommitAuthor;
    private String myCommitMessage;

    public CherryPickMergeDialogCustomizer(String commitHash, String commitAuthor, String commitMessage) {
      myCommitHash = commitHash;
      myCommitAuthor = commitAuthor;
      myCommitMessage = commitMessage;
    }

    @Override
    public String getMultipleFileMergeDescription(Collection<VirtualFile> files) {
      return "<html>Conflicts during cherry-picking commit <code>" + myCommitHash + "</code> made by " + myCommitAuthor + "<br/>" +
             "<code>\"" + myCommitMessage + "\"</code></html>";
    }

    @Override
    public String getLeftPanelTitle(VirtualFile file) {
      return "Local changes";
    }

    @Override
    public String getRightPanelTitle(VirtualFile file, VcsRevisionNumber lastRevisionNumber) {
      return "<html>Changes from cherry-pick <code>" + myCommitHash + "</code>";
    }
  }

  /*
    Commit procedure is overridden by the executor with its own CommitSession.
    The reason of that is the asynchronous nature of the CommitHelper: it returns, we continue cherry-picking and occasionally pick
    the next commit in the queue, and only then Git is called for commit. Thus it commits two cherry-picks at once, which is wrong.

    Here we call GitCheckinEnvironment manually
   */
  private static class CherryPickCommitExecutor implements CommitExecutor {

    @NotNull private final Project myProject;
    @NotNull private final PlatformFacade myPlatformFacade;
    @NotNull private final List<Change> myChanges;
    @NotNull private final String myCommitMessage;
    private boolean myCommitFailed;

    CherryPickCommitExecutor(@NotNull Project project, @NotNull PlatformFacade platformFacade,
                             @NotNull List<Change> changes, @NotNull String commitMessage) {
      myProject = project;
      myPlatformFacade = platformFacade;
      myChanges = changes;
      myCommitMessage = commitMessage;
    }

    @Nls
    @Override
    public String getActionText() {
      return "Commit";
    }

    @NotNull
    @Override
    public CommitSession createCommitSession() {
      return new CherryPickCommitSession();
    }

    public boolean hasCommitFailed() {
      return myCommitFailed;
    }

    private class CherryPickCommitSession implements CommitSession {
      @Override
      public JComponent getAdditionalConfigurationUI() {
        return null;
      }

      @Override
      public JComponent getAdditionalConfigurationUI(Collection<Change> changes, String commitMessage) {
        return null;
      }

      @Override
      public boolean canExecute(Collection<Change> changes, String commitMessage) {
        return true;
      }

      @Override
      public void execute(Collection<Change> changes, String commitMessage) {
        final Collection<Document> committingDocs = markCommittingDocs();
        try {
          CheckinEnvironment ce = myPlatformFacade.getVcs(myProject).getCheckinEnvironment();
          if (ce != null) {
            try {
              List<VcsException> exceptions = ce.commit(myChanges, myCommitMessage);
              VcsDirtyScopeManager.getInstance(myProject).filePathsDirty(ChangesUtil.getPaths(myChanges), null);
              if (exceptions != null && !exceptions.isEmpty()) {
                VcsException exception = exceptions.get(0);
                handleError(exception);
              }
            }
            catch (Throwable e) {
              LOG.error(e);
              handleError(e);
            }
          }
        }
        finally {
          unmarkCommittingDocs(committingDocs);
        }
      }

      private void handleError(Throwable exception) {
        myCommitFailed = true;
        final String errorMessage = exception.getMessage();
        WaitForProgressToShow.runOrInvokeLaterAboveProgress(new Runnable() {
          public void run() {
            Messages.showErrorDialog(myProject, errorMessage, "Commit Failed");
          }
        }, null, myProject);
      }

      @Override
      public void executionCanceled() {
      }

      @Override
      public String getHelpId() {
        return null;
      }

      private void unmarkCommittingDocs(final Collection<Document> committingDocs) {
        myPlatformFacade.runReadAction(new Runnable() {
          @Override
          public void run() {
            CommitHelper.unmarkCommittingDocuments(committingDocs);
          }
        });
      }

      @NotNull
      private Collection<Document> markCommittingDocs() {
        final Collection<Document> committingDocs = new ArrayList<Document>();
        myPlatformFacade.runReadAction(new Runnable() {
          @Override
          public void run() {
            committingDocs.addAll(CommitHelper.markCommittingDocuments(myProject, myChanges));
          }
        });
        return committingDocs;
      }

    }
  }

}
