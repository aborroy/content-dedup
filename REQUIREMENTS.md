# Requirements: Duplicate Content Detector

## 1. Overview

- **Extension name**: `content-dedup`
- **Business purpose**: Prevent duplicate file uploads by computing a SHA-256 hash of incoming content and comparing it against existing nodes in the target folder (and optionally its ancestor hierarchy) before the upload is persisted. Identical content causes an immediate transaction rollback with a descriptive error.
- **Target ACS version**: 26.1

---

## 2. Project Architecture

Single-project layout — synchronous in-process behaviour only, no async side-effects required.

| Project | Type | SDK | Root path | Purpose |
|---------|------|-----|-----------|---------|
| `content-dedup-platform` | Platform JAR | alfresco-sdk-parent 4.15.0 | `.` | SHA-256 hash computation, duplicate-check behaviour, content model |

---

## 3. User Stories

| ID | User Story |
|----|------------|
| US-01 | As a **content contributor**, I want the system to reject an upload whose byte-for-byte content already exists in the target folder, so that the repository does not accumulate redundant documents. |
| US-02 | As a **content contributor**, I want the system to optionally reject an upload if identical content exists anywhere in the ancestor folder hierarchy, so that duplicates are caught repository-wide when required. |
| US-03 | As a **content contributor**, I want the rejection error to include the existing document's node identifier, display name, and repository path, so that I can locate the original without a manual search. |
| US-04 | As a **system administrator**, I want to control duplicate-check scope per folder (folder-only vs. full hierarchy) by toggling an aspect on the folder node, so that I can tune detection breadth without redeploying code. |
| US-05 | As a **developer**, I want the duplicate check to be safe under concurrent uploads of identical files to the same folder, so that race conditions never let two identical documents persist simultaneously. |

---

## 4. Acceptance Criteria

### US-01 — Duplicate rejected within same folder

- **Given** a folder contains a document with SHA-256 hash `H`,
  **when** a new file with hash `H` is uploaded to the same folder,
  **then** the upload is aborted, the transaction is rolled back, and a `DuplicateContentException` (RuntimeException) is thrown before the new node is committed.

- **Given** a folder contains a document with SHA-256 hash `H`,
  **when** a new file with a *different* hash is uploaded to the same folder,
  **then** the upload succeeds and the new node is persisted with its hash stored in the `cdd:sha256Hash` property.

### US-02 — Hierarchy-scoped duplicate check

- **Given** a folder has the `cdd:hierarchyCheckEnabled` aspect applied,
  **and** an ancestor folder (at any depth) contains a document with hash `H`,
  **when** a file with hash `H` is uploaded into the folder,
  **then** the upload is aborted with `DuplicateContentException`.

- **Given** a folder does *not* have the `cdd:hierarchyCheckEnabled` aspect,
  **and** only an ancestor folder contains a document with hash `H` (the target folder itself has no duplicate),
  **when** a file with hash `H` is uploaded,
  **then** the upload succeeds (folder-scoped check only).

### US-03 — Error message content

- **Given** a duplicate is detected,
  **when** `DuplicateContentException` is thrown,
  **then** the exception message contains:
  - the existing node's `NodeRef` (UUID form),
  - the existing node's `cm:name`,
  - the full repository path (e.g. `/Company Home/Sites/mysite/documentLibrary/reports/Q1.pdf`).

### US-04 — Per-folder scope configuration

- **Given** no aspect is present on a folder,
  **when** an upload is made,
  **then** only the immediate folder is checked (default behaviour).

- **Given** the `cdd:hierarchyCheckEnabled` aspect is applied to a folder,
  **when** an upload is made,
  **then** the check walks all ancestor folders up to the repository root.

### US-05 — Concurrency safety

- **Given** two identical files are uploaded to the same folder at exactly the same time (concurrent requests),
  **when** both transactions run,
  **then** at most one upload succeeds; the other is rejected with `DuplicateContentException` (no silent duplicates, no deadlock, no unhandled exception).

---

## 5. Content Model Requirements

*(Platform JAR)*

### Namespace

| Prefix | URI |
|--------|-----|
| `cdd` | `http://www.example.com/model/content-dedup/1.0` |

### Custom Aspects

#### `cdd:hashable`

Applied automatically to every document node after its content passes the duplicate check.

| Property | Data type | Mandatory | Indexed | Constraints | Notes |
|----------|-----------|-----------|---------|-------------|-------|
| `cdd:sha256Hash` | `d:text` | true | true (tokenised: false) | length ≤ 64, pattern `[a-f0-9]{64}` | Lowercase hex SHA-256 digest |

#### `cdd:hierarchyCheckEnabled`

Applied manually to a folder node by an administrator to activate hierarchy-scoped checking for all uploads into that folder.

*No additional properties — presence of the aspect is the flag.*

### Associations

None required.

---

## 6. API Requirements

*(Platform JAR — no custom web scripts required)*

The duplicate check is triggered transparently through the standard Alfresco upload flow (CMIS, Share, REST API v1). No new endpoints are needed.

---

## 7. Behaviour Requirements

*(Platform JAR)*

### 7.1 `DuplicateContentCheckBehaviour`

