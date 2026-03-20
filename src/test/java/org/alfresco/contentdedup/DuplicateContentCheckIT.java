package org.alfresco.contentdedup;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the duplicate-content-detection extension.
 *
 * <p>Runs against a live ACS instance reachable at {@code acs.endpoint.path}
 * (default: {@code http://localhost:8080}). Configure via system properties or
 * Maven Failsafe plugin properties:
 * <pre>
 *   mvn verify -Dacs.endpoint.path=http://alfresco:8080 \
 *              -Dacs.username=admin -Dacs.password=admin
 * </pre>
 *
 * <p>Test isolation: all nodes are created inside a timestamped root folder and
 * permanently deleted in {@link #deleteTestData()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DuplicateContentCheckIT {

    // -----------------------------------------------------------------------
    // Configuration (override via system properties)
    // -----------------------------------------------------------------------

    private final String baseUrl  = System.getProperty("acs.endpoint.path", "http://localhost:8080");
    private final String username = System.getProperty("acs.username", "admin");
    private final String password = System.getProperty("acs.password", "admin");

    private final String nodesApi;
    private final String authHeader;
    private final HttpClient http = HttpClient.newHttpClient();

    // -----------------------------------------------------------------------
    // Test content — two distinct byte sequences
    // -----------------------------------------------------------------------

    /** First payload: used to seed and then attempt to re-upload. */
    private static final byte[] CONTENT_A =
            "duplicate-detection-content-A:abcdef1234567890".getBytes(StandardCharsets.UTF_8);

    /** Second payload: different from A, must not be flagged as a duplicate. */
    private static final byte[] CONTENT_B =
            "duplicate-detection-content-B:0987654321fedcba".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------------
    // Shared state across ordered tests
    // -----------------------------------------------------------------------

    /** Root test folder — deleted in @AfterAll. */
    private String rootFolderId;

    /** Plain folder: no hierarchy check aspect. */
    private String mainFolderId;

    /** Parent folder in the hierarchy test — has NO aspect itself. */
    private String hierarchyParentId;

    /** Child folder in the hierarchy test — carries cdd:hierarchyCheckEnabled. */
    private String hierarchyChildId;

    /** NodeRef of the first successful upload — used to verify error messages. */
    private String firstUploadId;

    // -----------------------------------------------------------------------
    // Constructor — derives computed fields from configuration
    // -----------------------------------------------------------------------

    public DuplicateContentCheckIT() {
        nodesApi    = baseUrl + "/alfresco/api/-default-/public/alfresco/versions/1/nodes";
        authHeader  = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeAll
    void createTestFolderStructure() throws Exception {
        String stamp = String.valueOf(System.currentTimeMillis());

        rootFolderId      = createFolder("-root-", "test-dedup-" + stamp, null);
        mainFolderId      = createFolder(rootFolderId, "main-folder", null);
        hierarchyParentId = createFolder(rootFolderId, "hier-parent", null);
        // child carries the hierarchy-check aspect — see REQUIREMENTS.md §5
        hierarchyChildId  = createFolder(hierarchyParentId, "hier-child", "cdd:hierarchyCheckEnabled");
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * REQ-01 — First upload must succeed; the {@code cdd:hashable} aspect must be
     * applied and {@code cdd:sha256Hash} must equal the SHA-256 digest of the content.
     */
    @Test
    @Order(1)
    @DisplayName("REQ-01: First upload succeeds and stores correct SHA-256 hash")
    void firstUploadShouldSucceedAndStoreHash() throws Exception {
        HttpResponse<String> response = uploadFile(mainFolderId, "file-a.txt", CONTENT_A);

        assertEquals(201, response.statusCode(),
                "First upload of CONTENT_A must return HTTP 201 Created. Body: " + response.body());

        firstUploadId = extractJsonString(response.body(), "id");
        assertNotNull(firstUploadId, "Response body must contain the new node ID");

        // Fetch full node including properties and aspectNames
        HttpResponse<String> nodeResponse = getNode(firstUploadId);
        assertEquals(200, nodeResponse.statusCode());

        String body = nodeResponse.body();
        assertTrue(body.contains("cdd:hashable"),
                "Node must carry the cdd:hashable aspect after first upload");

        String expectedHash = sha256Hex(CONTENT_A);
        String storedHash   = extractJsonProperty(body, "cdd:sha256Hash");
        assertEquals(expectedHash, storedHash,
                "cdd:sha256Hash must equal the SHA-256 digest of the uploaded content");
    }

    /**
     * REQ-01 / REQ-03 — Re-uploading identical bytes to the same folder must be
     * rejected. The error response must reference the existing node's ID and contain
     * the word "Duplicate".
     */
    @Test
    @Order(2)
    @DisplayName("REQ-01/REQ-03: Duplicate upload to same folder is rejected with descriptive error")
    void duplicateUploadSameFolderShouldBeRejected() throws Exception {
        assertNotNull(firstUploadId, "Depends on test @Order(1) — firstUploadId must be set");

        HttpResponse<String> response = uploadFile(mainFolderId, "file-a-copy.txt", CONTENT_A);

        assertNotEquals(201, response.statusCode(),
                "Duplicate upload must not return HTTP 201 Created");
        assertTrue(response.statusCode() >= 400,
                "Duplicate upload must return a 4xx or 5xx status, got: " + response.statusCode());

        String body = response.body();
        assertTrue(body.toLowerCase().contains("duplicate"),
                "Error body must contain 'Duplicate'. Actual: " + body);
        assertTrue(body.contains(firstUploadId),
                "Error body must reference the existing node ID (" + firstUploadId + "). Actual: " + body);
    }

    /**
     * REQ-01 — Uploading content that differs from anything already in the folder
     * must succeed.
     */
    @Test
    @Order(3)
    @DisplayName("REQ-01: Upload of different content to same folder succeeds")
    void differentContentInSameFolderShouldSucceed() throws Exception {
        HttpResponse<String> response = uploadFile(mainFolderId, "file-b.txt", CONTENT_B);

        assertEquals(201, response.statusCode(),
                "Upload of CONTENT_B (different from A) must return HTTP 201. Body: " + response.body());

        String nodeId = extractJsonString(response.body(), "id");
        assertNotNull(nodeId, "Response must contain the new node ID");

        HttpResponse<String> nodeResponse = getNode(nodeId);
        assertEquals(expectedHash(CONTENT_B),
                extractJsonProperty(nodeResponse.body(), "cdd:sha256Hash"),
                "cdd:sha256Hash for CONTENT_B must match its SHA-256 digest");

        deleteNode(nodeId); // tidy up — only this node, not the folder
    }

    /**
     * REQ-04 — Without {@code cdd:hierarchyCheckEnabled}, a duplicate in a parent
     * folder must NOT be detected when uploading to a child folder (folder-scoped
     * check only).
     */
    @Test
    @Order(4)
    @DisplayName("REQ-04: Without hierarchy aspect, duplicate in parent is not detected")
    void childFolderWithoutHierarchyAspectAllowsDuplicateFromParent() throws Exception {
        // mainFolderId already contains CONTENT_A (from test 1).
        // Create a plain child folder — no aspect.
        String plainChildId = createFolder(mainFolderId, "plain-child", null);
        try {
            HttpResponse<String> response = uploadFile(plainChildId, "file-a-in-child.txt", CONTENT_A);

            assertEquals(201, response.statusCode(),
                    "Without cdd:hierarchyCheckEnabled, duplicate in parent must not block child upload. "
                    + "Got HTTP " + response.statusCode() + ". Body: " + response.body());

            String nodeId = extractJsonString(response.body(), "id");
            if (nodeId != null) {
                deleteNode(nodeId);
            }
        } finally {
            deleteNode(plainChildId);
        }
    }

    /**
     * REQ-02 — With {@code cdd:hierarchyCheckEnabled} on the upload-target folder,
     * a duplicate anywhere in the ancestor chain must be detected and the upload
     * rejected.
     */
    @Test
    @Order(5)
    @DisplayName("REQ-02: With hierarchy aspect, duplicate in ancestor folder is rejected")
    void hierarchyCheckDetectsDuplicateInAncestor() throws Exception {
        // Seed the parent folder with CONTENT_A.
        HttpResponse<String> parentUpload =
                uploadFile(hierarchyParentId, "file-in-parent.txt", CONTENT_A);
        assertEquals(201, parentUpload.statusCode(),
                "Seeding parent folder must succeed. Body: " + parentUpload.body());
        String parentNodeId = extractJsonString(parentUpload.body(), "id");

        try {
            // hierarchyChildId carries cdd:hierarchyCheckEnabled — must walk ancestors.
            HttpResponse<String> childUpload =
                    uploadFile(hierarchyChildId, "file-in-child.txt", CONTENT_A);

            assertNotEquals(201, childUpload.statusCode(),
                    "cdd:hierarchyCheckEnabled must cause ancestor duplicate to be detected");
            assertTrue(childUpload.statusCode() >= 400,
                    "Hierarchy duplicate must return 4xx/5xx. Got: " + childUpload.statusCode());
            assertTrue(childUpload.body().toLowerCase().contains("duplicate"),
                    "Error body must contain 'Duplicate'. Actual: " + childUpload.body());
        } finally {
            if (parentNodeId != null) {
                deleteNode(parentNodeId);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    @AfterAll
    void deleteTestData() throws Exception {
        if (rootFolderId != null) {
            // Permanent delete removes the entire test tree including all children.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(nodesApi + "/" + rootFolderId + "?permanent=true"))
                    .header("Authorization", authHeader)
                    .DELETE()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    /** Creates a cm:folder; optionally applies a single extra aspect at creation time. */
    private String createFolder(String parentId, String name, String aspect) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"").append(name).append("\",\"nodeType\":\"cm:folder\"");
        if (aspect != null) {
            json.append(",\"aspectNames\":[\"").append(aspect).append("\"]");
        }
        json.append("}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodesApi + "/" + parentId + "/children"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(),
                "createFolder('" + name + "') failed. Body: " + response.body());
        return extractJsonString(response.body(), "id");
    }

    /** Uploads a file via multipart/form-data to {@code POST /nodes/{parentId}/children}. */
    private HttpResponse<String> uploadFile(String parentId, String fileName, byte[] content)
            throws Exception {
        String boundary = "boundary" + System.currentTimeMillis();
        byte[] body = buildMultipart(boundary, fileName, content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodesApi + "/" + parentId + "/children"))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Fetches a node including {@code properties} and {@code aspectNames}. */
    private HttpResponse<String> getNode(String nodeId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodesApi + "/" + nodeId + "?include=properties,aspectNames"))
                .header("Authorization", authHeader)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Permanently deletes a node (requires admin). */
    private void deleteNode(String nodeId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(nodesApi + "/" + nodeId + "?permanent=true"))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------------
    // Multipart body builder
    // -----------------------------------------------------------------------

    private static byte[] buildMultipart(String boundary, String fileName, byte[] fileContent) {
        String CRLF   = "\r\n";
        String header = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"filedata\"; filename=\"" + fileName + "\"" + CRLF
                + "Content-Type: application/octet-stream" + CRLF
                + CRLF;
        String footer = CRLF + "--" + boundary + "--" + CRLF;

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + fileContent.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0,                                   headerBytes.length);
        System.arraycopy(fileContent, 0, result, headerBytes.length,                  fileContent.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileContent.length, footerBytes.length);
        return result;
    }

    // -----------------------------------------------------------------------
    // Minimal JSON helpers (no extra dependencies)
    // -----------------------------------------------------------------------

    /**
     * Extracts the string value of {@code "key":"value"} from a JSON response.
     * Sufficient for well-formed, compact ACS REST API responses.
     */
    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end >= 0 ? json.substring(start, end) : null;
    }

    /**
     * Extracts the value of a property inside the {@code "properties":{}} object.
     * Delegates to {@link #extractJsonString} since ACS serialises property values
     * as JSON strings.
     */
    private static String extractJsonProperty(String json, String qualifiedName) {
        return extractJsonString(json, qualifiedName);
    }

    // -----------------------------------------------------------------------
    // Crypto helpers
    // -----------------------------------------------------------------------

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String expectedHash(byte[] content) {
        try { return sha256Hex(content); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
