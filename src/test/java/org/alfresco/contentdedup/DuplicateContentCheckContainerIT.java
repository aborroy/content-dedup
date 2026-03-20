package org.alfresco.contentdedup;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-contained integration tests: Testcontainers spins up the full ACS stack
 * via {@code compose.yaml}, deploys the extension JAR through the volume mount
 * declared in that file, runs all duplicate-detection scenarios, then tears
 * everything down.
 *
 * <p><b>Prerequisites</b>
 * <ul>
 *   <li>Docker installed and running</li>
 *   <li>{@code docker compose} v2 plugin available on the PATH</li>
 *   <li>The JAR built: {@code mvn package -DskipTests}</li>
 *   <li>Port 8080 available on the host (stop any other ACS stack first)</li>
 * </ul>
 *
 * <p><b>Run</b>
 * <pre>
 *   mvn verify
 * </pre>
 *
 * <p>Startup takes 5–10 minutes on the first run (image pull + ACS boot).
 * Subsequent runs reuse the pulled images and are faster (~3–5 minutes).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DuplicateContentCheckContainerIT {

    // -----------------------------------------------------------------------
    // Stack — started once for all tests in this class
    // -----------------------------------------------------------------------

    @Container
    static final DockerComposeContainer<?> STACK =
            new DockerComposeContainer<>(new File("compose.yaml"))
                    .withExposedService("alfresco_1", 8080,
                            Wait.forHttp("/alfresco/api/-default-/public/alfresco/versions/1/probes/-ready-")
                                    .withStartupTimeout(Duration.ofMinutes(10)))
                    .withLocalCompose(true);

    // -----------------------------------------------------------------------
    // Test content
    // -----------------------------------------------------------------------

    private static final byte[] CONTENT_A =
            "dedup-container-test-payload-A:abc1234567890".getBytes(StandardCharsets.UTF_8);

    private static final byte[] CONTENT_B =
            "dedup-container-test-payload-B:xyz0987654321".getBytes(StandardCharsets.UTF_8);

    // -----------------------------------------------------------------------
    // HTTP wiring — resolved after STACK starts
    // -----------------------------------------------------------------------

    private String    nodesApi;
    private String    authHeader;
    private HttpClient http;

    // -----------------------------------------------------------------------
    // Shared state across ordered tests
    // -----------------------------------------------------------------------

    private String rootFolderId;
    private String mainFolderId;
    private String hierarchyParentId;
    private String hierarchyChildId;
    private String firstUploadId;

    // -----------------------------------------------------------------------
    // Setup / teardown
    // -----------------------------------------------------------------------

    @BeforeAll
    void setup() throws Exception {
        String host = STACK.getServiceHost("alfresco_1", 8080);
        int    port = STACK.getServicePort("alfresco_1", 8080);
        String baseUrl = "http://" + host + ":" + port;

        nodesApi   = baseUrl + "/alfresco/api/-default-/public/alfresco/versions/1/nodes";
        authHeader = "Basic " + Base64.getEncoder()
                .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        http = HttpClient.newHttpClient();

        String stamp      = String.valueOf(System.currentTimeMillis());
        rootFolderId      = createFolder("-root-", "test-dedup-" + stamp, null);
        mainFolderId      = createFolder(rootFolderId, "main-folder", null);
        hierarchyParentId = createFolder(rootFolderId, "hier-parent", null);
        hierarchyChildId  = createFolder(hierarchyParentId, "hier-child", "cdd:hierarchyCheckEnabled");
    }

    @AfterAll
    void cleanup() throws Exception {
        if (rootFolderId != null) {
            http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(nodesApi + "/" + rootFolderId + "?permanent=true"))
                            .header("Authorization", authHeader)
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------------
    // Tests (same scenarios as DuplicateContentCheckIT)
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("REQ-01: First upload succeeds and stores correct SHA-256 hash")
    void firstUploadShouldSucceedAndStoreHash() throws Exception {
        HttpResponse<String> response = uploadFile(mainFolderId, "file-a.txt", CONTENT_A);

        assertEquals(201, response.statusCode(),
                "First upload must return HTTP 201. Body: " + response.body());

        firstUploadId = extractJsonString(response.body(), "id");
        assertNotNull(firstUploadId, "Response must contain the new node ID");

        HttpResponse<String> nodeResponse = getNode(firstUploadId);
        assertEquals(200, nodeResponse.statusCode());

        String body = nodeResponse.body();
        assertTrue(body.contains("cdd:hashable"),
                "Node must carry the cdd:hashable aspect after first upload");

        String expectedHash = sha256Hex(CONTENT_A);
        String storedHash   = extractJsonProperty(body, "cdd:sha256Hash");
        assertEquals(expectedHash, storedHash,
                "cdd:sha256Hash must match the SHA-256 digest of the uploaded content");
    }

    @Test
    @Order(2)
    @DisplayName("REQ-01/REQ-03: Duplicate upload to same folder is rejected with descriptive error")
    void duplicateUploadSameFolderShouldBeRejected() throws Exception {
        assertNotNull(firstUploadId, "Depends on test @Order(1)");

        HttpResponse<String> response = uploadFile(mainFolderId, "file-a-copy.txt", CONTENT_A);

        assertNotEquals(201, response.statusCode(),
                "Duplicate upload must not return HTTP 201");
        assertTrue(response.statusCode() >= 400,
                "Duplicate upload must return 4xx or 5xx, got: " + response.statusCode());

        String body = response.body();
        assertTrue(body.toLowerCase().contains("duplicate"),
                "Error body must contain 'Duplicate'. Actual: " + body);
        assertTrue(body.contains(firstUploadId),
                "Error body must reference existing node ID (" + firstUploadId + "). Actual: " + body);
    }

    @Test
    @Order(3)
    @DisplayName("REQ-01: Upload of different content to same folder succeeds")
    void differentContentInSameFolderShouldSucceed() throws Exception {
        HttpResponse<String> response = uploadFile(mainFolderId, "file-b.txt", CONTENT_B);

        assertEquals(201, response.statusCode(),
                "Upload of different content must return HTTP 201. Body: " + response.body());

        String nodeId = extractJsonString(response.body(), "id");
        assertNotNull(nodeId);

        HttpResponse<String> nodeResponse = getNode(nodeId);
        assertEquals(sha256Hex(CONTENT_B),
                extractJsonProperty(nodeResponse.body(), "cdd:sha256Hash"),
                "cdd:sha256Hash for CONTENT_B must match its SHA-256 digest");

        deleteNode(nodeId);
    }

    @Test
    @Order(4)
    @DisplayName("REQ-04: Without hierarchy aspect, duplicate in parent is not detected")
    void childFolderWithoutHierarchyAspectAllowsDuplicateFromParent() throws Exception {
        String plainChildId = createFolder(mainFolderId, "plain-child", null);
        try {
            HttpResponse<String> response = uploadFile(plainChildId, "file-a-in-child.txt", CONTENT_A);

            assertEquals(201, response.statusCode(),
                    "Without cdd:hierarchyCheckEnabled, duplicate in parent must not block child upload. "
                    + "Got: " + response.statusCode() + ". Body: " + response.body());

            String nodeId = extractJsonString(response.body(), "id");
            if (nodeId != null) {
                deleteNode(nodeId);
            }
        } finally {
            deleteNode(plainChildId);
        }
    }

    @Test
    @Order(5)
    @DisplayName("REQ-02: With hierarchy aspect, duplicate in ancestor folder is rejected")
    void hierarchyCheckDetectsDuplicateInAncestor() throws Exception {
        HttpResponse<String> parentUpload =
                uploadFile(hierarchyParentId, "file-in-parent.txt", CONTENT_A);
        assertEquals(201, parentUpload.statusCode(),
                "Seeding parent folder must succeed. Body: " + parentUpload.body());
        String parentNodeId = extractJsonString(parentUpload.body(), "id");

        try {
            HttpResponse<String> childUpload =
                    uploadFile(hierarchyChildId, "file-in-child.txt", CONTENT_A);

            assertNotEquals(201, childUpload.statusCode(),
                    "cdd:hierarchyCheckEnabled must detect ancestor duplicate");
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
    // HTTP helpers
    // -----------------------------------------------------------------------

    private String createFolder(String parentId, String name, String aspect) throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"").append(name).append("\",\"nodeType\":\"cm:folder\"");
        if (aspect != null) {
            json.append(",\"aspectNames\":[\"").append(aspect).append("\"]");
        }
        json.append("}");

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(nodesApi + "/" + parentId + "/children"))
                        .header("Authorization", authHeader)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode(),
                "createFolder('" + name + "') failed. Body: " + response.body());
        return extractJsonString(response.body(), "id");
    }

    private HttpResponse<String> uploadFile(String parentId, String fileName, byte[] content)
            throws Exception {
        String boundary = "boundary" + System.currentTimeMillis();
        byte[] body     = buildMultipart(boundary, fileName, content);

        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(nodesApi + "/" + parentId + "/children"))
                        .header("Authorization", authHeader)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getNode(String nodeId) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(nodesApi + "/" + nodeId + "?include=properties,aspectNames"))
                        .header("Authorization", authHeader)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void deleteNode(String nodeId) throws Exception {
        http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(nodesApi + "/" + nodeId + "?permanent=true"))
                        .header("Authorization", authHeader)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
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

        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] f = footer.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[h.length + fileContent.length + f.length];
        System.arraycopy(h,           0, r, 0,                         h.length);
        System.arraycopy(fileContent, 0, r, h.length,                  fileContent.length);
        System.arraycopy(f,           0, r, h.length + fileContent.length, f.length);
        return r;
    }

    // -----------------------------------------------------------------------
    // Minimal JSON helpers
    // -----------------------------------------------------------------------

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end >= 0 ? json.substring(start, end) : null;
    }

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
}
