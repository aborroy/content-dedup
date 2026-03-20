# content-dedup

An Alfresco Content Services (ACS 26.1) Platform JAR extension that prevents duplicate file uploads by computing a SHA-256 hash of incoming content and comparing it against existing nodes in the target folder hierarchy before the upload is committed.

> Built with [aiup-alfresco](https://github.com/aborroy/aiup-alfresco) — a Claude Code plugin for Alfresco extension development.

---

## How it works

1. **Upload intercept** — An `OnContentUpdatePolicy` behaviour fires within the same database transaction as the upload, before any commit.
2. **Scope check (opt-in)** — The behaviour walks the folder's ancestor chain looking for the `cdd:hierarchyCheckEnabled` marker aspect. If the aspect is not found anywhere in the chain, hashing is skipped entirely (zero overhead for unconfigured folders).
3. **SHA-256 hash** — The content stream is hashed in-process using `java.security.MessageDigest`.
4. **Transactional search** — A DB-backed AFTS query (`QueryConsistency.TRANSACTIONAL`) checks for existing nodes with the same hash within the configured scope, bypassing Solr indexing lag.
5. **Folder lock** — A `LockService.WRITE_LOCK` on the parent folder serialises the check-and-write window, making the extension safe under concurrent uploads.
6. **Reject or accept** — If a duplicate is found, a `DuplicateContentException` (unchecked) is thrown, rolling back the entire transaction. If no duplicate, the `cdd:hashable` aspect and `cdd:sha256Hash` property are stored on the new node.

---

## Technology stack

| Component | Version |
|-----------|---------|
| Alfresco Content Services | 26.1 |
| Maven In-Process SDK (`alfresco-sdk-aggregator`) | 4.15.0 |
| Java | 17 |
| JUnit | 5.10.2 |

---

## Content model

### Namespace

| Prefix | URI |
|--------|-----|
| `cdd` | `http://www.example.com/model/content-dedup/1.0` |

### Aspects

#### `cdd:hashable`

Applied automatically to every document node after it passes the duplicate check.

| Property | Type | Mandatory | Constraints |
|----------|------|-----------|-------------|
| `cdd:sha256Hash` | `d:text` | true | Exactly 64 lowercase hex characters (`[a-f0-9]{64}`) |

#### `cdd:hierarchyCheckEnabled`

Marker aspect — no properties. Applied by an administrator to a folder to activate dedup scope for all uploads into that folder's subtree.

- **Absent from all ancestors** → zero overhead, no hashing.
- **Present on the direct parent** → folder-only duplicate check.
- **Present on an ancestor folder** → all folders from the upload target up to and including the marked folder are included in the duplicate search.

---

## Build

Prerequisites: Java 17+, Maven 3.9+.

```bash
mvn clean package
```

The JAR is produced at `target/content-dedup-1.0.0-SNAPSHOT.jar`.

---

## Deploy

### Option A — Volume mount (development)

```yaml
# In your compose.yaml:
volumes:
  - ./target/content-dedup-1.0.0-SNAPSHOT.jar:/usr/local/tomcat/webapps/alfresco/WEB-INF/lib/content-dedup-1.0.0-SNAPSHOT.jar
```

### Option B — Custom Docker image

```dockerfile
FROM alfresco/alfresco-content-repository-community:26.1.0
COPY target/content-dedup-1.0.0-SNAPSHOT.jar \
     /usr/local/tomcat/webapps/alfresco/WEB-INF/lib/
```

---

## Configuration

All properties are set in `alfresco-global.properties`. Defaults are safe without any customisation.

| Property | Default | Description |
|----------|---------|-------------|
| `content.dedup.enabled` | `true` | Master on/off switch — disables the behaviour without redeployment |
| `content.dedup.hash.algorithm` | `SHA-256` | `MessageDigest` algorithm name |
| `content.dedup.lock.timeoutSeconds` | `30` | Folder write-lock TTL; `UnableToAcquireLockException` propagates to `RetryingTransactionHelper` |

---

## Activating dedup on a folder

Apply the `cdd:hierarchyCheckEnabled` aspect to any folder **before** uploading content. Files uploaded before the aspect is applied will not have hashes stored and cannot be detected as future duplicates.

```bash
# Fetch current aspects of the folder
curl -u admin:admin \
  "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1/nodes/{folder-id}?fields=aspectNames"

# Add the marker aspect (include all existing aspects in the list)
curl -u admin:admin -X PUT \
  "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1/nodes/{folder-id}" \
  -H "Content-Type: application/json" \
  -d '{"aspectNames": ["cm:auditable", "cm:titled", "cdd:hierarchyCheckEnabled"]}'
```

Alternatively, apply the aspect through the Alfresco Share UI or Node Browser (`Admin Tools → Node Browser`).

---

## Testing

### Integration tests (JUnit 5)

Run against a live ACS instance:

```bash
mvn verify -Dacs.endpoint.path=http://localhost:8080 \
           -Dacs.username=admin \
           -Dacs.password=admin
```

The test class (`DuplicateContentCheckIT`) covers:

| Test | Requirement |
|------|-------------|
| First upload succeeds and stores correct SHA-256 hash | REQ-01 |
| Duplicate upload to same folder is rejected with descriptive error | REQ-01 / REQ-03 |
| Upload of different content to same folder succeeds | REQ-01 |
| Without hierarchy aspect, duplicate in parent folder is not detected | REQ-04 |
| With hierarchy aspect, duplicate in ancestor folder is rejected | REQ-02 |

### HTTP smoke tests (curl)

```bash
chmod +x http-tests/content-dedup.sh

# Default (localhost:8080, admin/admin)
bash http-tests/content-dedup.sh

# Custom target
HOST=http://alfresco:8080 USERNAME=admin PASSWORD=secret \
  bash http-tests/content-dedup.sh
```

Covers 6 test cases (TC-01 through TC-06) including authentication, hash storage, duplicate rejection, different-content acceptance, scope boundary enforcement, and hierarchy detection.

---

## Project structure

```
content-dedup/
├── pom.xml
├── REQUIREMENTS.md
├── http-tests/
│   └── content-dedup.sh                          # curl smoke tests
└── src/
    ├── main/
    │   ├── java/org/alfresco/contentdedup/
    │   │   ├── behaviour/
    │   │   │   └── DuplicateContentCheckBehaviour.java
    │   │   └── exception/
    │   │       └── DuplicateContentException.java
    │   └── resources/alfresco/module/content-dedup/
    │       ├── module.properties
    │       ├── module-context.xml
    │       ├── context/
    │       │   ├── bootstrap-context.xml
    │       │   └── service-context.xml
    │       └── model/
    │           └── content-model.xml
    └── test/
        └── java/org/alfresco/contentdedup/
            └── DuplicateContentCheckIT.java
```

---

## AI-assisted development walkthrough

This project was built in a single session using [Claude Code](https://claude.com/claude-code) with the [aiup-alfresco](https://github.com/aborroy/aiup-alfresco) plugin, which packages Alfresco extension development as slash commands. The full session — including three real runtime bugs discovered during testing and the architectural improvement that followed — is documented below.

---

### Step 1 — Requirements (`/requirements`)

The session started with a description of the desired behaviour:

> *Implement duplicate content detection with SHA-256. Abort on duplicate. Check before persistence. Safe for concurrent uploads.*

The `/requirements` command produced `REQUIREMENTS.md`, establishing:

- **Architecture**: single in-process Platform JAR (no async side-effects needed).
- **Five user stories** covering same-folder detection (US-01), hierarchy scope (US-02), descriptive error messages (US-03), per-folder configuration (US-04), and concurrency safety (US-05).
- **Content model**: namespace prefix `cdd`, aspects `cdd:hashable` and `cdd:hierarchyCheckEnabled`.
- **Behaviour**: `OnContentUpdatePolicy` on `cm:content` with `EVERY_EVENT`, throwing an unchecked exception to force a transaction rollback.

---

### Step 2 — Scaffold (`/scaffold`)

The `/scaffold` command generated the Maven project skeleton:

- `pom.xml` — parent `alfresco-sdk-aggregator 4.15.0`, Java 17, ACS 26.1.0, Alfresco BOM import, `alfresco-repository` / `alfresco-remote-api` / `spring-webscripts` as `provided` dependencies, JUnit 5.10.2 for tests.
- `module.properties` — `module.id=org.alfresco.content-dedup`, `module.repo.version.min=26.1`.
- `module-context.xml` — entry point that imports sub-contexts.

---

### Step 3 — Content model (`/content-model`)

The `/content-model` command generated `content-model.xml` and `bootstrap-context.xml`.

**Namespace prefix rename**: the initial generation used prefix `dc` (Dublin Core conflict). The prefix was renamed to `cdd` (Content Dedup) across the model XML and all Java `QName` constants.

Key decisions captured in the model:

- `cdd:sha256Hash` uses `<mandatory>true</mandatory>` **without** `enforced="true"` — a deliberate choice explained in [Bug fix 2](#bug-fix-2--integrityexception-on-addaspect) below.
- The property is indexed with `<tokenised>false</tokenised>` to support exact-match transactional AFTS queries.
- Two constraints guard the hash value: a `LENGTH` constraint (min/max 64) and a `REGEX` constraint (`[a-f0-9]{64}`).

---

### Step 4 — Behaviour (`/behaviours`)

The `/behaviours` command generated `DuplicateContentCheckBehaviour.java`, `DuplicateContentException.java`, and `service-context.xml`.

**Initial design** bound the behaviour to `OnContentUpdatePolicy` on `cm:content` with `EVERY_EVENT`, so it fires within the upload transaction. The algorithm:

1. Get the parent folder.
2. Build the scope: immediate folder only, or all ancestors up to the first folder bearing `cdd:hierarchyCheckEnabled`.
3. Compute SHA-256 via `MessageDigest`.
4. Acquire a `WRITE_LOCK` on the parent folder (concurrency guard).
5. Run a transactional AFTS query for existing nodes with the same hash.
6. Throw `DuplicateContentException` if found, or store the hash via `addAspect`.

---

### Step 5 — Tests (`/test`)

The `/test` command generated:

- `DuplicateContentCheckIT.java` — five ordered JUnit 5 tests using `java.net.http.HttpClient` (no extra test dependencies). Covers all five user stories.
- `http-tests/content-dedup.sh` — six `curl`-based smoke test cases (TC-01 through TC-06).

---

### Step 6 — Runtime testing and three bug fixes

Deploying the JAR and running real uploads exposed three issues.

---

#### Bug fix 1 — `QueryModelException: Analysis mode not supported for DB DEFAULT`

**Symptom**: the first upload to a folder with `cdd:hierarchyCheckEnabled` threw:

```
QueryModelException: Analysis mode not supported for DB DEFAULT
```

**Root cause**: the AFTS query in `findDuplicate()` used bare quoted-phrase syntax:

```java
// WRONG — triggers DEFAULT analysis mode, rejected by the DB query engine
"@cdd\\:sha256Hash:\"" + hash + "\""
```

The DB transactional query engine (`DBFTSPhrase`) only supports `IDENTIFIER` (exact-match) mode for property lookups. The `DEFAULT` mode is a Solr-only analysis path.

**Fix**: prefix the property term with `=` to force `IDENTIFIER` mode:

```java
// CORRECT — IDENTIFIER mode, supported by the DB transactional query engine
"=@cdd\\:sha256Hash:\"" + hash + "\""
```

This applies to any `SearchParameters` with `QueryConsistency.TRANSACTIONAL` or `TRANSACTIONAL_IF_POSSIBLE`.

---

#### Bug fix 2 — `IntegrityException: Mandatory property not set` (cdd:sha256Hash)

**Symptom**: the first upload failed at transaction commit:

```
IntegrityException: Mandatory property not set: cdd:sha256Hash on cdd:hashable
```

**Root cause**: the content model originally declared:

```xml
<mandatory enforced="true">true</mandatory>
```

With `enforced="true"`, ACS fires the `IntegrityChecker` immediately inside `OnAddAspectPolicy`, which runs *before* `NodeServiceImpl.addAspect()` has written the properties map to the database. Even though the behaviour passes a fully-populated properties map to `addAspect()`, the integrity check fires before those properties are visible, causing a spurious violation.

**Fix**: remove the `enforced` attribute:

```xml
<mandatory>true</mandatory>
```

Without `enforced`, the integrity check is deferred to `beforeCommit`, by which point `addAspect()` has written both the aspect and its properties to the database.

> **Note**: `enforced="true"` is safe only on properties belonging to **types** (not aspects), where the value must be supplied at node creation time via the REST API.

---

#### Architectural improvement — eligibility-first / zero-overhead opt-in

**Problem**: the initial `buildScope()` implementation always returned at least `[parentFolder]`, meaning every content upload anywhere in the repository triggered SHA-256 hashing — even in folders that were never configured for dedup.

**Question raised**: *"Why calculate the hash for every document even if it's not under a folder configured for duplicates exclusion?"*

**Redesign**: `buildScope()` was changed to walk the ancestor chain looking for `cdd:hierarchyCheckEnabled`. If the aspect is not found anywhere in the chain, the method returns an empty list. The caller checks for the empty list **before** calling `computeHash()` and returns immediately:

```java
// onContentUpdate — eligibility-first pattern
List<NodeRef> scope = buildScope(parentFolder);
if (scope.isEmpty()) {
    return;  // no aspect in ancestor chain → zero overhead
}
String hash = computeHash(nodeRef);  // only reached when dedup is configured
```

This means:
- **Folders without the aspect anywhere in their ancestor chain** — zero overhead: no hashing, no locking, no searching.
- **Folders with the aspect** — full dedup check, scoped from the upload folder up to and including the marked boundary folder.

The `cdd:hierarchyCheckEnabled` aspect now serves a dual role: it is both the **scope ceiling** (the topmost folder included in the hash search) and the **opt-in gate** (its absence anywhere in the chain disables dedup entirely for that subtree).

---

### Summary

| Step | Command / Action | Output |
|------|-----------------|--------|
| Requirements | `/requirements` | `REQUIREMENTS.md` — 5 user stories, content model, behaviour spec |
| Scaffold | `/scaffold` | `pom.xml`, `module.properties`, `module-context.xml` |
| Content model | `/content-model` | `content-model.xml`, `bootstrap-context.xml` |
| Namespace rename | Manual | Prefix `dc` → `cdd` across model and Java |
| Behaviour | `/behaviours` | `DuplicateContentCheckBehaviour.java`, `DuplicateContentException.java`, `service-context.xml` |
| Tests | `/test` | `DuplicateContentCheckIT.java`, `http-tests/content-dedup.sh` |
| Bug fix 1 | Runtime | AFTS query syntax: `@prop:"value"` → `=@prop:"value"` |
| Bug fix 2 | Runtime | Content model: `enforced="true"` → removed from mandatory declaration |
| Architecture | Design review | `buildScope()` returns empty list when no aspect in ancestor chain |

---

## License

[Apache License 2.0](LICENSE)
