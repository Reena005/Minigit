import { useState, useEffect, useRef } from "react";
import {
  getRepository,
  getStatus,
  stageFile,
  commitChanges,
  getCommitHistory,
  getCommitDetails,
  getCommitDiff,
  getRepositoryFiles,
  getFileContent,
  saveFileContent,
  getBranches,
  createBranch,
  deleteBranch,
  checkoutBranch,
  unstageFile,
  unstageAll,
  undoLastCommit,
  getReflog,
  checkRepositoryIntegrity,
} from "./services/repositoryService";
import "./App.css";
/* -------------------------------------------------------
   NAVIGATION
------------------------------------------------------- */

const navigation = [
  { label: "Overview", page: "Overview", icon: "overview" },
  { label: "Changes", page: "Source Control", icon: "changes" },
  { label: "History", page: "Commit History", icon: "history" },
  { label: "Branches", page: "Branches", icon: "branches" },
  { label: "File Explorer", page: "File Explorer", icon: "files" },
  { label: "Diff Viewer", page: "Diff Viewer", icon: "diff" },
  {
    label: "Integrity Checker",
    page: "Integrity Checker",
    icon: "integrity",
  },
];

/* -------------------------------------------------------
   UTILITIES
------------------------------------------------------- */

function normalizeHash(hash) {
  if (typeof hash !== "string") return "";
  return hash.trim();
}

function getCommitHash(commit) {
  return normalizeHash(
    commit?.hash ??
      commit?.commitHash ??
      commit?.id ??
      ""
  );
}

function getCommitMessage(commit) {
  return (
    commit?.message ??
    commit?.commitMessage ??
    "Untitled commit"
  );
}

function getCommitAuthor(commit) {
  return (
    commit?.author ??
    commit?.authorName ??
    commit?.committer ??
    "Unknown author"
  );
}

function getCommitTimestamp(commit) {
  return (
    commit?.timestamp ??
    commit?.date ??
    commit?.committedAt ??
    commit?.commitTime ??
    null
  );
}

function getCommitBranch(commit) {
  return (
    commit?.branch ??
    commit?.branchName ??
    ""
  );
}

function getCommitFiles(commit) {
  const files =
    commit?.changedFiles ??
    commit?.changed_files ??
    commit?.fileChanges ??
    commit?.files ??
    [];

  if (Array.isArray(files)) {
    return files;
  }

  if (files && typeof files === "object") {
    return Object.entries(files).map(([path, value]) => {
      if (value && typeof value === "object") {
        return {
          ...value,
          path: value.path ?? path,
        };
      }

      return {
        path,
        hash: value,
        status: "unknown",
      };
    });
  }

  return [];
}

function getSnapshotFiles(files) {
  if (Array.isArray(files)) return files;
  if (!files || typeof files !== "object") return [];

  return Object.entries(files).map(([path, hash]) => ({
    path,
    hash,
  }));
}

function getChangedFiles(files) {
  if (!Array.isArray(files)) return [];
  return files;
}

function getFilePath(file) {
  if (typeof file === "string") {
    return file;
  }

  return (
    file?.path ??
    file?.filePath ??
    file?.name ??
    "Unknown file"
  );
}

function getNumber(value) {
  if (value === null || value === undefined) {
    return null;
  }

  const number = Number(value);

  return Number.isFinite(number) ? number : null;
}

function getFileAdditions(file) {
  return getNumber(
    file?.additions ??
      file?.addedLines ??
      file?.insertions
  );
}

function getFileDeletions(file) {
  return getNumber(
    file?.deletions ??
      file?.deletedLines ??
      file?.removals
  );
}

function getFileStatus(file) {
  if (typeof file === "string") {
    return "modified";
  }

  return String(
    file?.status ??
      file?.changeType ??
      file?.type ??
      "unknown"
  ).toLowerCase();
}

function getDiffLineContent(line) {
  return String(line?.content ?? "").replace(/\r/g, "");
}

function getCommitStats(commit) {
  const files = getCommitFiles(commit);

  let additions = 0;
  let deletions = 0;
  let hasLineStats = false;

  for (const file of files) {
    const added = getFileAdditions(file);
    const deleted = getFileDeletions(file);

    if (added !== null) {
      additions += added;
      hasLineStats = true;
    }

    if (deleted !== null) {
      deletions += deleted;
      hasLineStats = true;
    }
  }

  const totalAdditions = getNumber(
    commit?.additions ??
      commit?.totalAdditions ??
      commit?.insertions
  );

  const totalDeletions = getNumber(
    commit?.deletions ??
      commit?.totalDeletions ??
      commit?.removals
  );

  if (totalAdditions !== null) {
    additions = totalAdditions;
    hasLineStats = true;
  }

  if (totalDeletions !== null) {
    deletions = totalDeletions;
    hasLineStats = true;
  }

  const fileCount = getNumber(
    commit?.fileCount ??
      commit?.filesChanged ??
      commit?.changedFileCount
  );

  return {
    fileCount: fileCount ?? files.length,
    additions,
    deletions,
    hasLineStats,
  };
}

function formatDate(timestamp) {
  if (!timestamp) {
    return "Unknown date";
  }

  const date = new Date(timestamp);

  if (Number.isNaN(date.getTime())) {
    return String(timestamp);
  }

  return new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(date);
}

function formatRelativeTime(timestamp) {
  if (!timestamp) return "";

  const date = new Date(timestamp);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  const seconds = Math.floor(
    (Date.now() - date.getTime()) / 1000
  );

  if (seconds < 0) {
    return formatDate(timestamp);
  }

  if (seconds < 60) {
    return "just now";
  }

  const minutes = Math.floor(seconds / 60);

  if (minutes < 60) {
    return `${minutes}m ago`;
  }

  const hours = Math.floor(minutes / 60);

  if (hours < 24) {
    return `${hours}h ago`;
  }

  const days = Math.floor(hours / 24);

  if (days < 30) {
    return `${days}d ago`;
  }

  const months = Math.floor(days / 30);

  if (months < 12) {
    return `${months}mo ago`;
  }

  return `${Math.floor(months / 12)}y ago`;
}

function getInitials(name) {
  return String(name || "Unknown author")
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0].toUpperCase())
    .join("");
}

function getAvatarColor(name) {
  const colors = [
    "#8b7cf6",
    "#5799f7",
    "#39b98a",
    "#df9950",
    "#db6d9a",
    "#36a8b5",
  ];

  const value = String(name || "Unknown author");

  let hash = 0;

  for (let index = 0; index < value.length; index++) {
    hash =
      (hash * 31 + value.charCodeAt(index)) | 0;
  }

  return colors[Math.abs(hash) % colors.length];
}

async function copyText(value) {
  if (!value) return;

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }

    const textarea = document.createElement("textarea");

    textarea.value = value;
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";

    document.body.appendChild(textarea);
    textarea.select();

    const successful = document.execCommand("copy");

    textarea.remove();

    return successful;
  } catch {
    return false;
  }
}

/* -------------------------------------------------------
   SHARED COMPONENTS
------------------------------------------------------- */

const iconPaths = {
  overview: "M3 3h8v8H3zM13 3h8v5h-8zM13 10h8v11h-8zM3 13h8v8H3z",
  changes: "M6 3v18m0-12h8a4 4 0 0 1 4 4v4m-4-4 4 4 4-4",
  history: "M3 12a9 9 0 1 0 2.64-6.36L3 8m0-5v5h5m4-1v5l3 2",
  branches: "M6 3v18m0-12h8a4 4 0 0 1 4 4v4m-4-4 4 4 4-4",
  files: "M6 3h8l4 4v14H6zM14 3v5h5M9 12h6m-6 4h6",
  diff: "M7 4 3 8l4 4m-4-4h13M17 20l4-4-4-4m4 4H8",
  integrity: "M12 3 20 6v5c0 5-3.4 8.5-8 10-4.6-1.5-8-5-8-10V6zM9 12l2 2 4-4",
  search: "m20 20-4.5-4.5M18 10.5a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0",
  refresh: "M20 7v5h-5M4 17v-5h5m-5 0a8 8 0 0 1 14-5l2 1m-16 8 2 1a8 8 0 0 0 14-5",
};

function Icon({ name, className = "" }) {
  return (
    <svg
      className={`nav-icon ${className}`.trim()}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={iconPaths[name] || iconPaths.files} />
    </svg>
  );
}

function Sidebar({
  activePage,
  setActivePage,
  repository,
  status,
}) {
  const changedFiles = status?.files?.length ?? 0;

  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">
          M<span>G</span>
        </div>

        <div>
          <h1>MiniGit</h1>
          <p>VERSION CONTROL</p>
        </div>
      </div>

      <div className="workspace-label">WORKSPACE</div>

      <button
        className="repository-switcher"
        onClick={() => setActivePage("Overview")}
        type="button"
      >
        <div className="repo-avatar">M</div>

        <div className="repo-switcher-text">
          <strong>{repository?.name || "mini-git"}</strong>
          <span>{repository?.path || "Local repository"}</span>
        </div>

        <span className="switcher-arrow">⌄</span>
      </button>

      <div className="nav-label">DEVELOPMENT</div>

      <nav className="navigation">
        {navigation.map((item) => (
          <button
            key={item.label}
            className={`nav-item ${
              activePage === item.page ? "active" : ""
            }`}
            onClick={() => setActivePage(item.page)}
            type="button"
            aria-current={activePage === item.page ? "page" : undefined}
            aria-label={item.label}
            title={item.label}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>

            {item.page === "Source Control" &&
              changedFiles > 0 && (
                <span className="nav-badge">
                  {changedFiles}
                </span>
              )}
          </button>
        ))}
      </nav>

      <div className="sidebar-bottom">
        <div className="sidebar-repository-state">
          <span className={`status-dot ${status?.clean ? "is-clean" : ""}`} />
          <span>
            {status?.currentBranch || repository?.currentBranch || "No branch"}
          </span>
          <span className="sidebar-state-label">
            {status?.clean === true
              ? "Clean"
              : status?.clean === false
                ? `${changedFiles} change${changedFiles === 1 ? "" : "s"}`
                : "Status unavailable"}
          </span>
        </div>
      </div>
    </aside>
  );
}

function Topbar({
  activePage,
  repository,
  status,
  backendConnected,
  onRefresh,
  refreshing,
  onNavigate,
}) {
  return (
    <header className="topbar">
      <div className="breadcrumbs">
        <span>Repositories</span>
        <span className="breadcrumb-separator">/</span>

        <strong>
          {repository?.name || "mini-git"}
        </strong>

        <span className="breadcrumb-separator">/</span>

        <span className="current-crumb">
          {activePage}
        </span>
      </div>

      <div className="topbar-actions">
        <button
          type="button"
          className="topbar-branch"
          onClick={() => onNavigate("Branches")}
          title="Open branch management"
        >
          <Icon name="branches" />
          <span>
            {status?.currentBranch ||
              repository?.currentBranch ||
              "No branch"}
          </span>
        </button>

        <div className="connection-status">
          <span
            className="status-dot"
            style={{
              background: backendConnected
                ? "#59d6a5"
                : "#e8ae64",
            }}
          />

          <span>
            {backendConnected
              ? "Backend connected"
              : "Connecting"}
          </span>
        </div>

        <button
          type="button"
          className="icon-button topbar-refresh"
          title="Refresh repository status and history"
          aria-label="Refresh repository"
          onClick={onRefresh}
          disabled={refreshing}
        >
          <Icon name="refresh" />
        </button>
      </div>
    </header>
  );
}

