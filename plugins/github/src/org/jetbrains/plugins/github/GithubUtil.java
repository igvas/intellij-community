/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.plugins.github;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.ThrowableConvertor;
import git4idea.GitUtil;
import git4idea.config.GitVcsApplicationSettings;
import git4idea.config.GitVersion;
import git4idea.i18n.GitBundle;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.apache.commons.httpclient.auth.AuthenticationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.github.api.*;
import org.jetbrains.plugins.github.ui.GithubLoginDialog;

import java.io.IOException;
import java.util.List;

/**
 * Various utility methods for the GutHub plugin.
 *
 * @author oleg
 * @author Kirill Likhodedov
 * @author Aleksey Pivovarov
 */
public class GithubUtil {

  public static final Logger LOG = Logger.getInstance("github");

  @NotNull
  public static GithubAuthData runAndGetValidAuth(@Nullable Project project,
                                                  @NotNull ProgressIndicator indicator,
                                                  @NotNull ThrowableConsumer<GithubAuthData, IOException> task) throws IOException {
    GithubAuthData auth = GithubSettings.getInstance().getAuthData();
    try {
      if (auth.getAuthType() == GithubAuthData.AuthType.ANONYMOUS) {
        throw new AuthenticationException("Bad authentication type");
      }
      task.consume(auth);
      return auth;
    }
    catch (AuthenticationException e) {
      auth = getValidAuthData(project, indicator);
      if (auth == null) {
        throw new AuthenticationException("Can't get valid credentials");
      }
      task.consume(auth);
      return auth;
    }
    catch (IOException e) {
      GithubSslSupport sslSupport = GithubSslSupport.getInstance();
      if (GithubSslSupport.isCertificateException(e)) {
        if (sslSupport.askIfShouldProceed(auth.getHost())) {
          return runAndGetValidAuth(project, indicator, task);
        }
      }
      throw e;
    }
  }

  @NotNull
  public static <T> T runWithValidAuth(@Nullable Project project,
                                       @NotNull ProgressIndicator indicator,
                                       @NotNull ThrowableConvertor<GithubAuthData, T, IOException> task) throws IOException {
    GithubAuthData auth = GithubSettings.getInstance().getAuthData();
    try {
      if (auth.getAuthType() == GithubAuthData.AuthType.ANONYMOUS) {
        throw new AuthenticationException("Bad authentication type");
      }
      return task.convert(auth);
    }
    catch (AuthenticationException e) {
      auth = getValidAuthData(project, indicator);
      if (auth == null) {
        throw new AuthenticationException("Can't get valid credentials");
      }
      return task.convert(auth);
    }
    catch (IOException e) {
      GithubSslSupport sslSupport = GithubSslSupport.getInstance();
      if (GithubSslSupport.isCertificateException(e)) {
        if (sslSupport.askIfShouldProceed(auth.getHost())) {
          return runWithValidAuth(project, indicator, task);
        }
      }
      throw e;
    }
  }

  @NotNull
  public static <T> T runWithValidBasicAuth(@Nullable Project project,
                                            @NotNull ProgressIndicator indicator,
                                            @NotNull ThrowableConvertor<GithubAuthData, T, IOException> task) throws IOException {
    GithubAuthData auth = GithubSettings.getInstance().getAuthData();
    try {
      if (auth.getAuthType() != GithubAuthData.AuthType.BASIC) {
        throw new AuthenticationException("Bad authentication type");
      }
      return task.convert(auth);
    }
    catch (AuthenticationException e) {
      auth = getValidBasicAuthData(project, indicator);
      if (auth == null) {
        throw new AuthenticationException("Can't get valid credentials");
      }
      return task.convert(auth);
    }
    catch (IOException e) {
      GithubSslSupport sslSupport = GithubSslSupport.getInstance();
      if (GithubSslSupport.isCertificateException(e)) {
        if (sslSupport.askIfShouldProceed(auth.getHost())) {
          return runWithValidAuth(project, indicator, task);
        }
      }
      throw e;
    }
  }

