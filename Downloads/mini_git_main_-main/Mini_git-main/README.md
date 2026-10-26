# MiniGit --- Web-Based Mini Version Control System

MiniGit is a small version-control-system project with a web interface.
The React frontend communicates with a Java backend deployed on Apache
Tomcat. The application displays repository information and commit
history, and lets users inspect commit metadata and view file diffs.

This README documents the current setup, how to run the application
locally, the API routes used by the frontend, troubleshooting steps, and
a practical next-step checklist.

> **Current implementation note:** The backend endpoints for commit
> details and diffs have been tested. The frontend should call them with
> the commit hash and file path. A commit can contain a file in its
> snapshot without that file having changed in that particular commit,
> so the UI should distinguish **files in a commit snapshot** from
> **files changed by a commit**.

------------------------------------------------------------------------

## Contents

-   [Architecture](#architecture)
-   [Prerequisites](#prerequisites)
-   [Project layout](#project-layout)
-   [1. Start the Java backend](#1-start-the-java-backend)
-   [2. Start the React frontend](#2-start-the-react-frontend)
-   [3. Verify the backend API](#3-verify-the-backend-api)
-   [4. Use the application](#4-use-the-application)
-   [API reference](#api-reference)
-   [Module 9 — Branch management](#module-9--branch-management)
-   [Module 10 — Staging management and undo](#module-10--staging-management-and-undo)
-   [Module 11 — Deletion and rename tracking](#module-11--deletion-and-rename-tracking)
-   [Integrity Checker](#integrity-checker)
-   [Module 12 — Git client interface](#module-12--git-client-interface)
-   [H2 Database and Hibernate](#h2-database-and-hibernate)
-   [Commit details and diff
    behaviour](#commit-details-and-diff-behaviour)
-   [Troubleshooting](#troubleshooting)
-   [Recommended next steps](#recommended-next-steps)
-   [Development checklist](#development-checklist)

------------------------------------------------------------------------

## Architecture

``` text
Browser
  |
  | React UI
  | App.jsx + App.css
  | repositoryService.js
  v
http://localhost:8080/mini-git-1.0.0/api
  |
  | Java REST API
  | Apache Tomcat 11
  v
MiniGit repository / commit objects / file snapshots
```

### Main frontend responsibilities

-   Display repository overview and status.
-   Load and display commit history.
-   Open a selected commit.
-   Load the full details of a commit.
-   List files recorded in a commit snapshot.
-   Request and display a file diff for a selected commit and path.
-   Show loading and error states when API requests are pending or fail.

### Main backend responsibilities

-   Expose repository and status information.
-   Return commit history.
-   Return detailed commit metadata and the file snapshot.
-   Return the diff for a given commit hash and file path.

------------------------------------------------------------------------

## Prerequisites

Install or configure the following before running the project:

-   **Java JDK** compatible with the backend and Tomcat 11.
-   **Apache Tomcat 11**.
-   **Node.js and npm** for the React frontend.
-   A terminal and an editor such as VS Code.
-   The MiniGit project source code.

Check that Java, Node.js, and npm are available:

``` bash
java -version
node -v
npm -v
```

The backend deployment used during testing was:

``` text
Tomcat: 11.0.27
Application context: /mini-git-1.0.0
API base URL: http://localhost:8080/mini-git-1.0.0/api
```

If your WAR file or Tomcat context name differs, update the API base URL
in the frontend service file accordingly.

------------------------------------------------------------------------

## Project layout

The exact directory layout can vary. The frontend files relevant to the
commit-history feature are expected to include:

``` text
frontend/
└── src/
    ├── App.jsx
    ├── App.css
    └── services/
        └── repositoryService.js
```

The backend is a Java web application packaged as a WAR and deployed to
Tomcat. The deployed application context used in the current setup is
`mini-git-1.0.0`.

> If your repository uses different folder names, run `pwd` and
> `find . -maxdepth 3 -type f` from the project root to inspect its
> actual layout. Use the folder containing `package.json` as the
> frontend working directory.

------------------------------------------------------------------------

## 1. Start the Java backend

### Option A --- Run the existing Tomcat deployment

1.  Start Apache Tomcat 11.
2.  Confirm that the WAR is deployed under the context `mini-git-1.0.0`.
3.  Confirm that Tomcat is listening on port `8080`.
4.  Open the API base URL in a browser or use the API checks below.

For a standard local Tomcat installation, the startup script is usually:

``` bash
/path/to/apache-tomcat-11.0.27/bin/startup.sh
```

Stop Tomcat with:

``` bash
/path/to/apache-tomcat-11.0.27/bin/shutdown.sh
```

Replace `/path/to/apache-tomcat-11.0.27` with your actual Tomcat
installation directory.

### Option B --- Deploy the WAR manually

If the backend WAR is already built:

1.  Copy `mini-git-1.0.0.war` into Tomcat's `webapps/` directory.
2.  Start Tomcat.
3.  Wait for Tomcat to deploy the application.
4.  Verify the API routes listed below.

Example:

``` bash
cp /path/to/mini-git-1.0.0.war /path/to/apache-tomcat-11.0.27/webapps/
```

Do not copy the WAR into the frontend directory. It belongs in Tomcat's
`webapps/` directory.

### Confirm that port 8080 is listening

On macOS:

``` bash
lsof -i :8080
```

If the port is already in use, identify the process before stopping
anything. Do not terminate an unfamiliar process blindly.

------------------------------------------------------------------------

## 2. Start the React frontend

1.  Open a terminal.
2.  Change directory to the frontend folder---the folder containing
    `package.json`.
3.  Install dependencies.
4.  Start the development server.

``` bash
cd /path/to/frontend
npm install
npm run dev
```

Open the local URL printed by Vite in the terminal, commonly:

``` text
http://localhost:5173
```

If the project uses Create React App rather than Vite, the start command
may be `npm start` instead. Use the scripts defined in `package.json` as
the source of truth:

``` bash
npm run
```

### Check the API base URL

Open:

``` text
src/services/repositoryService.js
```

The service should point to the deployed backend API, for example:

``` js
const API_BASE_URL = "http://localhost:8080/mini-git-1.0.0/api";
```

Use the exact variable name and structure already present in your
service file; do not add a second base-URL declaration if one already
exists.

The service functions for commit details and diffs should send these
query parameters:

``` js
export async function getCommitDetails(commitHash) {
  const query = new URLSearchParams({ hash: commitHash });
  return fetchApi(`/commit-details?${query.toString()}`);
}

export async function getCommitDiff(commitHash, path) {
  const query = new URLSearchParams({
    hash: commitHash,
    path,
  });
  return fetchApi(`/diff?${query.toString()}`);
}
```

These functions assume the existing `fetchApi()` helper adds the API
base URL and handles HTTP/API errors. Keep one definition of each
exported function; duplicate declarations can cause build errors.

------------------------------------------------------------------------

## 3. Verify the backend API

Run these checks after Tomcat has started.

### Check commit history

``` bash
curl -i "http://localhost:8080/mini-git-1.0.0/api/history"
```

The endpoint should return a successful response containing the
repository's commit history.

### Check details for a commit

Replace `<COMMIT_HASH>` with a real hash returned by `/history`:

``` bash
curl -i "http://localhost:8080/mini-git-1.0.0/api/commit-details?hash=<COMMIT_HASH>"
```

The response is expected to include fields similar to:

``` json
{
  "success": true,
  "hash": "<commit-hash>",
  "parent": "<parent-hash-or-null>",
  "timestamp": "<ISO-8601 timestamp>",
  "message": "Commit message",
  "fileCount": 1,
  "files": {
    "hello.txt": "<blob-hash>"
  },
  "changedFiles": []
}
```

The values above illustrate the response shape; use the actual values
returned by your backend.

### Check a file diff

Replace both placeholders with values from the details/history response:

``` bash
curl -G "http://localhost:8080/mini-git-1.0.0/api/diff" \
  --data-urlencode "hash=<COMMIT_HASH>" \
  --data-urlencode "path=hello.txt"
```

The response may include:

-   `success`: whether the request succeeded.
-   `commitHash`: the requested commit.
-   `path`: the requested file.
-   `status`: for example, `ADDED`, `MODIFIED`, `DELETED`, or
    `UNCHANGED`.
-   `oldContent`: file content before the commit, when available.
-   `newContent`: file content at the selected commit, when available.
-   `lines`: diff rows, commonly labelled `ADDED`, `REMOVED`, or
    `CONTEXT`.

A tested response for one current commit reported `status: "UNCHANGED"`
and identical old/new file contents. That is a valid result: the file
exists in the commit snapshot, but its contents did not change relative
to its parent.

### If the hash contains special characters

Use `curl -G --data-urlencode` as shown above. In the frontend, use
`URLSearchParams` rather than manually concatenating query strings.

------------------------------------------------------------------------

## 4. Use the application

The exact navigation labels depend on the current UI, but the normal
workflow is:

1.  Start the backend.
2.  Start the frontend.
3.  Open the frontend URL in the browser.
4.  Open the repository or overview page.
5.  Load the commit history.
6.  Select a commit to open its details page.
7.  Review the commit message, hash, timestamp, and parent hash.
8.  Inspect the files recorded in the commit snapshot.
9.  Expand a file to request its diff.
10. Review added, removed, and context lines.
11. Use the back control to return to commit history.

### Expected UI states

The interface should handle these cases without breaking the page:

-   History is loading.
-   History request failed.
-   Commit details are loading.
-   Commit details request failed.
-   Diff is loading.
-   Diff request failed, with an option to retry.
-   A commit has no parent (root commit).
-   A commit has no files.
-   A file is unchanged.
-   A file was added, modified, or deleted.
-   A commit has files in its snapshot but no changed files.

------------------------------------------------------------------------

## API reference

Base URL:

``` text
http://localhost:8080/mini-git-1.0.0/api
```

  ---------------------------------------------------------------------------------
  Method                  Route                             Purpose
  ----------------------- --------------------------------- -----------------------
  `GET`                   `/history`                        Retrieve commit history

  `GET`                   `/commit-details?hash=<hash>`     Retrieve details for
                                                            one commit

  `GET`                   `/diff?hash=<hash>&path=<path>`   Retrieve the diff for
                                                            one file in a commit

  `GET`                   `/status`                         List working-tree and
                                                            staged changes

  `POST`                  `/add` (`path`)                   Stage a file or a
                                                            tracked deletion

  `POST`                  `/commit` (`message`)             Commit staged changes

  `GET`                   `/integrity`                      Verify repository
                                                            object integrity
  ---------------------------------------------------------------------------------

`/status` returns `files` with `path`, `status`, `changeType`,
`renameFrom`, `staged`, and `workingChanged`. Common statuses are
`UNTRACKED`, `MODIFIED`, `DELETED`, `RENAMED`, and `STAGED`. `/add` uses
the existing staging index for both file contents and deletion markers.

### Query parameter requirements

-   `hash` must be the commit hash expected by the backend.
-   `path` must be the file path within that commit.
-   URL-encode query parameters. Use `URLSearchParams` in JavaScript and
    `--data-urlencode` with curl.

------------------------------------------------------------------------

## Commit details and diff behaviour

### Commit details

The history endpoint may return only summary information. When a commit
is selected, the details page should call:

``` text
GET /commit-details?hash=<selected-hash>
```

Use the response to populate the message, timestamp, parent hash, file
count, and file snapshot.

### Snapshot files versus changed files

These concepts are different:

-   **Files in snapshot:** files present in the commit's stored
    tree/snapshot.
-   **Changed files:** files whose contents or presence differ from the
    parent commit.

The `files` field in the tested backend response was an object mapping
file paths to blob hashes, for example:

``` json
{
  "hello.txt": "<blob-hash>"
}
```

The frontend must convert that object into a list before rendering it:

``` js
const snapshotFiles = Object.entries(details.files ?? {}).map(
  ([path, blobHash]) => ({
    path,
    hash: blobHash,
  })
);
```

Do not treat the `files` object as an array.

The tested response also contained:

``` json
"changedFiles": []
```

That means the backend did not report any changed files for that commit.
It does **not** mean the snapshot has no files. If the UI uses snapshot
files to let users inspect a diff, label the section **Files in this
commit** rather than claiming every listed file changed.

### Diff loading

When a user expands a file, request:

``` text
GET /diff?hash=<selected-commit-hash>&path=<file-path>
```

Recommended behaviour:

1.  Do not fetch until the user expands the file, unless the design
    intentionally preloads diffs.
2.  Show a loading state while the request is pending.
3.  Render backend diff rows using the existing CSS classes:
    -   `ADDED` → added-line style.
    -   `REMOVED` → removed-line style.
    -   `CONTEXT` → context-line style.
4.  Display the returned status, including `UNCHANGED`.
5.  Show a useful error message if the request fails.
6.  Avoid duplicate requests caused by effect dependencies or repeated
    renders.
7.  Ignore stale responses if the user switches to another commit before
    a request finishes.

------------------------------------------------------------------------

## Module 9 — Branch management

Branches let you keep separate lines of work. A branch is a file in
`.minigit/refs/heads/` containing the hash of its newest commit; `HEAD`
points at the current branch. Commits, history and status already follow
`HEAD`, so they work on whichever branch is checked out.

### What it can do

-   List branches (the current branch is marked, even before its first
    commit).
-   Create a branch at the current commit, or at any commit hash.
-   Create **and** switch in one step.
-   Switch branches (`checkout`) — the working directory is updated to the
    target branch's files.
-   Delete branches.

### Safety rules

Switching branches never silently destroys work. It is refused (HTTP 409 /
CLI error listing the files) when:

-   a file that differs between the two branches has uncommitted edits,
    was deleted locally, or is an untracked file that would be
    overwritten;
-   files are staged that are not part of the current commit.

Everything the target branch needs is read and integrity-checked *before*
any file is written. Local edits to files that are identical on both
branches are kept.

Deleting a branch is refused for the branch you are on, and for branches
with commits that are not reachable from the current branch unless you
force it (`-D` / `force=true`). Forced deletes can still be recovered (see
the reflog below).

Branch names: letters, numbers, `.`, `_`, `-`; start with a letter or
number; up to 100 characters; no `..`, no trailing `.`, `.lock` or `.tmp`.

### CLI

``` bash
mvn exec:java -Dexec.args="branch"                    # list
mvn exec:java -Dexec.args="branch feature"            # create
mvn exec:java -Dexec.args="branch rescue <commit>"    # create at a commit
mvn exec:java -Dexec.args="checkout feature"          # switch
mvn exec:java -Dexec.args="checkout -b feature2"      # create + switch
mvn exec:java -Dexec.args="branch -d feature"         # delete (-D forces)
```

### API

  ----------------------------------------------------------------------------------------
  Method     Route                                    Purpose
  ---------- ---------------------------------------- ------------------------------------
  `GET`      `/branches`                              List branches
  `POST`     `/branches` (`name`, `from`,             Create a branch (optionally from a
             `checkout`)                              commit, optionally switch to it)
  `DELETE`   `/branches?name=...&force=true\|false`   Delete a branch
  `POST`     `/checkout` (`branch`, `create`)         Switch branch
  ----------------------------------------------------------------------------------------

Errors return `{"success":false,"message":"..."}` with `400` (bad input),
`404` (unknown branch) or `409` (conflict). A checkout conflict also
returns `"conflicts": [...]`.

## Module 10 — Staging management and undo

### Unstage

`unstage` takes a file out of the staging area. Only the index changes —
the file in your working directory is never touched.

``` bash
mvn exec:java -Dexec.args="unstage src/App.java"
mvn exec:java -Dexec.args="unstage --all"
```

API: `POST /unstage` with `path=<file>` or `all=true`.

### Undo the last commit

`undo` is a *soft reset*: the branch moves back to the parent commit and
the files that commit changed return to the staging area. Working-directory
files are not modified, so you can fix the commit and commit again. It is
refused while other files are staged, and when there is nothing to undo.
Undoing the root commit leaves an empty branch.

``` bash
mvn exec:java -Dexec.args="undo"
```

API: `POST /undo`.

### Reflog — safe recovery

Every commit, checkout, branch creation, branch deletion and undo is
appended to `.minigit/logs/reflog` with the old and new commit hash. The
commit objects themselves are never deleted, so nothing that was undone or
deleted is really gone:

``` bash
mvn exec:java -Dexec.args="reflog"
mvn exec:java -Dexec.args="branch rescued <hash-from-reflog>"
```

API: `GET /reflog?limit=50`. In the web UI, **Branches → Recent activity**
has a *Recover* button for undone commits and deleted branches.

### Web UI

-   **Branches** page: create (with "switch to it"), switch, delete, and the
    recent-activity list with *Recover*. The Overview branch card lists
    branches and can switch between them.
-   **Source Control** page: *Unstage* next to every staged file,
    *Unstage all*, and *Undo last commit*.

### Tests

``` bash
mvn test
```

New tests: `BranchManagerTest`, `StagingManagerTest`, `UndoManagerTest`,
`ReflogTest`.

------------------------------------------------------------------------

## Module 11 — Deletion and rename tracking

MiniGit reports a tracked file missing from the working directory as
`DELETED`. Deletions remain visible in `/status` and in Source Control;
select **Stage** beside the deleted path to stage its removal. Commit the
staged change as usual. The file is then absent from the committed
snapshot, and the working tree returns to clean when no other changes
remain.

``` bash
del path\to\tracked-file.txt
mvn exec:java -Dexec.args="status"
mvn exec:java -Dexec.args="add path/to/tracked-file.txt"
mvn exec:java -Dexec.args="commit Remove tracked file"
```

The same `/api/add` route stages an existing file or a tracked deletion.
For a missing path that was never tracked, it returns a client error
rather than creating a deletion entry.

### Rename detection

Renames are inferred only when exactly one deleted path and exactly one
new path share the same content hash. If either side has multiple
candidates, MiniGit reports the separate deletion and addition instead
of guessing. Source Control offers **Stage rename** for a detected pair;
the UI stages both the old-path deletion and the new file. The operations
use the existing staging endpoint and can be retried individually if a
request fails.

No rename record is added to the object store. Commit details infer
renames from the parent and current snapshots and expose them in
`changedFiles` as `{"path":"new/path","oldPath":"old/path","status":"RENAMED"}`.
The `/diff` response for the new path reports `status: "RENAMED"` and
`renameFrom`, with a diff from the old path's content to the new path's
content.

New commits use the `MINIGIT_COMMIT_V2` header to identify their complete
file snapshots, including empty snapshots after deleting every file.
Existing `MINIGIT_COMMIT_V1` commits remain readable; commit objects and
blob storage are not rewritten.

### Verification

Run the backend test suite:

``` bash
mvn test
```

`DeletionAndRenameTest` covers working-tree deletion reporting, staging
and committing a deletion, clean status after commit, identical-content
rename detection, unrelated file changes, ambiguous matches, and undoing
a deletion.

------------------------------------------------------------------------

## Integrity Checker

Open **Integrity Checker** in the web app and select **Run Integrity
Check**. MiniGit performs a read-only scan of the object store, verifies
each object filename against the SHA-256 of its contents, parses stored
V1/V2 commit objects, checks their referenced blob and parent objects,
and validates repository branch references. It does not repair, remove,
or rewrite repository data.

The response from `GET /integrity` includes `success`,
`totalObjectsChecked`, `validObjectCount`, `corruptedObjectCount`,
`missingObjectCount`, `unreadableObjectCount`, `status`, and `results`.
Each failed result identifies `objectHash`, `status`, and `reason`.
`success` means the scan request completed; `status` is `OK` when all
checks pass and `ISSUES_FOUND` when a problem was found. An initialized
repository with no stored objects returns a successful empty report. An
uninitialized repository returns an error response.

The checker reports malformed or invalid object names and commit
metadata as corruption, referenced but absent objects as missing, and
objects or repository metadata it cannot read as unreadable. If an
object directory itself cannot be enumerated, the endpoint returns an
error instead of an incomplete success report.

Run the backend tests, including intact, empty, corrupted, missing,
unreadable, malformed, and uninitialized-repository cases:

``` bash
mvn test
```

------------------------------------------------------------------------

## Module 12 — Git client interface

The frontend uses a compact dark workspace layout with shared repository
navigation, current-branch/status indicators, and responsive panels.
The Changes, History, Branches, File Explorer, Diff Viewer, and Integrity
Checker pages continue to use the existing MiniGit services; the UI does
not simulate repository operations.

The service layer maps these views to the existing API:

| UI workflow | Existing API operations |
| --- | --- |
| Repository and Changes | `GET /repository`, `GET /status`, `POST /add`, `POST /unstage`, `POST /commit`, `POST /undo` |
| History and diffs | `GET /history`, `GET /commit-details`, `GET /diff` |
| Branches | `GET/POST/DELETE /branches`, `POST /checkout`, `GET /reflog` |
| File Explorer | `GET /files`, `GET/POST /file` |
| Integrity Checker | `GET /integrity` |

The header, summary panels, and file/commit listings are based on responses
from these APIs. No contributor identity, activity chart, storage usage,
or commit graph is fabricated when the backend does not provide that
metadata.

Some Git-client operations are not provided by the current API. In
particular, there is no endpoint to discard arbitrary unstaged working
tree edits, so the interface does not offer a fake discard action.
`POST /restore` restores a path from a specified commit under backend
preconditions; it is not a general discard operation. Staging,
unstaging, commit creation, undo, branch switching, branch creation, and
branch deletion use their existing backend routes.

Build and lint the frontend from the frontend directory:

``` bash
npm run build
npm run lint
```

------------------------------------------------------------------------

## H2 Database and Hibernate

MiniGit keeps Git repository truth in its existing `.minigit` object
store, commit objects, refs, and index. These files remain authoritative
for commits, branches, staged content, and integrity verification; none
of them are copied into SQL tables.

Hibernate ORM 6.6 (Jakarta Persistence) and H2 2.3 are used for
application-owned workspace preferences. The `workspace_preferences`
table stores a preference key, value, and update time. This avoids
creating a competing source of truth for Git data.

### Persistent database location

By default, the file database is stored at:

``` text
${user.home}/.minigit/database/minigit.mv.db
```

The database is outside both the WAR and Tomcat's temporary/expanded
deployment directory and survives application and Tomcat restarts. The
application creates the database directory if needed. Configure another
writable external database file path with either:

- JVM property `-Dminigit.database.path=C:/data/minigit`
- Environment variable `MINIGIT_DATABASE_PATH=C:\data\minigit`

The path is the H2 database base name, without the `.mv.db` suffix. If
both are supplied, the JVM property takes precedence. When Tomcat runs as
a Windows service, set the JVM property in that service's Java options,
or set the environment variable for the service account. Ensure that
account can create and write to the selected directory.

Hibernate creates or updates the small preferences schema on startup.
The shared `SessionFactory` is initialized when the web application starts
and closed when the context is stopped or redeployed.

With the default location, start Tomcat normally. To override the location
in PowerShell before starting Tomcat:

``` powershell
$env:CATALINA_OPTS = "$env:CATALINA_OPTS -Dminigit.database.path=C:/data/minigit"
& "$TomcatHome\bin\startup.bat"
```

For Tomcat installed as a Windows service, configure the same `-D` option
in the service's Java options instead of setting `CATALINA_OPTS` in an
interactive shell.

### Database endpoints and persistence check

- `GET /api/database/health` opens a Hibernate session and runs `SELECT 1`.
  It returns `{"success":true,"status":"UP","database":"H2"}` on success
  and HTTP 503 if the database is unavailable.
- `POST /api/preferences` saves or updates a preference using form fields
  `key` and `value`.
- `GET /api/preferences?key=<key>` retrieves one preference.
- `GET /api/preferences` lists all workspace preferences.

Example requests against a deployed Tomcat app:

``` text
GET  http://localhost:8080/mini-git-1.0.0/api/database/health
POST http://localhost:8080/mini-git-1.0.0/api/preferences
     Content-Type: application/x-www-form-urlencoded
     key=ui.density&value=compact
GET  http://localhost:8080/mini-git-1.0.0/api/preferences?key=ui.density
```

PowerShell verification using `curl.exe`:

``` powershell
curl.exe "http://localhost:8080/mini-git-1.0.0/api/database/health"
curl.exe -X POST "http://localhost:8080/mini-git-1.0.0/api/preferences" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  --data-urlencode "key=ui.density" `
  --data-urlencode "value=compact"
curl.exe "http://localhost:8080/mini-git-1.0.0/api/preferences?key=ui.density"
```

The preference endpoint is the Hibernate-backed save/retrieve operation;
it is intentionally separate from commit creation and repository storage.

### Build and test

From the project root:

``` bash
mvn clean test
mvn package
```

The Maven tests connect to temporary file-based H2 databases, verify
preference persistence after closing and reopening the `SessionFactory`,
and force a transaction failure to verify rollback and subsequent
database usability. The packaged WAR includes Hibernate and H2 runtime
dependencies.

### Troubleshooting

- **Database directory cannot be created:** configure
  `minigit.database.path` or `MINIGIT_DATABASE_PATH` to a location
  writable by the Tomcat service account.
- **Tomcat does not start after database configuration:** inspect Tomcat
  deployment logs for the reported H2/permission error and verify the
  configured directory path.
- **Health check returns HTTP 503:** inspect the same server logs and
  confirm the H2 file is not locked by another process. MiniGit uses an
  embedded H2 database; do not start a second application instance on
  the same file.
- **Existing Git data is missing:** H2 does not store commits or blobs.
  Continue to configure the MiniGit repository path independently with
  `-Dminigit.repository=...`.

------------------------------------------------------------------------

## Troubleshooting

### Frontend cannot reach the backend

**Symptoms:** Fetch errors, failed network requests, or a blank data
panel.

Check:

``` bash
curl -i "http://localhost:8080/mini-git-1.0.0/api/history"
```

Then verify:

-   Tomcat is running.
-   The WAR deployed successfully.
-   The API base URL matches the deployed context.
-   The browser's developer console and Network panel show the actual
    failed request.
-   CORS configuration allows the frontend development origin if the
    frontend and backend use different origins.

### HTTP 404

Common causes:

-   The WAR context is not `mini-git-1.0.0`.
-   The route differs from the route implemented by the backend.
-   A query parameter or path was appended incorrectly.
-   Tomcat has not finished deploying the application.

Confirm the exact route names in the backend controllers.

### HTTP 500

Inspect the Tomcat logs for the backend exception. Do not try to fix a
500 only by changing frontend rendering; first identify whether the
backend failed to read the commit, parent, tree, blob, or file path.

### Diff reports `UNCHANGED`

This is not automatically an error. Compare `oldContent` and
`newContent`. If they are identical, `UNCHANGED` is expected. A file can
be present in a commit snapshot without being modified by that commit.

### The file list is empty

Check the details response directly. The `files` field may be an object,
while `changedFiles` is an array. Ensure the frontend is reading the
intended field and converting `files` with `Object.entries()` when
appropriate.

### React build errors about duplicate declarations

Check `src/services/repositoryService.js` for duplicate definitions or
exports of `getCommitDetails` and `getCommitDiff`. Keep one
implementation of each function.

### The page looks different after a logic update

Keep the existing `App.css` and component class names. For the
commit-details integration, limit changes to the data-fetching logic and
the necessary text/status states. Avoid replacing the full layout or
redesigning unrelated components.

### Port 8080 is already in use

On macOS:

``` bash
lsof -i :8080
```

Identify the process before stopping it. If another Tomcat instance is
running, stop it using its own `shutdown.sh` script when possible.

### Port 5173 is already in use

Vite may choose another port and print the new URL. Open the exact URL
shown in the terminal.

------------------------------------------------------------------------

## Recommended next steps

Work through these in order.

### Phase 1 --- Stabilize the existing application

-   [ ] Confirm the frontend and backend start independently.
-   [ ] Confirm `/history` returns valid JSON.
-   [ ] Confirm `/commit-details?hash=...` returns the selected commit's
    metadata.
-   [ ] Confirm `/diff?hash=...&path=...` returns valid diff data.
-   [ ] Ensure `repositoryService.js` has only one implementation of
    each service function.
-   [ ] Keep the existing layout and CSS while integrating the new
    functions.
-   [ ] Add loading and error states for history, details, and diff
    requests.
-   [ ] Check browser console and Network panel for failed requests.

### Phase 2 --- Make commit details reliable

-   [ ] Fetch details when the selected commit changes.
-   [ ] Use the selected commit hash for the details and diff calls.
-   [ ] Populate parent hash and timestamp from the details response.
-   [ ] Support root commits with no parent.
-   [ ] Convert the `files` object into a renderable array.
-   [ ] Distinguish snapshot files from changed files in labels and
    counts.
-   [ ] Handle commits with no files.
-   [ ] Avoid stale asynchronous responses when switching between
    commits.

### Phase 3 --- Improve diff display

-   [ ] Map `ADDED`, `REMOVED`, and `CONTEXT` to the correct existing
    styles.
-   [ ] Display the returned file status.
-   [ ] Handle `UNCHANGED` clearly.
-   [ ] Add a retry action for failed diff requests.
-   [ ] Test file paths containing spaces or other URL-sensitive
    characters.
-   [ ] Verify added, modified, deleted, unchanged, and root-commit
    cases against real backend data.

### Phase 4 --- Validate repository operations

-   [ ] Verify repository overview and status.
-   [ ] Verify staging a file.
-   [ ] Verify creating a commit.
-   [ ] Refresh history after a successful commit.
-   [ ] Confirm a new commit appears with the correct message and hash.
-   [ ] Verify parent links and file contents for the new commit.
-   [ ] Test error responses from the backend and make sure the UI
    reports them clearly.

Only test staging and committing against a disposable/test repository if
the operation could modify important repository data.

### Phase 5 --- Quality and delivery

-   [ ] Run the frontend's available lint/test/build scripts.
-   [ ] Test the app in a fresh browser session.
-   [ ] Check the layout at desktop and smaller viewport sizes.
-   [ ] Remove debug logs and unused imports.
-   [ ] Document any environment-specific paths and configuration.
-   [ ] Add screenshots or a short demo to the project documentation if
    needed.
-   [ ] Commit the working changes to Git.

------------------------------------------------------------------------

## Development checklist

Before considering the commit-details and diff feature complete, verify
all of the following:

-   [ ] The old UI layout remains intact.
-   [ ] History loads successfully.
-   [ ] Selecting a commit fetches its full details.
-   [ ] The selected commit's message, timestamp, and parent are
    correct.
-   [ ] Snapshot files render correctly from the backend's `files`
    object.
-   [ ] Expanding a file fetches a diff for the correct commit hash and
    path.
-   [ ] Added, removed, and context lines render with the appropriate
    styles.
-   [ ] An unchanged file is shown as unchanged rather than as a failed
    diff.
-   [ ] Loading and error states are visible.
-   [ ] Switching commits does not show stale data.
-   [ ] The frontend production build succeeds.

------------------------------------------------------------------------

## Quick start summary

Use two terminals.

**Terminal 1 --- Backend:** Start Tomcat 11 and make sure the
`mini-git-1.0.0` WAR is deployed on port `8080`.

**Terminal 2 --- Frontend:**

``` bash
cd /path/to/frontend
npm install
npm run dev
```

Open the URL printed by the frontend development server, then verify the
API:

``` bash
curl -i "http://localhost:8080/mini-git-1.0.0/api/history"
```

For commit details and a file diff, use a real commit hash returned by
history:

``` bash
curl -i "http://localhost:8080/mini-git-1.0.0/api/commit-details?hash=<COMMIT_HASH>"

curl -G "http://localhost:8080/mini-git-1.0.0/api/diff" \
  --data-urlencode "hash=<COMMIT_HASH>" \
  --data-urlencode "path=hello.txt"
```

If these requests work but the UI does not, inspect the browser Network
panel and verify that `repositoryService.js` uses the same base URL,
route names, and query parameters.