function PageHeading({
  activePage,
  onNewCommit,
}) {
  const descriptions = {
    Overview: "Your repository at a glance.",
    "Source Control":
      "Review changes and prepare your next commit.",
    "Commit History":
      "Explore the evolution of your codebase.",
    Branches:
      "Manage independent lines of development.",
    "File Explorer":
      "Browse files in your repository.",
    "Diff Viewer":
      "Understand exactly what changed.",
    "Integrity Checker":
      "Verify content using SHA-256 hashes.",
  };

  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">
          <span className="eyebrow-dot" />
          REPOSITORY WORKSPACE
        </div>

        <h2>{activePage}</h2>

        <p>
          {descriptions[activePage] ||
            "Your MiniGit workspace."}
        </p>
      </div>

      <div className="heading-actions">
        {activePage !== "Source Control" && (
          <button
            className="button-primary"
            onClick={onNewCommit}
            type="button"
          >
            <span aria-hidden="true">+</span> New commit
          </button>
        )}
      </div>
    </div>
  );
}

function StatCard({
  label,
  value,
  detail,
  icon,
  accent,
  badge,
}) {
  return (
    <article className="stat-card">
      <div className="stat-top">
        <span className="stat-label">{label}</span>
        <span className={`stat-icon ${accent}`}>
          <Icon name={icon} />
        </span>
      </div>

      <div className="stat-value-row">
        <span className="stat-value">{value}</span>

        {badge && (
          <span className={`stat-badge ${accent}`}>
            {badge}
          </span>
        )}
      </div>

      <p className="stat-detail">{detail}</p>
    </article>
  );
}

/* -------------------------------------------------------
   REPOSITORY OVERVIEW
------------------------------------------------------- */

function RepositoryCard({ repository, status }) {
  const initialized =
    repository?.initialized ?? status?.initialized;

  return (
    <section className="panel repository-panel">
      <div className="panel-header">
        <div>
          <h3>Repository overview</h3>
          <p>Your project, organized.</p>
        </div>

        <span className="repository-state-label">
          {initialized ? "Initialized" : "Not initialized"}
        </span>
      </div>

      <div className="repository-identity">
        <div className="repository-large-icon" aria-hidden="true">
          MG
        </div>

        <div className="repository-name">
          <h4>
            {repository?.name || "MiniGit Repository"}
          </h4>

          <p>MiniGit working repository</p>
        </div>
      </div>

      <div className="repository-description">
        <p>
          A lightweight version control system built from
          scratch with content-addressed storage and
          SHA-256 integrity verification.
        </p>

        <p>
          <strong>Repository path:</strong>{" "}
          {status?.path || repository?.path || "Unknown"}
        </p>
      </div>

      <div className="repository-meta">
        <div>
          <span className="meta-label">CURRENT BRANCH</span>
          <span className="branch-pill">
            <Icon name="branches" />
            {status?.currentBranch ||
              repository?.currentBranch ||
              "Unavailable"}
          </span>
        </div>

        <div>
          <span className="meta-label">
            REPOSITORY STATUS
          </span>

          <strong>
            {initialized ? "Initialized" : "Not initialized"}
          </strong>
        </div>
      </div>

      <div className="repository-footer">
        <span>Path</span>
        <code>{status?.path || repository?.path || "Unavailable"}</code>
      </div>
    </section>
  );
}

/* -------------------------------------------------------
   COMMIT AVATAR
------------------------------------------------------- */

function CommitAvatar({ author, size = "normal" }) {
  return (
    <div
      className={`commit-avatar commit-avatar-${size}`}
      style={{
        background: getAvatarColor(author),
      }}
      title={author}
      aria-label={`Author: ${author}`}
    >
      {getInitials(author)}
    </div>
  );
}

/* -------------------------------------------------------
   COPY HASH BUTTON
------------------------------------------------------- */

function CopyHashButton({ hash }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy(event) {
    event.stopPropagation();

    const success = await copyText(hash);

    if (success) {
      setCopied(true);

      window.setTimeout(() => {
        setCopied(false);
      }, 1500);
    }
  }

  return (
    <button
      type="button"
      className="copy-hash-button"
      onClick={handleCopy}
      disabled={!hash}
      title="Copy full commit hash"
      aria-label="Copy full commit hash"
    >
      {copied ? "✓ Copied" : "▢"}
    </button>
  );
}

/* -------------------------------------------------------
   COMMIT STATISTICS
------------------------------------------------------- */

function CommitStats({ commit, compact = false }) {
  const stats = getCommitStats(commit);

  return (
    <div
      className={`commit-stats ${
        compact ? "commit-stats-compact" : ""
      }`}
    >
      <span className="commit-file-stat">
        <span aria-hidden="true">▤</span>
        {stats.fileCount}{" "}
        {stats.fileCount === 1 ? "file" : "files"}
      </span>

      {stats.hasLineStats ? (
        <>
          <span className="commit-additions">
            +{stats.additions}
          </span>

          <span className="commit-deletions">
            −{stats.deletions}
          </span>
        </>
      ) : (
        <span className="commit-stats-unavailable">
          Line stats unavailable
        </span>
      )}
    </div>
  );
}

/* -------------------------------------------------------
   COMMIT CARD
------------------------------------------------------- */

function CommitCard({
  commit,
  index,
  last,
  compact,
  onSelect,
}) {
  const hash = getCommitHash(commit);
  const message = getCommitMessage(commit);
  const author = getCommitAuthor(commit);
  const timestamp = getCommitTimestamp(commit);
  const branch = getCommitBranch(commit);

  return (
    <article
      className={`commit-card ${
        compact ? "commit-card-compact" : ""
      }`}
    >
      <div className="commit-card-timeline">
        <div
          className="commit-timeline-node"
          style={{
            "--commit-accent": getAvatarColor(author),
          }}
        >
          {index === 0 ? "●" : "•"}
        </div>

        {!last && <div className="commit-timeline-line" />}
      </div>

      <button
        type="button"
        className="commit-card-main"
        onClick={() => onSelect(commit)}
        aria-label={`View commit details: ${message}`}
      >
        <div className="commit-card-topline">
          <h4 className="commit-card-message">
            {message}
          </h4>

          <span className="commit-card-chevron">
            ↗
          </span>
        </div>

        <div className="commit-card-author">
          <CommitAvatar author={author} />

          <span className="commit-author-name">
            {author}
          </span>

          <span className="commit-metadata-dot">·</span>

          <span
            className="commit-relative-time"
            title={formatDate(timestamp)}
          >
            {formatRelativeTime(timestamp) ||
              formatDate(timestamp)}
          </span>
        </div>

        <div className="commit-card-bottomline">
          {branch && (
            <span className="commit-branch-pill">
              <Icon name="branches" /> {branch}
            </span>
          )}

          <CommitStats
            commit={commit}
            compact={compact}
          />
        </div>
      </button>

      <div className="commit-card-hash">
        <code title={hash}>
          {hash ? hash.slice(0, 7) : "No hash"}
        </code>

        <CopyHashButton hash={hash} />
      </div>
    </article>
  );
}

/* -------------------------------------------------------
   COMMIT HISTORY
------------------------------------------------------- */

function CommitHistory({
  commits = [],
  compact = false,
  loading = false,
  error = "",
  onRefresh,
  onSelectCommit,
}) {
  const visibleCommits = compact
    ? commits.slice(0, 4)
    : commits;

  return (
    <section
      className={`panel commits-panel ${
        compact ? "compact-commits" : ""
      }`}
    >
      <div className="panel-header">
        <div>
          <h3>
            {compact ? "Recent commits" : "Commit history"}
          </h3>

          <p>
            {compact
              ? "Recent commits from your repository."
              : "Explore your repository's development timeline."}
          </p>
        </div>

        <span className="commit-count">
          {loading
            ? "Loading..."
            : `${commits.length} commits`}
        </span>
      </div>

      {error && (
        <div
          className="commit-feedback commit-feedback-error"
          role="alert"
        >
          <p>{error}</p>

          {onRefresh && (
            <button
              className="button-secondary"
              onClick={onRefresh}
            >
              Retry
            </button>
          )}
        </div>
      )}

      {loading ? (
        <div className="commit-empty-state">
          <div className="commit-loading-spinner" />
          <h4>Loading commit history</h4>
          <p>Retrieving commits from your repository.</p>
        </div>
      ) : !error && visibleCommits.length === 0 ? (
        <div className="commit-empty-state">
          <div className="commit-empty-icon">◷</div>

          <h4>No commits found</h4>

          <p>
            Create a commit from Source Control to start
            your repository history.
          </p>
        </div>
      ) : (
        !error && (
          <div className="commit-timeline-list">
            {visibleCommits.map((commit, index) => (
              <CommitCard
                key={
                  getCommitHash(commit) ||
                  `${getCommitMessage(commit)}-${index}`
                }
                commit={commit}
                index={index}
                last={index === visibleCommits.length - 1}
                compact={compact}
                onSelect={onSelectCommit}
              />
            ))}
          </div>
        )
      )}

      {!loading &&
        !error &&
        visibleCommits.length > 0 && (
          <div className="commit-history-footer">
            <span>
              Showing {visibleCommits.length} of{" "}
              {commits.length} commits
            </span>

            {onRefresh && (
              <button
                className="commit-refresh-button"
                onClick={onRefresh}
              >
                ↻ Refresh history
              </button>
            )}
          </div>
        )}
    </section>
  );
}

/* -------------------------------------------------------
   FILE DIFF VIEWER
------------------------------------------------------- */

function FileDiffViewer({ commitHash, file, onLoadDiff }) {
  const path = getFilePath(file);

  const [expanded, setExpanded] = useState(false);
  const [diffData, setDiffData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadDiff() {
    if (!commitHash || !path) {
      setError("Missing commit hash or file path.");
      return;
    }

    setLoading(true);
    setError("");

    try {
      const result = await getCommitDiff(commitHash, path);

      if (result?.success === false) {
        throw new Error(
          result.message || "Unable to load file diff."
        );
      }

      setDiffData(result);
      onLoadDiff?.(path, result);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to load file diff."
      );
    } finally {
      setLoading(false);
    }
  }

  function toggleExpanded() {
    if (!expanded && !diffData && !loading && !error) {
      loadDiff();
    }

    setExpanded((value) => !value);
  }

  const lines = Array.isArray(diffData?.lines)
    ? diffData.lines
    : [];

  const status =
    diffData?.status ?? getFileStatus(file);

  return (
    <div className="commit-detail-file">
      <button
        type="button"
        className="commit-detail-file-header"
        onClick={toggleExpanded}
      >
        <span className="commit-detail-file-chevron">
          {expanded ? "⌄" : "›"}
        </span>

        <span className="commit-detail-file-icon">
          ▤
        </span>

        <span className="commit-detail-file-path">
          {path}
        </span>

        {file?.hash && (
          <code className="commit-detail-file-hash" title={file.hash}>
            {file.hash.slice(0, 10)}
          </code>
        )}
        {file?.oldPath && (
          <span className="commit-detail-file-rename">
            from {file.oldPath}
          </span>
        )}

        <span className="commit-detail-file-stats">
          <span
            className={`file-status status-${getFileStatusClass(
              status
            )}`}
          >
            {status}
          </span>
        </span>
      </button>

      {expanded && (
        <div className="commit-file-diff">
          {loading ? (
            <div className="commit-no-diff">
              Loading file diff...
            </div>
          ) : error ? (
            <div className="commit-no-diff">
              <p>{error}</p>

              <button
                type="button"
                className="button-secondary"
                onClick={loadDiff}
              >
                Retry
              </button>
            </div>
          ) : diffData ? (
            <>
              <div className="commit-diff-summary">
                <span>
                  Status: <strong>{status}</strong>
                </span>
              </div>

              {lines.length > 0 ? (
                <pre className="commit-diff-code">
                  {lines.map((line, index) => {
                    const type = String(
                      line.type ?? "CONTEXT"
                    ).toUpperCase();

                    const lineClass =
                      type === "ADDED" ||
                      type === "ADD" ||
                      type === "INSERT" ||
                      type === "ADDITION"
                        ? "diff-added"
                        : type === "REMOVED" ||
                            type === "DELETED" ||
                            type === "DELETE" ||
                            type === "DELETION"
                          ? "diff-removed"
                          : type === "CONTEXT" || type === "UNCHANGED"
                            ? "diff-context"
                            : "diff-neutral";

                    const prefix =
                      type === "ADDED" ||
                      type === "ADD" ||
                      type === "INSERT" ||
                      type === "ADDITION"
                        ? "+"
                        : type === "REMOVED" ||
                            type === "DELETED" ||
                            type === "DELETE" ||
                            type === "DELETION"
                          ? "-"
                          : type === "CONTEXT" || type === "UNCHANGED"
                            ? " "
                            : "·";

                    return (
                      <div
                        className={`commit-diff-line ${lineClass}`}
                        key={`${index}-${line.content ?? ""}`}
                      >
                        <span className="commit-diff-line-number">
                          {index + 1}
                        </span>

                        <span className="commit-diff-line-content">
                          {prefix}
                          {getDiffLineContent(line)}
                        </span>
                      </div>
                    );
                  })}
                </pre>
              ) : (
                <div className="commit-no-diff">
                  <p>No line-by-line changes were returned.</p>

                  {status === "UNCHANGED" && (
                    <p>
                      This file has identical content in the
                      selected commit and its parent.
                    </p>
                  )}

                  {diffData.oldContent != null && (
                    <>
                      <h4>Previous content</h4>
                      <pre className="commit-diff-code">
                        {diffData.oldContent || "(Empty file)"}
                      </pre>
                    </>
                  )}

                  {diffData.newContent != null && (
                    <>
                      <h4>Current content</h4>
                      <pre className="commit-diff-code">
                        {diffData.newContent || "(Empty file)"}
                      </pre>
                    </>
                  )}
                </div>
              )}
            </>
          ) : null}
        </div>
      )}
    </div>
  );
}