  @Nullable
  public static GithubAuthData getValidAuthData(@Nullable Project project, @NotNull ProgressIndicator indicator) {
    final GithubLoginDialog dialog = new GithubLoginDialog(project);
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        dialog.show();
      }
    }, indicator.getModalityState());
    if (!dialog.isOK()) {
      return null;
    }
    return dialog.getAuthData();
  }

  @Nullable
  public static GithubAuthData getValidBasicAuthData(@Nullable Project project, @NotNull ProgressIndicator indicator) {
    final GithubLoginDialog dialog = new GithubLoginDialog(project);
    dialog.setBasicOnly();
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        dialog.show();
      }
    }, indicator.getModalityState());
    if (!dialog.isOK()) {
      return null;
    }
    return dialog.getAuthData();
  }

  @Nullable
  public static GithubAuthData getValidAuthDataFromConfig(@Nullable Project project, @NotNull ProgressIndicator indicator) {
    GithubAuthData auth = GithubSettings.getInstance().getAuthData();
    try {
      checkAuthData(auth, GithubSettings.getInstance().getLogin());
      return auth;
    }
    catch (AuthenticationException e) {
      return getValidAuthData(project, indicator);
    }
    catch (IOException e) {
      LOG.info("Connection error", e);
      return null;
    }
  }

  public static void checkAuthData(@NotNull GithubAuthData auth, @Nullable String login) throws IOException {
    if (StringUtil.isEmptyOrSpaces(auth.getHost())) {
      throw new AuthenticationException("Target host not defined");
    }

    switch (auth.getAuthType()) {
      case BASIC:
        GithubAuthData.BasicAuth basicAuth = auth.getBasicAuth();
        assert basicAuth != null;
        if (StringUtil.isEmptyOrSpaces(basicAuth.getLogin()) || StringUtil.isEmptyOrSpaces(basicAuth.getPassword())) {
          throw new AuthenticationException("Empty login or password");
        }
        break;
      case TOKEN:
        GithubAuthData.TokenAuth tokenAuth = auth.getTokenAuth();
        assert tokenAuth != null;
        if (StringUtil.isEmptyOrSpaces(tokenAuth.getToken())) {
          throw new AuthenticationException("Empty token");
        }
        break;
      case ANONYMOUS:
        throw new AuthenticationException("Anonymous connection not allowed");
    }

    try {
      testConnection(auth, login);
    }
    catch (JsonException e) {
      throw new AuthenticationException("Can't get user info", e);
    }
  }

  private static void testConnection(@NotNull GithubAuthData auth, @Nullable String login) throws IOException {
    GithubUserDetailed user = GithubApiUtil.getCurrentUserInfo(auth);
    if (login != null && !login.equalsIgnoreCase(user.getLogin())) {
      throw new AuthenticationException("Wrong login");
    }
  }

  /*
  * Git utils
  */

  @Nullable
  public static String findGithubRemoteUrl(@NotNull GitRepository repository) {
    String githubUrl = null;
    for (GitRemote gitRemote : repository.getRemotes()) {
      for (String remoteUrl : gitRemote.getUrls()) {
        if (GithubUrlUtil.isGithubUrl(remoteUrl)) {
          final String remoteName = gitRemote.getName();
          if ("github".equals(remoteName) || "origin".equals(remoteName)) {
            return remoteUrl;
          }
          if (githubUrl == null) {
            githubUrl = remoteUrl;
          }
          break;
        }
      }
    }
    return githubUrl;
  }

  @Nullable
  public static String findGithubUpstreamRemote(@NotNull GitRepository repository) {
    for (GitRemote gitRemote : repository.getRemotes()) {
      final String remoteName = gitRemote.getName();
      if ("upstream".equals(remoteName)) {
        for (String remoteUrl : gitRemote.getUrls()) {
          if (GithubUrlUtil.isGithubUrl(remoteUrl)) {
            return remoteUrl;
          }
        }
        return gitRemote.getFirstUrl();
      }
    }
    return null;
  }

  public static boolean testGitExecutable(final Project project) {
    final GitVcsApplicationSettings settings = GitVcsApplicationSettings.getInstance();
    final String executable = settings.getPathToGit();
    final GitVersion version;
    try {
      version = GitVersion.identifyVersion(executable);
    }
    catch (Exception e) {
      GithubNotifications.showErrorDialog(project, GitBundle.getString("find.git.error.title"), e.getMessage());
      return false;
    }

    if (!version.isSupported()) {
      GithubNotifications.showWarningDialog(project, GitBundle.message("find.git.unsupported.message", version.toString(), GitVersion.MIN),
                                            GitBundle.getString("find.git.success.title"));
      return false;
    }
    return true;
  }

  public static boolean isRepositoryOnGitHub(@NotNull GitRepository repository) {
    return findGithubRemoteUrl(repository) != null;
  }

  static void setVisibleEnabled(AnActionEvent e, boolean visible, boolean enabled) {
    e.getPresentation().setVisible(visible);
    e.getPresentation().setEnabled(enabled);
  }

  @NotNull
  public static String getErrorTextFromException(@NotNull IOException e) {
    return e.getMessage();
  }

  @Nullable
  public static GitRepository getGitRepository(@NotNull Project project, @Nullable VirtualFile file) {
    GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
    List<GitRepository> repositories = manager.getRepositories();
    if (repositories.size() == 0) {
      return null;
    }
    if (repositories.size() == 1) {
      return repositories.get(0);
    }
    if (file != null) {
      GitRepository repository = manager.getRepositoryForFile(file);
      if (repository != null) {
        return repository;
      }
    }
    return manager.getRepositoryForFile(project.getBaseDir());
  }

}
