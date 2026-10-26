const API_BASE_URL = "http://localhost:8080/mini-git-1.0.0/api";
const inFlightGetRequests = new Map();

/**
 * Shared API request handler.
 */
async function fetchApi(endpoint, options = {}) {
  let response;

  try {
    response = await fetch(`${API_BASE_URL}${endpoint}`, {
      ...options,
    });
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error(
        "Cannot reach the MiniGit API. Check that Tomcat is running, " +
          "the WAR is deployed at the configured URL, and CORS allows " +
          "this frontend origin.",
        { cause: error }
      );
    }

    throw error;
  }

  const contentType = response.headers.get("content-type") || "";

  let result;

  if (contentType.includes("application/json")) {
    result = await response.json();
  } else {
    result = await response.text();
  }

  if (!response.ok) {
    const message =
      typeof result === "object" && result !== null
        ? result.message || `Request failed: HTTP ${response.status}`
        : result || `Request failed: HTTP ${response.status}`;

    throw new Error(message);
  }

  if (
    typeof result === "object" &&
    result !== null &&
    result.success === false
  ) {
    throw new Error(result.message || "Backend operation failed");
  }

  return result;
}

function fetchApiOnce(endpoint) {
  const pending = inFlightGetRequests.get(endpoint);
  if (pending) return pending;

  const request = fetchApi(endpoint).finally(() => {
    if (inFlightGetRequests.get(endpoint) === request) {
      inFlightGetRequests.delete(endpoint);
    }
  });

  inFlightGetRequests.set(endpoint, request);
  return request;
}

/**
 * Create URL-encoded form data.
 */
function formBody(values) {
  const body = new URLSearchParams();

  Object.entries(values).forEach(([key, value]) => {
    body.append(key, String(value));
  });

  return body;
}

/**
 * Repository information.
 */
export async function getRepository() {
  return fetchApi("/repository");
}

/**
 * Working-tree status.
 */
export async function getStatus() {
  return fetchApi("/status");
}

/**
 * Stage a file.
 */
export async function stageFile(path) {
  return fetchApi("/add", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ path }),
  });
}

/**
 * Create a commit.
 */
export async function commitChanges(message) {
  return fetchApi("/commit", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ message }),
  });
}

/**
 * Retrieve commit history.
 */
export async function getCommitHistory() {
  return fetchApiOnce("/history");
}

/**
 * Retrieve details for a specific commit.
 * GET /commit-details?hash=<commitHash>
 */
export async function getCommitDetails(commitHash) {
  const params = new URLSearchParams({
    hash: commitHash,
  });

  return fetchApiOnce(`/commit-details?${params.toString()}`);
}

/**
 * Retrieve a file diff for a specific commit.
 * GET /diff?hash=<commitHash>&path=<filePath>
 */
export async function getCommitDiff(commitHash, path) {
  const params = new URLSearchParams({
    hash: commitHash,
    path,
  });

  return fetchApiOnce(`/diff?${params.toString()}`);
}

/**
 * Retrieve repository file-tree information.
 *
 * GET /files
 */
export async function getRepositoryFiles() {
  return fetchApi("/files");
}

/**
 * Retrieve all branches.
 * GET /branches
 * -> { currentBranch, branches: [{ name, commit, current }] }
 */
export async function getBranches() {
  return fetchApi("/branches");
}

/**
 * Create a branch at the current commit (or at `from`, any commit hash).
 * Set `checkout` to switch to the new branch straight away.
 * POST /branches
 */
export async function createBranch(name, { from = "", checkout = false } = {}) {
  const values = { name, checkout };

  if (from) {
    values.from = from;
  }

  return fetchApi("/branches", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody(values),
  });
}

/**
 * Delete a branch. `force` deletes a branch that has unmerged commits.
 * DELETE /branches?name=<name>&force=<bool>
 */
export async function deleteBranch(name, force = false) {
  const params = new URLSearchParams({
    name,
    force: String(force),
  });

  return fetchApi(`/branches?${params.toString()}`, {
    method: "DELETE",
  });
}

/**
 * Switch to another branch. The backend refuses to switch when local
 * changes would be overwritten (HTTP 409 with an explanatory message).
 * POST /checkout
 */
export async function checkoutBranch(branchName) {
  return fetchApi("/checkout", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ branch: branchName }),
  });
}

/**
 * Remove one file from the staging area (the file itself is untouched).
 * POST /unstage
 */
export async function unstageFile(path) {
  return fetchApi("/unstage", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ path }),
  });
}

/**
 * Empty the staging area.
 * POST /unstage?all=true
 */
export async function unstageAll() {
  return fetchApi("/unstage", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ all: true }),
  });
}

/**
 * Undo the last commit. The changes return to the staging area and the
 * undone commit stays recoverable through the reflog.
 * POST /undo
 */
export async function undoLastCommit() {
  return fetchApi("/undo", {
    method: "POST",
  });
}

/**
 * Recovery log of every branch movement, newest first.
 * GET /reflog?limit=<n>
 */
export async function getReflog(limit = 50) {
  const params = new URLSearchParams({ limit: String(limit) });
  return fetchApi(`/reflog?${params.toString()}`);
}

/**
 * Verify repository object and reference integrity.
 * GET /integrity
 */
export async function checkRepositoryIntegrity() {
  return fetchApi("/integrity");
}
/**
 * Read a file from the MiniGit working directory.
 */
export async function getFileContent(path) {
  const params = new URLSearchParams({ path });
  return fetchApi(`/file?${params.toString()}`);
}

/**
 * Save file contents to the MiniGit working directory.
 */
export async function saveFileContent(path, content) {
  return fetchApi("/file", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: formBody({ path, content }),
  });
}