/* -------------------------------------------------------
   DIFF VIEWER PAGE
------------------------------------------------------- */

function DiffViewerPage({
  commits = [],
  loading = false,
  error = "",
  onRefresh,
}) {
  const [selectedCommitHash, setSelectedCommitHash] = useState("");
  const [changedFiles, setChangedFiles] = useState([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState("");
  const [selectedFilePath, setSelectedFilePath] = useState("");
  const [diffData, setDiffData] = useState(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState("");
  const fileRequestId = useRef(0);
  const diffRequestId = useRef(0);

  useEffect(
    () => () => {
      fileRequestId.current += 1;
      diffRequestId.current += 1;
    },
    []
  );

  async function loadCommitFiles(commitHash, commit) {
    const requestId = ++fileRequestId.current;
    diffRequestId.current += 1;
    setSelectedCommitHash(commitHash);
    setChangedFiles([]);
    setFilesError("");
    setFilesLoading(Boolean(commitHash));
    setSelectedFilePath("");
    setDiffData(null);
    setDiffLoading(false);
    setDiffError("");

    if (!commitHash) return;

    try {
      const details = await getCommitDetails(commitHash);

      if (details?.success === false) {
        throw new Error(
          details.message || "Unable to load files for this commit."
        );
      }

      const filesValue =
        details?.changedFiles ??
        details?.changed_files ??
        commit?.changedFiles ??
        commit?.changed_files ??
        commit?.fileChanges;
      const files = getCommitFiles({ changedFiles: filesValue });
      const uniqueFiles = [
        ...new Map(
          files
            .map((file) => [getFilePath(file), file])
            .filter(([path]) => path && path !== "Unknown file")
        ).values(),
      ];

      if (requestId === fileRequestId.current) {
        setChangedFiles(uniqueFiles);
      }
    } catch (requestError) {
      if (requestId === fileRequestId.current) {
        setFilesError(
          requestError instanceof Error
            ? requestError.message
            : "Unable to load files for this commit."
        );
      }
    } finally {
      if (requestId === fileRequestId.current) {
        setFilesLoading(false);
      }
    }
  }

  function handleCommitChange(event) {
    const commitHash = event.target.value;
    const commit = commits.find(
      (entry) => getCommitHash(entry) === commitHash
    );

    loadCommitFiles(commitHash, commit);
  }

  function handleFileChange(event) {
    diffRequestId.current += 1;
    setSelectedFilePath(event.target.value);
    setDiffData(null);
    setDiffLoading(false);
    setDiffError("");
  }

  async function showChanges() {
    if (!selectedCommitHash || !selectedFilePath || diffLoading) return;

    const requestId = ++diffRequestId.current;
    setDiffData(null);
    setDiffError("");
    setDiffLoading(true);

    try {
      const result = await getCommitDiff(
        selectedCommitHash,
        selectedFilePath
      );

      if (result?.success === false) {
        throw new Error(
          result.message || "Unable to load the selected file diff."
        );
      }

      if (!result || typeof result !== "object") {
        throw new Error("The diff endpoint returned an invalid response.");
      }

      if (requestId === diffRequestId.current) {
        setDiffData(result);
      }
    } catch (requestError) {
      if (requestId === diffRequestId.current) {
        setDiffError(
          requestError instanceof Error
            ? requestError.message
            : "Unable to load the selected file diff."
        );
      }
    } finally {
      if (requestId === diffRequestId.current) {
        setDiffLoading(false);
      }
    }
  }

  const selectedCommit = commits.find(
    (commit) => getCommitHash(commit) === selectedCommitHash
  );
  const selectedFile = changedFiles.find(
    (file) => getFilePath(file) === selectedFilePath
  );
  const diffLines = Array.isArray(diffData?.lines)
    ? diffData.lines
    : [];

  return (
    <section className="panel diff-viewer-panel">
      <div className="panel-header">
        <div>
          <h3>Compare a commit file</h3>
          <p>Select a commit and one of its changed files to inspect.</p>
        </div>
      </div>

      {error && (
        <div className="commit-feedback commit-feedback-error" role="alert">
          <p>{error}</p>
          {onRefresh && (
            <button
              type="button"
              className="button-secondary"
              onClick={onRefresh}
            >
              Retry commit history
            </button>
          )}
        </div>
      )}

      {loading ? (
        <div className="diff-viewer-state" role="status">
          Loading commit history...
        </div>
      ) : error ? null : commits.length === 0 ? (
        <div className="diff-viewer-state">
          No commits are available to compare.
        </div>
      ) : (
        <>
          <div className="diff-viewer-controls">
            <label className="diff-viewer-field">
              <span>Commit</span>
              <select
                value={selectedCommitHash}
                onChange={handleCommitChange}
              >
                <option value="">Select a commit</option>
                {commits.map((commit, index) => {
                  const hash = getCommitHash(commit);
                  if (!hash) return null;

                  return (
                    <option key={`${hash}-${index}`} value={hash}>
                      {getCommitMessage(commit)} ({hash.slice(0, 7)})
                    </option>
                  );
                })}
              </select>
            </label>

            <label className="diff-viewer-field">
              <span>Changed file</span>
              <select
                value={selectedFilePath}
                onChange={handleFileChange}
                disabled={
                  !selectedCommitHash ||
                  filesLoading ||
                  changedFiles.length === 0
                }
              >
                <option value="">
                  {filesLoading
                    ? "Loading changed files..."
                    : "Select a changed file"}
                </option>
                {changedFiles.map((file, index) => {
                  const path = getFilePath(file);
                  const status = getFileStatus(file);

                  return (
                    <option key={`${path}-${index}`} value={path}>
                      {file?.oldPath
                        ? `${file.oldPath} → ${path} — ${status}`
                        : `${path} — ${status}`}
                    </option>
                  );
                })}
              </select>
            </label>
          </div>

          {filesError && (
            <div className="commit-feedback commit-feedback-error" role="alert">
              <p>{filesError}</p>
              <button
                type="button"
                className="button-secondary"
                onClick={() =>
                  loadCommitFiles(selectedCommitHash, selectedCommit)
                }
                disabled={filesLoading}
              >
                {filesLoading ? "Retrying..." : "Retry"}
              </button>
            </div>
          )}

          {selectedCommitHash &&
            !filesLoading &&
            !filesError &&
            changedFiles.length === 0 && (
              <div className="diff-viewer-state">
                This commit has no changed files to display.
              </div>
            )}

          <div className="diff-viewer-action-row">
            {selectedCommit && (
              <span className="diff-viewer-commit-label">
                {getCommitMessage(selectedCommit)} ·{" "}
                {selectedCommitHash.slice(0, 7)}
              </span>
            )}
            <button
              type="button"
              className="button-primary"
              onClick={showChanges}
              disabled={
                !selectedCommitHash ||
                !selectedFilePath ||
                filesLoading ||
                diffLoading
              }
            >
              {diffLoading ? "Loading changes..." : "Show changes"}
            </button>
          </div>

          {diffError && (
            <div className="commit-feedback commit-feedback-error" role="alert">
              <p>{diffError}</p>
            </div>
          )}

          {diffLoading && (
            <div className="diff-viewer-state" role="status">
              Loading file changes...
            </div>
          )}

          {diffData && !diffLoading && (
            <div className="diff-viewer-result">
              <div className="commit-diff-summary">
                <span>
                  File: <strong>{diffData.path ?? selectedFilePath}</strong>
                </span>
                {diffData.renameFrom && (
                  <span>
                    Renamed from: <strong>{diffData.renameFrom}</strong>
                  </span>
                )}
                <span>
                  Status:{" "}
                  <strong>
                    {diffData.status ?? getFileStatus(selectedFile)}
                  </strong>
                </span>
              </div>

              {diffLines.length > 0 ? (
                <pre className="commit-diff-code diff-viewer-code">
                  {diffLines.map((line, index) => {
                    const type = String(
                      line?.type ?? "CONTEXT"
                    ).toUpperCase();
                    const isAddition = [
                      "ADDED",
                      "ADD",
                      "INSERT",
                      "ADDITION",
                    ].includes(type);
                    const isDeletion = [
                      "REMOVED",
                      "DELETED",
                      "DELETE",
                      "DELETION",
                    ].includes(type);
                    const lineClass = isAddition
                      ? "diff-added"
                      : isDeletion
                        ? "diff-removed"
                        : "diff-neutral";
                    const prefix = isAddition
                      ? "+"
                      : isDeletion
                        ? "−"
                        : " ";

                    return (
                      <div
                        className={`commit-diff-line ${lineClass}`}
                        key={`${index}-${getDiffLineContent(line)}`}
                      >
                        <span className="commit-diff-line-number">
                          {index + 1}
                        </span>
                        <span className="commit-diff-line-content">
                          {prefix}
                          {getDiffLineContent(line)}
                        </span>
                      </div>
                    );
                  })}
                </pre>
              ) : (
                <div className="diff-viewer-state">
                  <p>No line-by-line changes were returned.</p>
                  {diffData.status === "UNCHANGED" && (
                    <p>
                      This file has identical content in the selected commit
                      and its parent.
                    </p>
                  )}
                  {diffData.oldContent != null && (
                    <>
                      <h4>Previous content</h4>
                      <pre className="commit-diff-code diff-viewer-content">
                        {String(diffData.oldContent).replace(/\r/g, "") ||
                          "(Empty file)"}
                      </pre>
                    </>
                  )}
                  {diffData.newContent != null && (
                    <>
                      <h4>Current content</h4>
                      <pre className="commit-diff-code diff-viewer-content">
                        {String(diffData.newContent).replace(/\r/g, "") ||
                          "(Empty file)"}
                      </pre>
                    </>
                  )}
                </div>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}


/* -------------------------------------------------------
   COMMIT DETAILS
------------------------------------------------------- */

function CommitDetails({ commit, onBack }) {
  const hash = getCommitHash(commit);

  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    let cancelled = false;

    async function loadDetails() {
      setDetails(null);
      if (!hash) {
        setError("This commit has no valid hash.");
        setLoading(false);
        return;
      }

      setLoading(true);
      setError("");

      try {
        const result = await getCommitDetails(hash);

        if (result?.success === false) {
          throw new Error(
            result.message || "Unable to load commit details."
          );
        }

        if (!cancelled) {
          setDetails(result);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : "Unable to load commit details."
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadDetails();

    return () => {
      cancelled = true;
    };
  }, [hash, retryKey]);

  const message =
    details?.message ?? getCommitMessage(commit);

  const author = getCommitAuthor(commit);

  const timestamp =
    details?.timestamp ?? getCommitTimestamp(commit);

  const branch = getCommitBranch(commit);

  const parent =
    details?.parent ??
    commit?.parent ??
    commit?.parentHash ??
    null;

  /*
   * `files` is the file snapshot stored in this commit.
   * `changedFiles` is the separate list of files reported
   * as changed by the backend.
   */
  const snapshotFiles = details
    ? getSnapshotFiles(details.files)
    : getSnapshotFiles(commit?.files);
  const changedFiles = details
    ? getChangedFiles(
        details.changedFiles ?? details.changed_files
      )
    : getChangedFiles(
        commit?.changedFiles ?? commit?.changed_files
      );

  const stats = {
    fileCount: details?.fileCount ?? snapshotFiles.length,
    additions: 0,
    deletions: 0,
    hasLineStats: false,
  };

  async function handleCopyFullHash() {
    const success = await copyText(hash);

    if (success) {
      setCopied(true);

      window.setTimeout(() => {
        setCopied(false);
      }, 1500);
    }
  }

  return (
    <div className="commit-details-page">
      <button
        type="button"
        className="commit-back-button"
        onClick={onBack}
      >
        <span>←</span> Back to commit history
      </button>

      {error && (
        <div
          className="commit-feedback commit-feedback-error"
          role="alert"
        >
          <p>{error}</p>

          <button
            type="button"
            className="button-secondary"
            onClick={() => {
              setRetryKey((value) => value + 1);
            }}
          >
            Retry
          </button>
        </div>
      )}

      {loading && (
        <div className="panel" style={{ padding: "20px" }}>
          Loading commit details from the Java backend...
        </div>
      )}

      <section className="panel commit-detail-hero">
        <div className="commit-detail-hero-top">
          <span className="commit-detail-label">
            COMMIT DETAILS
          </span>

          <span className="commit-branch-pill">
            <span>⑂</span> {branch}
          </span>
        </div>

        <h3 className="commit-detail-title">
          {message}
        </h3>

        <div className="commit-detail-author-row">
          <CommitAvatar author={author} size="large" />

          <div className="commit-detail-author-info">
            <strong>{author}</strong>

            <span>
              committed{" "}
              {formatRelativeTime(timestamp) ||
                formatDate(timestamp)}
            </span>
          </div>
        </div>

        <div className="commit-detail-meta-grid">
          <div className="commit-detail-meta-item">
            <span>COMMIT HASH</span>

            <div className="commit-full-hash-row">
              <code>{details?.hash ?? hash}</code>

              <button
                type="button"
                className="button-secondary commit-copy-full"
                onClick={handleCopyFullHash}
                disabled={!hash}
              >
                {copied ? "✓ Copied" : "▢ Copy"}
              </button>
            </div>
          </div>

          <div className="commit-detail-meta-item">
            <span>COMMITTED AT</span>
            <strong>{formatDate(timestamp)}</strong>
          </div>

          <div className="commit-detail-meta-item">
            <span>BRANCH</span>
            <strong>{branch}</strong>
          </div>

          <div className="commit-detail-meta-item">
            <span>PARENT COMMIT</span>

            {parent ? (
              <code>{String(parent)}</code>
            ) : (
              <strong>Initial commit</strong>
            )}
          </div>
        </div>
      </section>

      <section className="panel commit-detail-summary">
        <div className="panel-header">
          <div>
            <h3>Commit summary</h3>

            <p>
              Metadata returned by your MiniGit backend.
            </p>
          </div>
        </div>

        <div className="commit-detail-summary-grid">
          <div className="commit-summary-stat">
            <span>Files in commit</span>
            <strong>{stats.fileCount}</strong>
          </div>

          <div className="commit-summary-stat">
            <span>Lines added</span>

            <strong className="commit-additions">—</strong>
          </div>

          <div className="commit-summary-stat">
            <span>Lines deleted</span>

            <strong className="commit-deletions">—</strong>
          </div>
        </div>
      </section>

      <section className="panel commit-detail-files-panel">
        <div className="panel-header">
          <div>
            <h3>Changed files</h3>

            <p>
              Files the backend reports as changed from the
              parent commit.
            </p>
          </div>

          <span className="commit-count">
            {changedFiles.length} files
          </span>
        </div>

        {changedFiles.length === 0 ? (
          <div className="commit-empty-state">
            <div className="commit-empty-icon">▤</div>

            <h4>No changed files reported</h4>

            <p>
              This commit has no changedFiles entries from
              the backend.
            </p>
          </div>
        ) : (
          <div className="commit-detail-files-list">
            {changedFiles.map((file, index) => (
              <FileDiffViewer
                key={`${hash}-changed-${getFilePath(file)}-${index}`}
                commitHash={hash}
                file={file}
              />
            ))}
          </div>
        )}
      </section>

      <section className="panel commit-detail-files-panel">
        <div className="panel-header">
          <div>
            <h3>Files in commit snapshot</h3>
            <p>Tracked file paths and blob hashes stored in this commit.</p>
          </div>
          <span className="commit-count">{snapshotFiles.length} files</span>
        </div>

        {snapshotFiles.length === 0 ? (
          <div className="commit-empty-state">
            <h4>No snapshot files returned</h4>
            <p>The backend did not return files for this commit.</p>
          </div>
        ) : (
          <div className="commit-detail-files-list">
            {snapshotFiles.map((file, index) => (
              <FileDiffViewer
                key={`${hash}-snapshot-${getFilePath(file)}-${index}`}
                commitHash={hash}
                file={file}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

/* -------------------------------------------------------
   BRANCHES
------------------------------------------------------- */

function errorText(error, fallback) {
  return error instanceof Error ? error.message : fallback;
}

function shortCommitHash(hash) {
  return hash ? hash.slice(0, 8) : "no commits yet";
}

/**
 * Loads the branch list from the Java backend.
 * Reloads whenever `refreshKey` changes.
 */
function useBranchData(refreshKey) {
  const [branches, setBranches] = useState([]);
  const [currentBranch, setCurrentBranch] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function loadBranches() {
      setLoading(true);
      setError("");

      try {
        const data = await getBranches();

        if (cancelled) return;

        setBranches(
          Array.isArray(data?.branches) ? data.branches : []
        );
        setCurrentBranch(data?.currentBranch || "");
      } catch (loadError) {
        if (!cancelled) {
          setError(
            errorText(loadError, "Unable to load branches.")
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadBranches();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  return { branches, currentBranch, loading, error };
}

function BranchCard({
  refreshKey,
  onNavigate,
  onBranchChanged,
}) {
  const { branches, loading, error } = useBranchData(refreshKey);

  const [switchingTo, setSwitchingTo] = useState("");
  const [switchError, setSwitchError] = useState("");

  async function handleSwitch(name) {
    setSwitchingTo(name);
    setSwitchError("");

    try {
      await checkoutBranch(name);

      if (onBranchChanged) {
        onBranchChanged({ switched: true });
      }
    } catch (switchFailure) {
      setSwitchError(
        errorText(switchFailure, "Unable to switch branch.")
      );
    } finally {
      setSwitchingTo("");
    }
  }

  const visibleBranches = branches.slice(0, 5);

  return (
    <section className="panel branch-panel">
      <div className="panel-header">
        <div>
          <h3>Branches</h3>
          <p>Branches available in this repository.</p>
        </div>

        <button
          className="small-add-button"
          onClick={() => onNavigate && onNavigate("Branches")}
        >
          + New
        </button>
      </div>

      {loading && branches.length === 0 && (
        <div className="source-empty-state" role="status">
          Loading branches...
        </div>
      )}

      {error && branches.length === 0 && (
        <div className="source-empty-state" role="alert">
          <p>Unable to load branches.</p>
          <p>{error}</p>
          <button
            type="button"
            className="button-secondary"
            onClick={() => onNavigate && onNavigate("Branches")}
          >
            Open branch management
          </button>
        </div>
      )}

      {!loading && !error && branches.length === 0 && (
        <div className="source-empty-state">
          <p>No branches were returned by the backend.</p>
        </div>
      )}

      {visibleBranches.map((branch) => (
        <div className="branch-item" key={branch.name}>
          <div
            className={`branch-icon ${
              branch.current ? "main-branch-icon" : ""
            }`}
          >
            ⑂
          </div>

          <div className="branch-info">
            <strong>{branch.name}</strong>
            <span>
              {branch.current
                ? "Current branch"
                : shortCommitHash(branch.commit)}
            </span>
          </div>

          {branch.current ? (
            <span className="current-branch-badge">
              Current
            </span>
          ) : (
            <button
              className="button-secondary"
              disabled={Boolean(switchingTo)}
              onClick={() => handleSwitch(branch.name)}
            >
              {switchingTo === branch.name
                ? "Switching..."
                : "Switch"}
            </button>
          )}
        </div>
      ))}

      {switchError && (
        <div
          className="commit-feedback commit-feedback-error"
          role="alert"
        >
          {switchError}
        </div>
      )}

      <div className="branch-footer">
        <span>
          <i className="branch-status-dot" />
          {error
            ? error
            : loading
              ? "Loading branches..."
              : `${branches.length} ${
                  branches.length === 1 ? "branch" : "branches"
                }`}
        </span>

        {branches.length > 5 && (
          <button
            className="view-all-button"
            onClick={() => onNavigate && onNavigate("Branches")}
          >
            All branches <span>→</span>
          </button>
        )}
      </div>
    </section>
  );
}

const REFLOG_LABELS = {
  commit: "Commit",
  checkout: "Switched branch",
  "branch-create": "Branch created",
  "branch-delete": "Branch deleted",
  "undo-commit": "Commit undone",
};

function BranchesPage({ refreshKey, onBranchChanged }) {
  const { branches, currentBranch, loading, error } =
    useBranchData(refreshKey);

  const [newName, setNewName] = useState("");
  const [switchAfterCreate, setSwitchAfterCreate] =
    useState(true);

  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const [actionError, setActionError] = useState("");

  const [reflog, setReflog] = useState([]);
  const [reflogError, setReflogError] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function loadReflog() {
      setReflogError("");

      try {
        const data = await getReflog(15);

        if (!cancelled) {
          setReflog(
            Array.isArray(data?.entries) ? data.entries : []
          );
        }
      } catch (loadError) {
        if (!cancelled) {
          setReflogError(
            errorText(loadError, "Unable to load activity.")
          );
        }
      }
    }

    loadReflog();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  async function runAction(key, task, switched) {
    setBusy(key);
    setMessage("");
    setActionError("");

    try {
      const result = await task();

      setMessage(result?.message || "Done.");

      if (onBranchChanged) {
        onBranchChanged({ switched });
      }

      return true;
    } catch (failure) {
      setActionError(errorText(failure, "Operation failed."));
      return false;
    } finally {
      setBusy("");
    }
  }

  async function handleCreate(event) {
    event.preventDefault();

    const name = newName.trim();

    if (!name) {
      setActionError("Enter a branch name.");
      return;
    }

    const created = await runAction(
      "create",
      () => createBranch(name, { checkout: switchAfterCreate }),
      switchAfterCreate
    );

    if (created) {
      setNewName("");
    }
  }

  function handleSwitch(name) {
    return runAction(
      `switch:${name}`,
      () => checkoutBranch(name),
      true
    );
  }

  async function handleDelete(name) {
    if (!window.confirm(`Delete branch "${name}"?`)) return;

    setBusy(`delete:${name}`);
    setMessage("");
    setActionError("");

    try {
      let result;

      try {
        result = await deleteBranch(name, false);
      } catch (deleteFailure) {
        const text = errorText(deleteFailure, "");

        const forceIt =
          text.includes("Use force") &&
          window.confirm(
            `${text}\n\nDelete it anyway? You can still recover ` +
              "it from the activity list below."
          );

        if (!forceIt) {
          throw deleteFailure;
        }

        result = await deleteBranch(name, true);
      }

      setMessage(result?.message || "Branch deleted.");

      if (onBranchChanged) {
        onBranchChanged({ switched: false });
      }
    } catch (failure) {
      setActionError(
        errorText(failure, "Unable to delete branch.")
      );
    } finally {
      setBusy("");
    }
  }

  async function handleRecover(entry) {
    const suggestion = `recovered-${entry.oldHash.slice(0, 7)}`;

    const name = window.prompt(
      "Name for the recovered branch:",
      suggestion
    );

    if (!name || !name.trim()) return;

    await runAction(
      `recover:${entry.oldHash}`,
      () => createBranch(name.trim(), { from: entry.oldHash }),
      false
    );
  }

  return (
    <section className="feature-page">
      <div className="panel branches-page-panel">
        <div className="panel-header">
          <div>
            <h3>Branches</h3>
            <p>
              Create branches, switch between them and clean up
              the ones you no longer need.
            </p>
          </div>

          <span className="commit-count">
            {branches.length} total
          </span>
        </div>

        <form className="branch-form" onSubmit={handleCreate}>
          <input
            className="branch-input"
            type="text"
            placeholder="new-branch-name"
            value={newName}
            maxLength={100}
            onChange={(event) => setNewName(event.target.value)}
            disabled={Boolean(busy)}
            aria-label="New branch name"
          />

          <label className="branch-checkbox">
            <input
              type="checkbox"
              checked={switchAfterCreate}
              onChange={(event) =>
                setSwitchAfterCreate(event.target.checked)
              }
              disabled={Boolean(busy)}
            />
            Switch to it
          </label>

          <button
            type="submit"
            className="button-primary"
            disabled={Boolean(busy) || !newName.trim()}
          >
            {busy === "create" ? "Creating..." : "+ Create branch"}
          </button>
        </form>

        {message && (
          <div
            className="commit-feedback commit-feedback-success"
            role="status"
          >
            {message}
          </div>
        )}

        {actionError && (
          <div
            className="commit-feedback commit-feedback-error"
            role="alert"
          >
            {actionError}
          </div>
        )}

        {error && (
          <div
            className="commit-feedback commit-feedback-error"
            role="alert"
          >
            {error}
          </div>
        )}

        {loading && branches.length === 0 ? (
          <p className="commit-help-text">Loading branches...</p>
        ) : (
          branches.map((branch) => (
            <div className="branch-item" key={branch.name}>
              <div
                className={`branch-icon ${
                  branch.current ? "main-branch-icon" : ""
                }`}
              >
                ⑂
              </div>

              <div className="branch-info">
                <strong>{branch.name}</strong>
                <span>
                  {shortCommitHash(branch.commit)}
                </span>
              </div>

              {branch.current ? (
                <span className="current-branch-badge">
                  Current
                </span>
              ) : (
                <div className="branch-actions">
                  <button
                    className="button-secondary"
                    disabled={Boolean(busy)}
                    onClick={() => handleSwitch(branch.name)}
                  >
                    {busy === `switch:${branch.name}`
                      ? "Switching..."
                      : "Switch"}
                  </button>

                  <button
                    className="button-secondary"
                    disabled={Boolean(busy)}
                    onClick={() => handleDelete(branch.name)}
                  >
                    {busy === `delete:${branch.name}`
                      ? "Deleting..."
                      : "Delete"}
                  </button>
                </div>
              )}
            </div>
          ))
        )}

        {!loading && currentBranch && (
          <p className="commit-help-text">
            You are on <code>{currentBranch}</code>. Switching is
            blocked while uncommitted work would be overwritten.
          </p>
        )}
      </div>

      <div className="panel branches-page-panel">
        <div className="panel-header">
          <div>
            <h3>Recent activity</h3>
            <p>
              Every branch movement is logged, so undone commits
              and deleted branches can be recovered.
            </p>
          </div>
        </div>

        {reflogError && (
          <div
            className="commit-feedback commit-feedback-error"
            role="alert"
          >
            {reflogError}
          </div>
        )}

        {reflog.length === 0 && !reflogError ? (
          <p className="commit-help-text">
            No activity recorded yet.
          </p>
        ) : (
          reflog.map((entry, index) => {
            const recoverable =
              (entry.action === "undo-commit" ||
                entry.action === "branch-delete") &&
              Boolean(entry.oldHash);

            return (
              <div
                className="branch-item reflog-row"
                key={`${entry.timestamp}-${index}`}
              >
                <div className="branch-info">
                  <strong>
                    {REFLOG_LABELS[entry.action] || entry.action}
                    {" · "}
                    {entry.branch}
                  </strong>
                  <span>
                    {entry.detail}
                    {entry.newHash
                      ? ` (${entry.newHash.slice(0, 8)})`
                      : ""}
                  </span>
                </div>

                {recoverable && (
                  <button
                    className="button-secondary"
                    disabled={Boolean(busy)}
                    onClick={() => handleRecover(entry)}
                  >
                    {busy === `recover:${entry.oldHash}`
                      ? "Recovering..."
                      : "Recover"}
                  </button>
                )}
              </div>
            );
          })
        )}
      </div>
    </section>
  );
}

/* -------------------------------------------------------
   FILE STATUS HELPERS
------------------------------------------------------- */

function getFileStatusClass(status) {
  return String(status || "UNKNOWN")
    .toLowerCase()
    .replace(/[^a-z0-9-]/g, "-");
}

function getFileType(path) {
  const extension = path
    .split(".")
    .pop()
    ?.toLowerCase();

  if (extension === "java") return "java";

  if (
    extension === "js" ||
    extension === "jsx"
  ) {
    return "javascript";
  }

  if (extension === "css") return "css";
  if (extension === "html") return "html";
  if (extension === "json") return "json";
  if (extension === "md") return "markdown";

  return "generic";
}

/* -------------------------------------------------------
   COMMIT PANEL
------------------------------------------------------- */

function CommitPanel({
  status,
  onCommit,
  committing,
  commitMessage,
  setCommitMessage,
  commitError,
  commitResult,
  onUnstageFile,
  onUnstageAll,
  unstagingPath,
  onUndoCommit,
  undoing,
  canUndo,
}) {
  const stagedFiles = (
    status?.files || []
  ).filter((file) => file.status === "STAGED");

  const canCommit =
    stagedFiles.length > 0 &&
    commitMessage.trim().length > 0 &&
    !committing;

  return (
    <section className="panel commit-panel">
      <div className="panel-header">
        <div>
          <h3>Commit changes</h3>

          <p>
            Save your staged files in the repository history.
          </p>
        </div>

        <span className="commit-count">
          {stagedFiles.length} staged
        </span>
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          onCommit();
        }}
      >
        <label
          className="commit-message-label"
          htmlFor="commit-message"
        >
          Commit message
        </label>

        <textarea
          id="commit-message"
          className="commit-message-input"
          placeholder="Describe the changes you're committing..."
          value={commitMessage}
          onChange={(event) =>
            setCommitMessage(event.target.value)
          }
          maxLength={200}
          rows={3}
          disabled={committing}
        />

        <div className="commit-form-footer">
          <span>
            {commitMessage.length}/200 characters
          </span>

          <button
            type="submit"
            className="button-primary"
            disabled={!canCommit}
          >
            {committing
              ? "Committing..."
              : "✓ Commit Changes"}
          </button>
        </div>
      </form>

      <div className="commit-undo-actions">
        <button
          type="button"
          className="button-secondary"
          onClick={onUnstageAll}
          disabled={
            !onUnstageAll ||
            stagedFiles.length === 0 ||
            committing ||
            Boolean(unstagingPath)
          }
          title="Take every file out of the staging area. Your files are not changed."
        >
          {unstagingPath === "*" ? "Unstaging..." : "Unstage all"}
        </button>

        <button
          type="button"
          className="button-secondary"
          onClick={onUndoCommit}
          disabled={
            !onUndoCommit ||
            !canUndo ||
            stagedFiles.length > 0 ||
            undoing ||
            committing
          }
          title="Move the branch back one commit and keep its changes staged."
        >
          {undoing ? "Undoing..." : "↶ Undo last commit"}
        </button>
      </div>

      {canUndo && stagedFiles.length > 0 && (
        <p className="commit-help-text">
          Unstage or commit your staged files before undoing a
          commit.
        </p>
      )}

      {stagedFiles.length > 0 && (
        <div className="commit-staged-list">
          <strong>Files in the staging index</strong>

          {stagedFiles.map((file) => (
            <div
              className="commit-staged-file"
              key={file.path}
            >
              <span>▤</span>
              <span>{file.path}</span>

              <span className="status-staged">
                STAGED
              </span>

              {onUnstageFile && (
                <button
                  type="button"
                  className="button-secondary"
                  disabled={
                    Boolean(unstagingPath) || committing
                  }
                  onClick={() => onUnstageFile(file.path)}
                >
                  {unstagingPath === file.path
                    ? "Unstaging..."
                    : "Unstage"}
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {stagedFiles.length === 0 && (
        <p className="commit-help-text">
          Stage at least one file before creating a commit.
        </p>
      )}

      {commitError && (
        <div
          className="commit-feedback commit-feedback-error"
          role="alert"
        >
          {commitError}
        </div>
      )}

      {commitResult && (
        <div
          className="commit-feedback commit-feedback-success"
          role="status"
        >
          <strong>✓ Commit created successfully</strong>

          <p>{commitResult.commitMessage}</p>

          <div>
            <span>Branch:</span> {commitResult.branch}
          </div>

          <div>
            <span>Commit hash:</span>{" "}
            <code>{commitResult.commitHash}</code>
          </div>
        </div>
      )}
    </section>
  );
}

/* -------------------------------------------------------
   SOURCE CONTROL
------------------------------------------------------- */

function SourceControlPreview({
  status,
  statusLoading,
  statusError,
  onRefresh,
  onNavigate,
  onStageFile,
  stagingPath,
  stageError,
  stageMessage,
}) {
  const files = status?.files || [];

  const countStatus = (name) =>
    files.filter((file) => file.status === name).length;

  const modified = countStatus("MODIFIED");
  const staged = countStatus("STAGED");
  const untracked = countStatus("UNTRACKED");
  const deleted = files.filter(
    (file) =>
      file.changeType === "DELETED" &&
      file.status !== "STAGED"
  ).length;
  const renamed = files.filter(
    (file) => file.changeType === "RENAMED"
  ).length;

  return (
    <section className="panel source-preview">
      <div className="panel-header">
        <div>
          <h3>Source Control</h3>
          <p>Actual file status from your Java backend.</p>
        </div>

        <span className="changes-badge">
          {statusLoading
            ? "Loading..."
            : `${files.length} changes`}
        </span>
      </div>

      {statusError ? (
        <div className="source-empty-state">
          <p>Unable to load file status.</p>
          <p>{statusError}</p>

          <button
            className="button-secondary"
            onClick={onRefresh}
          >
            Retry
          </button>
        </div>
      ) : (
        <>
          <div className="file-status-summary">
            <div>
              <span className="file-status-dot modified-dot" />
              Modified <strong>{modified}</strong>
            </div>

            <div>
              <span className="file-status-dot staged-dot" />
              Staged <strong>{staged}</strong>
            </div>

            <div>
              <span className="file-status-dot untracked-dot" />
              Untracked <strong>{untracked}</strong>
            </div>
            <div>
              <span className="file-status-dot deleted-dot" />
              Deleted <strong>{deleted}</strong>
            </div>
            <div>
              <span className="file-status-dot renamed-dot" />
              Renamed <strong>{renamed}</strong>
            </div>
          </div>

          {stageMessage && (
            <div className="source-empty-state">
              <p>{stageMessage}</p>
            </div>
          )}

          {stageError && (
            <div className="source-empty-state">
              <p>Unable to stage file.</p>
              <p>{stageError}</p>
            </div>
          )}

          {statusLoading ? (
            <div className="source-empty-state">
              <p>Checking repository files...</p>
            </div>
          ) : files.length === 0 ? (
            <div className="source-empty-state">
              <div className="empty-state-icon">✓</div>

              <h4>Working tree looks clean</h4>

              <p>
                No file changes were reported by the backend.
              </p>
            </div>
          ) : (
            <div className="file-list">
              {files.slice(0, 8).map((file) => {
                const renameSource = file.renameFrom
                  ? files.find(
                      (entry) => entry.path === file.renameFrom
                    )
                  : null;
                const renameNeedsStaging =
                  file.changeType === "RENAMED" &&
                  (file.status !== "STAGED" ||
                    renameSource?.status !== "STAGED");
                const canStage =
                  file.status === "UNTRACKED" ||
                  file.status === "MODIFIED" ||
                  file.status === "DELETED" ||
                  file.status === "MISSING" ||
                  (file.changeType === "RENAMED" &&
                    renameNeedsStaging &&
                    (file.status === "RENAMED" ||
                      file.status === "STAGED"));

                const isStaging =
                  stagingPath === file.path;

                return (
                  <div className="file-row" key={file.path}>
                    <span
                      className={`file-icon ${getFileType(
                        file.path
                      )}`}
                    >
                      {getFileType(file.path) === "java"
                        ? "J"
                        : "F"}
                    </span>

                    <div className="file-info">
                      <strong>
                        {file.path.split("/").pop()}
                      </strong>

                      <span>
                        {file.renameFrom
                          ? `${file.renameFrom} → ${file.path}`
                          : file.path.includes("/")
                          ? file.path.substring(
                              0,
                              file.path.lastIndexOf("/")
                            )
                          : "Repository root"}
                      </span>
                    </div>

                    <span
                      className={`file-status status-${getFileStatusClass(
                        file.status
                      )}`}
                    >
                      {file.status}
                    </span>

                    {canStage && (
                      <button
                        className="button-secondary"
                        disabled={
                          Boolean(stagingPath) ||
                          statusLoading
                        }
                        onClick={() =>
                          onStageFile(file.path, file.renameFrom)
                        }
                      >
                        {isStaging
                          ? "Staging..."
                          : file.changeType === "RENAMED"
                            ? "Stage rename"
                            : "Stage"}
                      </button>
                    )}

                    {file.status === "STAGED" &&
                      !renameNeedsStaging && (
                      <span className="file-status status-staged">
                        ✓ Ready
                      </span>
                    )}
                  </div>
                );
              })}

              {files.length > 8 && (
                <p className="source-more-files">
                  And {files.length - 8} more files...
                </p>
              )}
            </div>
          )}
        </>
      )}

      <button className="view-all-button" onClick={onNavigate}>
        Review overview <span>→</span>
      </button>
    </section>
  );
}

/* -------------------------------------------------------
   QUICK ACTIONS
------------------------------------------------------- */

function QuickActions({ onNavigate }) {
  const actions = [
    {
      icon: "＋",
      title: "Review file changes",
      subtitle: "Inspect the working tree",
      color: "purple",
      page: "Source Control",
    },
    {
      icon: "⑂",
      title: "Manage branches",
      subtitle: "View the current branch",
      color: "blue",
      page: "Branches",
    },
    {
      icon: "⬡",
      title: "Verify integrity",
      subtitle: "Explore SHA-256 verification",
      color: "green",
      page: "Integrity Checker",
    },
  ];

  return (
    <section className="panel quick-actions-panel">
      <div className="panel-header">
        <div>
          <h3>Quick actions</h3>
          <p>Jump into your workflow.</p>
        </div>
      </div>

      {actions.map((action) => (
        <button
          className="quick-action"
          key={action.title}
          onClick={() => onNavigate(action.page)}
        >
          <span
            className={`quick-action-icon ${action.color}`}
          >
            {action.icon}
          </span>

          <span className="quick-action-text">
            <strong>{action.title}</strong>
            <span>{action.subtitle}</span>
          </span>

          <span className="quick-action-arrow">↗</span>
        </button>
      ))}
    </section>
  );
}

/* -------------------------------------------------------
   FILE EXPLORER PAGE
------------------------------------------------------- */

function FileExplorerPage({
  files,
  filesLoading,
  filesError,
  onReloadFiles,
  selectedFile,
  fileContent,
  setFileContent,
  savedContent,
  fileLoading,
  fileSaving,
  fileError,
  fileMessage,
  onSelectFile,
  onSaveFile,
}) {
  const hasUnsavedChanges =
    Boolean(selectedFile) && fileContent !== savedContent;

  function handleKeyDown(event) {
    if (
      (event.metaKey || event.ctrlKey) &&
      event.key.toLowerCase() === "s"
    ) {
      event.preventDefault();
      onSaveFile();
    }
  }

  return (
    <div className="file-explorer">
      <section className="panel file-explorer-tree">
        <div className="panel-header">
          <div>
            <h3>Files</h3>
            <p>Working directory contents.</p>
          </div>

          <span className="commit-count">
            {filesLoading ? "Loading..." : `${files.length} files`}
          </span>
        </div>

        {filesError ? (
          <div className="source-empty-state">
            <p>Unable to load files.</p>
            <p>{filesError}</p>

            <button
              className="button-secondary"
              onClick={onReloadFiles}
            >
              Retry
            </button>
          </div>
        ) : filesLoading && files.length === 0 ? (
          <div className="source-empty-state">
            <p>Loading repository files...</p>
          </div>
        ) : files.length === 0 ? (
          <div className="source-empty-state">
            <p>No files in the working directory.</p>
          </div>
        ) : (
          <div className="file-explorer-list">
            {files.map((file, index) => {
              const path = getFilePath(file);
              if (!path || path === "Unknown file") return null;

              return (
                <button
                  type="button"
                  key={`${path}-${index}`}
                  className={`file-explorer-item ${
                    selectedFile === path ? "active" : ""
                  }`}
                  onClick={() => onSelectFile(path)}
                  title={path}
                >
                  <span
                    className={`file-icon ${getFileType(path)}`}
                    aria-hidden="true"
                  >
                    <Icon name="files" />
                  </span>

                  <span className="file-explorer-item-text">
                    <strong>{path.split(/[\\/]/).pop()}</strong>
                    <span>
                      {path.includes("/")
                        ? path.substring(0, path.lastIndexOf("/"))
                        : path.includes("\\")
                          ? path.substring(0, path.lastIndexOf("\\"))
                          : "Repository root"}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </section>

      <section className="panel file-explorer-editor">
        <div className="panel-header">
          <div>
            <h3>{selectedFile || "No file selected"}</h3>
            <p>
              {selectedFile
                ? hasUnsavedChanges
                  ? "Unsaved changes"
                  : "All changes saved"
                : "Choose a file from the list to view or edit it."}
            </p>
          </div>

          <button
            type="button"
            className="button-primary"
            onClick={onSaveFile}
            disabled={
              !selectedFile ||
              !hasUnsavedChanges ||
              fileSaving ||
              fileLoading
            }
          >
            {fileSaving ? "Saving..." : "Save"}
          </button>
        </div>

        {fileError && (
          <div
            className="commit-feedback commit-feedback-error"
            role="alert"
          >
            {fileError}
          </div>
        )}

        {fileMessage && (
          <div
            className="commit-feedback commit-feedback-success"
            role="status"
          >
            {fileMessage}
          </div>
        )}

        {!selectedFile ? (
          <div className="source-empty-state">
            <div className="empty-state-icon">▤</div>
            <p>Select a file to open it in the editor.</p>
          </div>
        ) : fileLoading ? (
          <div className="source-empty-state">
            <p>Loading file...</p>
          </div>
        ) : (
          <textarea
            className="file-explorer-textarea"
            value={fileContent}
            onChange={(event) =>
              setFileContent(event.target.value)
            }
            onKeyDown={handleKeyDown}
            spellCheck={false}
            disabled={fileSaving}
            aria-label={`Contents of ${selectedFile}`}
          />
        )}
      </section>
    </div>
  );
}

/* -------------------------------------------------------
   OTHER FEATURE PAGES
------------------------------------------------------- */

function FeaturePage({ onNavigate }) {
  return (
    <div className="panel feature-unavailable">
      <h3>Page unavailable</h3>
      <p>This workspace page is not connected to a MiniGit operation.</p>
      <button
        type="button"
        className="button-secondary"
        onClick={() => onNavigate("Overview")}
      >
        Return to Overview
      </button>
    </div>
  );
}

function IntegrityCheckerPage() {
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function runCheck() {
    setLoading(true);
    setError("");
    setReport(null);

    try {
      const result = await checkRepositoryIntegrity();
      if (!result || result.success !== true) {
        throw new Error(
          result?.message || "The integrity check could not be completed."
        );
      }
      setReport(result);
    } catch (requestError) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : "Unable to run the integrity check."
      );
    } finally {
      setLoading(false);
    }
  }

  const summaryCards = report
    ? [
        ["Objects checked", report.totalObjectsChecked ?? 0],
        ["Valid", report.validObjectCount ?? 0],
        ["Corrupted", report.corruptedObjectCount ?? 0],
        ["Missing", report.missingObjectCount ?? 0],
        ["Unreadable", report.unreadableObjectCount ?? 0],
      ]
    : [];
  const failures = Array.isArray(report?.results)
    ? report.results
    : [];
  const hasIssues = Boolean(
    report &&
      ((report.corruptedObjectCount ?? 0) > 0 ||
        (report.missingObjectCount ?? 0) > 0 ||
        (report.unreadableObjectCount ?? 0) > 0)
  );
  const empty = report && report.totalObjectsChecked === 0;

  return (
    <section className="integrity-page">
      <div className="panel integrity-intro">
        <div>
          <h3>Verify repository objects</h3>
          <p>
            Check stored object hashes, commit metadata, and referenced
            objects. This scan is read-only.
          </p>
        </div>
        <button
          type="button"
          className="button-primary"
          onClick={runCheck}
          disabled={loading}
        >
          {loading ? "Checking objects..." : "Run Integrity Check"}
        </button>
      </div>

      {loading && (
        <div className="panel integrity-state" role="status">
          Verifying stored objects and commit references...
        </div>
      )}

      {error && (
        <div className="commit-feedback commit-feedback-error" role="alert">
          <p>Integrity check request failed.</p>
          <p>{error}</p>
          <button
            type="button"
            className="button-secondary"
            onClick={runCheck}
            disabled={loading}
          >
            Retry
          </button>
        </div>
      )}

      {report && !loading && (
        <>
          {empty ? (
            <div className="panel integrity-state">
              <h3>No objects to check</h3>
              <p>
                This repository contains no stored objects yet. The check
                completed successfully.
              </p>
            </div>
          ) : (
            <>
              <div
                className={`integrity-status ${
                  hasIssues ? "integrity-status-warning" : "integrity-status-good"
                }`}
                role={hasIssues ? "alert" : "status"}
              >
                <strong>
                  {hasIssues
                    ? "Integrity issues found"
                    : "All checked objects are valid"}
                </strong>
                <span>
                  {hasIssues
                    ? "Review the details below before relying on this repository."
                    : `${report.totalObjectsChecked} object checks completed.`}
                </span>
              </div>

              <div className="integrity-summary-grid">
                {summaryCards.map(([label, value]) => (
                  <article className="panel integrity-summary-card" key={label}>
                    <span>{label}</span>
                    <strong>{value}</strong>
                  </article>
                ))}
              </div>

              {failures.length > 0 && (
                <section className="panel integrity-results">
                  <div className="panel-header">
                    <div>
                      <h3>Failed checks</h3>
                      <p>
                        Objects that could not be verified or referenced
                        correctly.
                      </p>
                    </div>
                    <span className="commit-count">
                      {failures.length} issues
                    </span>
                  </div>
                  <div className="integrity-results-list">
                    {failures.map((failure, index) => (
                      <article
                        className="integrity-result-row"
                        key={`${failure.objectHash}-${failure.status}-${index}`}
                      >
                        <code title={failure.objectHash}>
                          {failure.objectHash || "Repository metadata"}
                        </code>
                        <span
                          className={`file-status status-${String(
                            failure.status || "unknown"
                          ).toLowerCase()}`}
                        >
                          {failure.status || "FAILED"}
                        </span>
                        <p>{failure.reason || "No reason was provided."}</p>
                      </article>
                    ))}
                  </div>
                </section>
              )}
            </>
          )}
        </>
      )}

      {!report && !loading && !error && (
        <div className="panel integrity-state">
          <h3>Ready to verify</h3>
          <p>
            Run a check to validate the content-addressed objects and commit
            references in this repository.
          </p>
        </div>
      )}
    </section>
  );
}

/* -------------------------------------------------------
   OVERVIEW PAGE
------------------------------------------------------- */

function OverviewPage({
  repository,
  status,
  statusLoading,
  statusError,
  commits,
  historyLoading,
  historyError,
  onRefresh,
  onNavigate,
  onSelectCommit,
  onStageFile,
  stagingPath,
  stageError,
  stageMessage,
  refreshKey,
  onBranchChanged,
}) {
  const files = status?.files || [];

  const stagedCount = files.filter(
    (file) => file.status === "STAGED"
  ).length;

  const modifiedCount = files.filter(
    (file) => file.status === "MODIFIED"
  ).length;

  const untrackedCount = files.filter(
    (file) => file.status === "UNTRACKED"
  ).length;
  const deletedCount = files.filter(
    (file) => file.changeType === "DELETED"
  ).length;
  const renamedCount = files.filter(
    (file) => file.changeType === "RENAMED"
  ).length;

  const clean = status?.clean;

  return (
    <>
      <div className="stats-grid">
        <StatCard
          label="Total commits"
          value={
            historyLoading
              ? "…"
              : historyError
                ? "—"
                : String(commits.length)
          }
          detail={
            historyError
              ? "Unable to load commit history"
              : "Loaded from the Java history API"
          }
          icon="history"
          accent="purple"
          badge={historyError ? "Error" : "Live"}
        />

        <StatCard
          label="Current branch"
          value={
            status?.currentBranch ||
            repository?.currentBranch ||
            "—"
          }
          detail="Loaded from repository metadata"
          icon="branches"
          accent="blue"
          badge="Live"
        />

        <StatCard
          label="Changed files"
          value={statusLoading ? "…" : String(files.length)}
          detail={`${stagedCount} staged · ${modifiedCount} modified · ${deletedCount} deleted · ${renamedCount} renamed · ${untrackedCount} untracked`}
          icon="files"
          accent="green"
          badge="Live"
        />

        <StatCard
          label="Working tree"
          value={
            statusLoading
              ? "…"
              : statusError
                ? "Error"
                : clean
                  ? "Clean"
                  : "Changes"
          }
          detail={
            statusError
              ? "Could not read file status"
              : clean
                ? "No changes detected"
                : "Review pending changes"
          }
          icon="integrity"
          accent="orange"
          badge={statusError ? "Error" : "Live"}
        />
      </div>

      <div className="main-grid">
        <RepositoryCard
          repository={repository}
          status={status}
        />

        <BranchCard
          refreshKey={refreshKey}
          onNavigate={onNavigate}
          onBranchChanged={onBranchChanged}
        />
      </div>

      <div className="lower-grid">
        <div className="overview-commit-column">
          <CommitHistory
            commits={commits}
            compact
            loading={historyLoading}
            error={historyError}
            onRefresh={onRefresh}
            onSelectCommit={onSelectCommit}
          />

          <button
            className="view-all-button"
            onClick={() => onNavigate("Commit History")}
          >
            View full commit history <span>→</span>
          </button>
        </div>

        <div className="right-column">
          <SourceControlPreview
            status={status}
            statusLoading={statusLoading}
            statusError={statusError}
            onRefresh={onRefresh}
            onNavigate={() => onNavigate("Source Control")}
            onStageFile={onStageFile}
            stagingPath={stagingPath}
            stageError={stageError}
            stageMessage={stageMessage}
          />

          <QuickActions onNavigate={onNavigate} />
        </div>
      </div>
    </>
  );
}

/* -------------------------------------------------------
   MAIN APPLICATION
------------------------------------------------------- */

function App() {
  const [activePage, setActivePage] = useState("Overview");

  const [searchQuery, setSearchQuery] = useState("");

  const [repository, setRepository] = useState(null);
  const [repositoryLoading, setRepositoryLoading] =
    useState(true);
  const [repositoryError, setRepositoryError] = useState("");

  const [status, setStatus] = useState(null);
  const [statusLoading, setStatusLoading] = useState(true);
  const [statusError, setStatusError] = useState("");

  const [stagingPath, setStagingPath] = useState(null);
  const [stageError, setStageError] = useState("");
  const [stageMessage, setStageMessage] = useState("");

  const [unstagingPath, setUnstagingPath] = useState(null);
  const [undoing, setUndoing] = useState(false);

  const [commitMessage, setCommitMessage] = useState("");
  const [committing, setCommitting] = useState(false);
  const [commitError, setCommitError] = useState("");
  const [commitResult, setCommitResult] = useState(null);

  const [commits, setCommits] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState("");

  const [selectedCommit, setSelectedCommit] = useState(null);

  const [refreshKey, setRefreshKey] = useState(0);
  // File Explorer state
const [repositoryFiles, setRepositoryFiles] = useState([]);
const [filesLoading, setFilesLoading] = useState(false);
const [filesError, setFilesError] = useState("");

const [selectedFile, setSelectedFile] = useState("");
const [fileContent, setFileContent] = useState("");
const [savedContent, setSavedContent] = useState("");

const [fileLoading, setFileLoading] = useState(false);
const [fileSaving, setFileSaving] = useState(false);
const [fileError, setFileError] = useState("");
const [fileMessage, setFileMessage] = useState("");

  /* -----------------------------------------------------
     STAGE A FILE
  ----------------------------------------------------- */

  async function handleStageFile(path, renameFrom = "") {
    setStagingPath(path);
    setStageError("");
    setStageMessage("");
    let renameSourceStaged = false;

    try {
      if (renameFrom) {
        await stageFile(renameFrom);
        renameSourceStaged = true;
      }

      const result = await stageFile(path);

      setStageMessage(
        renameFrom
          ? `Rename staged: ${renameFrom} → ${path}.`
          : result.message || `${path} staged successfully.`
      );

      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setStageError(
        `${renameSourceStaged
          ? `The deletion of ${renameFrom} was staged, but `
          : ""}${
          error instanceof Error
            ? error.message
            : "Failed to stage file."
        }`
      );
      if (renameSourceStaged) {
        setRefreshKey((previous) => previous + 1);
      }
    } finally {
      setStagingPath(null);
    }
  }

  /* -----------------------------------------------------
     UNSTAGE / UNDO (Module 10)
  ----------------------------------------------------- */

  async function handleUnstageFile(path) {
    setUnstagingPath(path);
    setStageError("");
    setStageMessage("");

    try {
      const result = await unstageFile(path);

      setStageMessage(
        result.message || `${path} unstaged.`
      );

      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setStageError(
        errorText(error, "Failed to unstage file.")
      );
    } finally {
      setUnstagingPath(null);
    }
  }

  async function handleUnstageAll() {
    setUnstagingPath("*");
    setStageError("");
    setStageMessage("");

    try {
      const result = await unstageAll();

      setStageMessage(
        result.message || "All files unstaged."
      );

      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setStageError(
        errorText(error, "Failed to unstage files.")
      );
    } finally {
      setUnstagingPath(null);
    }
  }

  async function handleUndoCommit() {
    const confirmed = window.confirm(
      "Undo the last commit?\n\nIts changes return to the " +
        "staging area and your files are not modified. The " +
        "commit can be recovered from the Branches page."
    );

    if (!confirmed) return;

    setUndoing(true);
    setStageError("");
    setStageMessage("");
    setCommitError("");
    setCommitResult(null);

    try {
      const result = await undoLastCommit();

      setStageMessage(result.message || "Last commit undone.");
      setSelectedCommit(null);

      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setStageError(
        errorText(error, "Failed to undo the last commit.")
      );
    } finally {
      setUndoing(false);
    }
  }

  /* -----------------------------------------------------
     BRANCH CHANGES (Module 9)
  ----------------------------------------------------- */

  function handleBranchChanged({ switched = false } = {}) {
    setStageError("");
    setStageMessage("");
    setCommitError("");
    setCommitResult(null);
    setSelectedCommit(null);

    // Switching branches rewrites files on disk. Drop the open file
    // unless it has unsaved edits that the user may still want.
    if (switched && fileContent === savedContent) {
      setSelectedFile("");
      setFileContent("");
      setSavedContent("");
      setFileError("");
      setFileMessage("");
    }

    setRefreshKey((previous) => previous + 1);
  }

  /* -----------------------------------------------------
     FILE EXPLORER
  ----------------------------------------------------- */

  async function loadRepositoryFiles() {
    setFilesLoading(true);
    setFilesError("");

    try {
      const result = await getRepositoryFiles();

      const files = Array.isArray(result)
        ? result
        : result.files || [];

      setRepositoryFiles(files);
    } catch (error) {
      setFilesError(
        error instanceof Error
          ? error.message
          : "Unable to load repository files."
      );
    } finally {
      setFilesLoading(false);
    }
  }

  async function handleSelectFile(path) {
    if (fileContent !== savedContent) {
      const discard = window.confirm(
        "You have unsaved changes. Discard them and open another file?"
      );

      if (!discard) return;
    }

    setSelectedFile(path);
    setFileContent("");
    setSavedContent("");
    setFileError("");
    setFileMessage("");
    setFileLoading(true);

    try {
      const result = await getFileContent(path);
      const content = result.content ?? "";

      setFileContent(content);
      setSavedContent(content);
    } catch (error) {
      setFileError(
        error instanceof Error
          ? error.message
          : "Unable to read the selected file."
      );
    } finally {
      setFileLoading(false);
    }
  }

  async function handleSaveFile() {
    if (!selectedFile || fileSaving) return;

    setFileSaving(true);
    setFileError("");
    setFileMessage("");

    try {
      const result = await saveFileContent(
        selectedFile,
        fileContent
      );

      setSavedContent(fileContent);

      setFileMessage(
        result.message || "File saved successfully."
      );

      // Refresh repository status and related information.
      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setFileError(
        error instanceof Error
          ? error.message
          : "Unable to save the file."
      );
    } finally {
      setFileSaving(false);
    }
  }

  /* -----------------------------------------------------
     CREATE A COMMIT
  ----------------------------------------------------- */

  async function handleCommit() {
    if (!commitMessage.trim()) {
      setCommitError("Enter a commit message.");
      return;
    }

    setCommitting(true);
    setCommitError("");
    setCommitResult(null);

    try {
      const result = await commitChanges(
        commitMessage.trim()
      );

      setCommitResult(result);
      setCommitMessage("");

      setRefreshKey((previous) => previous + 1);
    } catch (error) {
      setCommitError(
        error instanceof Error
          ? error.message
          : "Failed to create commit."
      );
    } finally {
      setCommitting(false);
    }
  }

  /* -----------------------------------------------------
     LOAD REPOSITORY
  ----------------------------------------------------- */

  useEffect(() => {
    let cancelled = false;

    async function loadRepository() {
      setRepositoryLoading(true);
      setRepositoryError("");

      try {
        const data = await getRepository();

        if (!cancelled) {
          setRepository(data);
        }
      } catch (error) {
        if (!cancelled) {
          setRepositoryError(
            error instanceof Error
              ? error.message
              : "Unable to connect to the MiniGit backend."
          );
        }
      } finally {
        if (!cancelled) {
          setRepositoryLoading(false);
        }
      }
    }

    loadRepository();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  /* -----------------------------------------------------
     LOAD FILE STATUS
  ----------------------------------------------------- */

  useEffect(() => {
    let cancelled = false;

    async function loadStatus() {
      setStatusLoading(true);
      setStatusError("");

      try {
        const data = await getStatus();

        if (!cancelled) {
          setStatus(data);
        }
      } catch (error) {
        if (!cancelled) {
          setStatusError(
            error instanceof Error
              ? error.message
              : "Unable to load repository file status."
          );
        }
      } finally {
        if (!cancelled) {
          setStatusLoading(false);
        }
      }
    }

    loadStatus();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  /* -----------------------------------------------------
   LIVE FILE-CHANGE DETECTION
----------------------------------------------------- */

useEffect(() => {
  const intervalId = window.setInterval(async () => {
    try {
      const data = await getStatus();
      setStatus(data);
      setStatusError("");
    } catch (error) {
      setStatusError(
        error instanceof Error
          ? error.message
          : "Unable to refresh repository status."
      );
    }
  }, 3000);

  return () => {
    window.clearInterval(intervalId);
  };
}, []);
  /* -----------------------------------------------------
     LOAD COMMIT HISTORY
  ----------------------------------------------------- */

  useEffect(() => {
    let cancelled = false;

    async function loadCommitHistory() {
      setHistoryLoading(true);
      setHistoryError("");

      try {
        const data = await getCommitHistory();

        if (cancelled) return;

        /*
         * Support common API response shapes:
         *
         * { commits: [...] }
         * { history: [...] }
         * { data: [...] }
         * [...]
         */

        let commitList = [];

        if (Array.isArray(data)) {
          commitList = data;
        } else if (Array.isArray(data?.commits)) {
          commitList = data.commits;
        } else if (Array.isArray(data?.history)) {
          commitList = data.history;
        } else if (Array.isArray(data?.data)) {
          commitList = data.data;
        }

        setCommits(commitList);
      } catch (error) {
        if (!cancelled) {
          setHistoryError(
            error instanceof Error
              ? error.message
              : "Unable to load commit history."
          );
        }
      } finally {
        if (!cancelled) {
          setHistoryLoading(false);
        }
      }
    }

    loadCommitHistory();

    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  /* -----------------------------------------------------
     REFRESH
  ----------------------------------------------------- */

  function refreshRepository() {
    setStageError("");
    setStageMessage("");
    setCommitError("");

    setRefreshKey((key) => key + 1);

    if (activePage === "File Explorer") {
      loadRepositoryFiles();
    }
  }

  /* -----------------------------------------------------
     NAVIGATION
  ----------------------------------------------------- */

  function navigateTo(page) {
    setActivePage(page);
    setSearchQuery("");
    setSelectedCommit(null);

    if (page === "File Explorer") {
      loadRepositoryFiles();
    }
  }

  function handleNewCommit() {
    setActivePage("Source Control");
    setCommitError("");
    setSelectedCommit(null);
  }

  function handleSelectCommit(commit) {
    setSelectedCommit(commit);
    setActivePage("Commit History");
  }

  function handleBackToHistory() {
    setSelectedCommit(null);
    setActivePage("Commit History");
  }
  useEffect(() => {
  if (activePage !== "File Explorer") return;

  let cancelled = false;

  async function loadFiles() {
    setFilesLoading(true);
    setFilesError("");

    try {
      const result = await getRepositoryFiles();

      if (!cancelled) {
        setRepositoryFiles(
          Array.isArray(result)
            ? result
            : result.files || []
        );
      }
    } catch (error) {
      if (!cancelled) {
        setFilesError(
          error instanceof Error
            ? error.message
            : "Unable to load repository files."
        );
      }
    } finally {
      if (!cancelled) {
        setFilesLoading(false);
      }
    }
  }

  loadFiles();

  return () => {
    cancelled = true;
  };
}, [activePage, refreshKey]);

  /* -----------------------------------------------------
     SEARCH
  ----------------------------------------------------- */

  const filteredCommits = commits.filter((commit) => {
    const searchable = [
      getCommitMessage(commit),
      getCommitHash(commit),
      getCommitAuthor(commit),
      getCommitBranch(commit),
    ]
      .join(" ")
      .toLowerCase();

    return searchable.includes(searchQuery.toLowerCase());
  });

  const backendConnected =
    Boolean(repository && !repositoryError);

  /* -----------------------------------------------------
     RENDER
  ----------------------------------------------------- */

  return (
    <div className="app-shell">
      <Sidebar
        activePage={activePage}
        setActivePage={navigateTo}
        repository={repository}
        status={status}
      />

      <main className="main-content">
        <Topbar
          activePage={
            selectedCommit && activePage === "Commit History"
              ? "Commit Details"
              : activePage
          }
          repository={repository}
          status={status}
          backendConnected={backendConnected}
          onRefresh={refreshRepository}
          refreshing={
            repositoryLoading ||
            statusLoading ||
            historyLoading
          }
          onNavigate={navigateTo}
        />

        <div className="page-container">
          <PageHeading
            activePage={
              selectedCommit && activePage === "Commit History"
                ? "Commit Details"
                : activePage
            }
            onNewCommit={handleNewCommit}
          />

          {activePage === "Commit History" && !selectedCommit ? (
            <div className="search-bar">
              <Icon name="search" />

              <input
                type="search"
                placeholder="Search messages, authors, hashes..."
                value={searchQuery}
                aria-label="Filter commit history"
                onChange={(event) =>
                  setSearchQuery(event.target.value)
                }
              />

              <span className="search-result-count">
                {filteredCommits.length} shown
              </span>
            </div>
          ) : null}

          {repositoryLoading ? (
            <div
              className="panel"
              style={{
                padding: "24px",
                marginBottom: "24px",
              }}
            >
              <h3>Connecting to MiniGit...</h3>

              <p>
                Fetching repository information from the
                Java backend.
              </p>
            </div>
          ) : repositoryError ? (
            <div
              className="panel"
              style={{
                padding: "24px",
                marginBottom: "24px",
              }}
            >
              <h3>Backend connection failed</h3>

              <p>{repositoryError}</p>

              <p>
                Confirm that Tomcat is running and that the
                MiniGit WAR has been deployed.
              </p>

              <button
                className="button-secondary"
                onClick={refreshRepository}
              >
                ↻ Retry connection
              </button>
            </div>
          ) : !repository ? (
            <div
              className="panel"
              style={{
                padding: "24px",
                marginBottom: "24px",
              }}
            >
              <h3>No repository information</h3>

              <p>
                The backend returned no repository data.
              </p>
            </div>
          ) : !repository.initialized &&
            activePage !== "Integrity Checker" ? (
            <div
              className="panel"
              style={{
                padding: "24px",
                marginBottom: "24px",
              }}
            >
              <h3>Repository not initialized</h3>

              <p>
                The Java API is responding, but the
                configured MiniGit repository has not
                been initialized.
              </p>

              <p>
                <strong>Repository:</strong>{" "}
                {repository.name}
              </p>

              <p>
                <strong>Path:</strong> {repository.path}
              </p>

              <button
                className="button-secondary"
                onClick={refreshRepository}
              >
                ↻ Check again
              </button>
            </div>
          ) : activePage === "Overview" ? (
            <OverviewPage
              repository={repository}
              status={status}
              statusLoading={statusLoading}
              statusError={statusError}
              commits={commits}
              historyLoading={historyLoading}
              historyError={historyError}
              onRefresh={refreshRepository}
              onNavigate={navigateTo}
              onSelectCommit={handleSelectCommit}
              onStageFile={handleStageFile}
              stagingPath={stagingPath}
              stageError={stageError}
              stageMessage={stageMessage}
              refreshKey={refreshKey}
              onBranchChanged={handleBranchChanged}
            />
          ) : activePage === "Source Control" ? (
            <div className="feature-page">
              <CommitPanel
                status={status}
                onCommit={handleCommit}
                committing={committing}
                commitMessage={commitMessage}
                setCommitMessage={setCommitMessage}
                commitError={commitError}
                commitResult={commitResult}
                onUnstageFile={handleUnstageFile}
                onUnstageAll={handleUnstageAll}
                unstagingPath={unstagingPath}
                onUndoCommit={handleUndoCommit}
                undoing={undoing}
                canUndo={commits.length > 0}
              />

              <SourceControlPreview
                status={status}
                statusLoading={statusLoading}
                statusError={statusError}
                onRefresh={refreshRepository}
                onNavigate={() => navigateTo("Overview")}
                onStageFile={handleStageFile}
                stagingPath={stagingPath}
                stageError={stageError}
                stageMessage={stageMessage}
              />
            </div>
          ) : activePage === "Commit History" ? (
            selectedCommit ? (
              <CommitDetails
                commit={selectedCommit}
                onBack={handleBackToHistory}
              />
            ) : (
              <CommitHistory
                commits={filteredCommits}
                loading={historyLoading}
                error={historyError}
                onRefresh={refreshRepository}
                onSelectCommit={handleSelectCommit}
              />
            )
          ) : activePage === "Branches" ? (
            <BranchesPage
              refreshKey={refreshKey}
              onBranchChanged={handleBranchChanged}
            />
          ) : activePage === "Diff Viewer" ? (
            <DiffViewerPage
              commits={filteredCommits}
              loading={historyLoading}
              error={historyError}
              onRefresh={refreshRepository}
            />
          ) : activePage === "Integrity Checker" ? (
            <IntegrityCheckerPage />
          ) : activePage === "File Explorer" ? (
            <FileExplorerPage
              files={repositoryFiles}
              filesLoading={filesLoading}
              filesError={filesError}
              onReloadFiles={() =>
                setRefreshKey((previous) => previous + 1)
              }
              selectedFile={selectedFile}
              fileContent={fileContent}
              setFileContent={(content) => {
                setFileContent(content);
                setFileMessage("");
                setFileError("");
              }}
              savedContent={savedContent}
              fileLoading={fileLoading}
              fileSaving={fileSaving}
              fileError={fileError}
              fileMessage={fileMessage}
              onSelectFile={handleSelectFile}
              onSaveFile={handleSaveFile}
            />
          ) : (
            <FeaturePage
              activePage={activePage}
              onNavigate={navigateTo}
            />
          )}

          <footer className="app-footer">
            <div className="footer-brand">
              <span className="footer-logo">M</span>

              <span>
                MiniGit{" "}
                <span className="footer-version">
                  v0.1.0
                </span>
              </span>
            </div>

            

            <span className="footer-status">
              <i
                style={{
                  background: backendConnected
                    ? "#59d6a5"
                    : "#e8ae64",
                }}
              />

              {backendConnected
                ? "Java backend connected"
                : "Backend disconnected"}
            </span>
          </footer>
        </div>
      </main>
    </div>
  );
}

export default App;