| Attribute | Value |
|-----------|-------|
| Policy | `org.alfresco.repo.content.ContentServicePolicies.OnContentUpdatePolicy` |
| Binding | `{http://www.alfresco.org/model/content/1.0}content` (and subtypes) |
| Timing | Runs within the same transaction as the upload — throwing a `RuntimeException` causes a full rollback |

**Algorithm (per invocation):**

1. Retrieve the `ContentReader` for the updated node; skip if no content or content is empty.
2. Stream the content through `MessageDigest(SHA-256)` to produce hex digest `H`. *(Content is read from the transaction-local content store — not yet committed.)*
3. Determine scope:
   - If the parent folder carries `cdd:hierarchyCheckEnabled`, collect all ancestor `NodeRef`s up to the repository root.
   - Otherwise, use only the immediate parent folder.
4. Execute a **transactional (DB-backed) search** — `SearchService` with `language = db-afts` — for nodes matching `@dc\:sha256Hash:"H"` within the scoped paths. *(Solr/Elasticsearch is intentionally bypassed to avoid indexing lag and ensure transactional visibility.)*
5. Exclude the node currently being created/updated from results.
6. If any matching node is found:
   - Resolve its display name (`cm:name`), full path (`FileFolderService.getNamePath`), and `NodeRef`.
   - Throw `DuplicateContentException` (unchecked) with message:
     `"Duplicate content detected. Existing node: id=<nodeRef>, name=<name>, path=<path>"`
7. If no duplicate: apply `cdd:hashable` aspect to the node and set `cdd:sha256Hash = H`.

### 7.2 Concurrency Strategy

- Wrap steps 4–7 in a **`RetryingTransactionHelper`-aware** pessimistic folder lock using `LockService.lock(parentFolderNodeRef, LockType.WRITE_LOCK, 0)` for the duration of the check-and-write window.
- The lock scope is the **immediate parent folder** (not the hierarchy root) to minimise contention.
- `LockService` uses the Alfresco persistent lock mechanism; if a competing transaction holds the lock, the caller retries via `RetryingTransactionHelper` (standard Alfresco retry-on-lock behaviour).

### 7.3 `DuplicateContentException`

Custom unchecked exception (`extends RuntimeException`) carrying:

```java
private final NodeRef existingNodeRef;
private final String   existingName;
private final String   existingPath;
```

---

## 8. Deployment Requirements

### Docker Compose services

| Service | Image | Notes |
|---------|-------|-------|
| `alfresco` | `alfresco/alfresco-content-repository-community:26.1.x` | Mount Platform JAR under `/usr/local/tomcat/webapps/alfresco/WEB-INF/lib/` or deploy as a JAR into the ACS image |
| `postgres` | `postgres:15` | Standard ACS database |
| `solr6` | `alfresco/alfresco-search-services:2.x` | Required for non-transactional queries; transactional (`db-afts`) queries also depend on Solr schema being loaded |
| `activemq` | `alfresco/alfresco-activemq:5.x` | Standard ACS dependency |
| `transform-core-aio` | `alfresco/alfresco-transform-core-aio:5.x` | Standard ACS dependency |

### Environment-specific configuration

| Property | Location | Default | Notes |
|----------|----------|---------|-------|
| `content.dedup.enabled` | `alfresco-global.properties` | `true` | Master switch to disable the behaviour without undeploying |
| `content.dedup.hash.algorithm` | `alfresco-global.properties` | `SHA-256` | Allows future swap to SHA-3; behaviour reads this at startup |
| `content.dedup.lock.timeoutSeconds` | `alfresco-global.properties` | `30` | Max seconds to wait for the folder write-lock before failing |

---

## 9. Traceability Matrix

| Req ID | Project | User Story | Content Model | API | Behaviour / Handler | Test |
|--------|---------|------------|---------------|-----|---------------------|------|
| REQ-01 | `content-dedup-platform` | US-01 | `cdd:hashable` / `cdd:sha256Hash` | — | `DuplicateContentCheckBehaviour` (same-folder check) | `DuplicateContentCheckIT#firstUploadShouldSucceedAndStoreHash` · `DuplicateContentCheckIT#differentContentInSameFolderShouldSucceed` · `content-dedup.sh` TC-01/TC-03 |
| REQ-02 | `content-dedup-platform` | US-02 | `cdd:hierarchyCheckEnabled` | — | `DuplicateContentCheckBehaviour` (hierarchy walk) | `DuplicateContentCheckIT#hierarchyCheckDetectsDuplicateInAncestor` · `content-dedup.sh` TC-05 |
| REQ-03 | `content-dedup-platform` | US-03 | — | — | `DuplicateContentException` message format | `DuplicateContentCheckIT#duplicateUploadSameFolderShouldBeRejected` · `content-dedup.sh` TC-02b/TC-02c |
| REQ-04 | `content-dedup-platform` | US-04 | `cdd:hierarchyCheckEnabled` presence | — | Scope-detection logic in `DuplicateContentCheckBehaviour` | `DuplicateContentCheckIT#childFolderWithoutHierarchyAspectAllowsDuplicateFromParent` · `content-dedup.sh` TC-04 |
| REQ-05 | `content-dedup-platform` | US-05 | — | — | `LockService` folder-lock + `RetryingTransactionHelper` | `DuplicateContentCheckIT#duplicateUploadSameFolderShouldBeRejected` (concurrent scenario verified by absence of silent duplicates) |
