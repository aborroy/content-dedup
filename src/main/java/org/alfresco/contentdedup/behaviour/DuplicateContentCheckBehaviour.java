package org.alfresco.contentdedup.behaviour;

import org.alfresco.contentdedup.exception.DuplicateContentException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.ContentServicePolicies;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.QueryConsistency;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.alfresco.repo.policy.Behaviour.NotificationFrequency.TRANSACTION_COMMIT;

/**
 * In-process behaviour that prevents duplicate content from being stored in
 * the repository.
 *
 * <h3>Trigger</h3>
 * <p>Bound to {@link ContentServicePolicies.OnContentUpdatePolicy} on
 * {@code cm:content} with {@code TRANSACTION_COMMIT}.  The policy fires once
 * per transaction, in the {@code beforeCommit} phase, after all content writes
 * and versioning work have settled.  Throwing an unchecked exception from this
 * method causes a full rollback of the upload transaction.</p>
 *
 * <h3>Why TRANSACTION_COMMIT instead of EVERY_EVENT</h3>
 * <p>{@code EVERY_EVENT} fires inline when the content stream is closed, which
 * is before {@code cm:versionable} processing and other post-write behaviours
 * have completed.  In that window {@code nodeService.addAspect()} can interact
 * unexpectedly with concurrently running node policies, causing the
 * {@code cdd:sha256Hash} property to be dropped silently.  Firing at
 * {@code TRANSACTION_COMMIT} guarantees the node is fully settled, the content
 * stream is readable, and property writes succeed reliably.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Compute the SHA-256 (configurable) digest of the uploaded content stream.</li>
 *   <li>Query the repository with {@code QueryConsistency.TRANSACTIONAL}
 *       (DB-backed, bypasses Solr indexing lag) for existing nodes carrying the
 *       same hash inside the target folder, or the full ancestor hierarchy when
 *       the folder bears the {@code cdd:hierarchyCheckEnabled} aspect.</li>
 *   <li>If a duplicate is found: throw {@link DuplicateContentException}
 *       (rolls back the transaction, upload is aborted).</li>
 *   <li>If no duplicate: apply the {@code cdd:hashable} aspect and persist
 *       the hash so future uploads can find this node.</li>
 * </ol>
 *
 * <h3>Configuration ({@code alfresco-global.properties})</h3>
 * <ul>
 *   <li>{@code content.dedup.enabled} — master on/off switch (default: {@code true})</li>
 *   <li>{@code content.dedup.hash.algorithm} — digest algorithm (default: {@code SHA-256})</li>
 * </ul>
 */
public class DuplicateContentCheckBehaviour
        implements ContentServicePolicies.OnContentUpdatePolicy, InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DuplicateContentCheckBehaviour.class);

    // -----------------------------------------------------------------------
    // Content model QNames
    // -----------------------------------------------------------------------

    static final String NAMESPACE_URI =
            "http://www.example.com/model/content-dedup/1.0";

    static final QName ASPECT_HASHABLE =
            QName.createQName(NAMESPACE_URI, "hashable");

    static final QName PROP_SHA256_HASH =
            QName.createQName(NAMESPACE_URI, "sha256Hash");

    static final QName ASPECT_HIERARCHY_CHECK =
            QName.createQName(NAMESPACE_URI, "hierarchyCheckEnabled");

    // -----------------------------------------------------------------------
    // Injected services
    // -----------------------------------------------------------------------

    private PolicyComponent   policyComponent;
    private ContentService    contentService;
    private NodeService       nodeService;
    private SearchService     searchService;
    private FileFolderService fileFolderService;

    // -----------------------------------------------------------------------
    // Configurable properties
    // -----------------------------------------------------------------------

    private boolean enabled       = true;
    private String  hashAlgorithm = "SHA-256";

    // -----------------------------------------------------------------------
    // Spring setters
    // -----------------------------------------------------------------------

    public void setPolicyComponent(PolicyComponent policyComponent) {
        this.policyComponent = policyComponent;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setSearchService(SearchService searchService) {
        this.searchService = searchService;
    }

    public void setFileFolderService(FileFolderService fileFolderService) {
        this.fileFolderService = fileFolderService;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setHashAlgorithm(String hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    // -----------------------------------------------------------------------
    // Behaviour registration
    // -----------------------------------------------------------------------

    @Override
    public void afterPropertiesSet() {
        policyComponent.bindClassBehaviour(
                ContentServicePolicies.OnContentUpdatePolicy.QNAME,
                ContentModel.TYPE_CONTENT,
                new JavaBehaviour(this, "onContentUpdate", TRANSACTION_COMMIT));

        LOG.info("DuplicateContentCheckBehaviour registered — enabled={}, algorithm={}",
                enabled, hashAlgorithm);
    }

    // -----------------------------------------------------------------------
    // Policy implementation
    // -----------------------------------------------------------------------

    /**
     * Called once per transaction at {@code beforeCommit} for every
     * {@code cm:content} node whose primary content stream was written or
     * overwritten during that transaction.
     *
     * @param nodeRef    the node whose content was updated
     * @param newContent {@code true} if this is a first-time write; {@code false} for an overwrite
     */
    @Override
    public void onContentUpdate(NodeRef nodeRef, boolean newContent) {
        if (!enabled) {
            return;
        }
        if (!nodeService.exists(nodeRef)) {
            return;
        }

        ChildAssociationRef primaryAssoc = nodeService.getPrimaryParent(nodeRef);
        if (primaryAssoc == null) {
            return;
        }
        NodeRef parentFolder = primaryAssoc.getParentRef();
        if (parentFolder == null) {
            return;
        }

        // Resolve the dedup scope BEFORE computing the hash.
        // buildScope walks the ancestor chain looking for cdd:hierarchyCheckEnabled.
        // An empty result means no dedup boundary has been configured anywhere in the
        // ancestor tree — skip hashing entirely to avoid unnecessary I/O cost.
        List<NodeRef> scope = buildScope(parentFolder);
        if (scope.isEmpty()) {
            LOG.debug("No cdd:hierarchyCheckEnabled in ancestor chain — skipping dedup for {}", nodeRef);
            return;
        }

        String hash = computeHash(nodeRef);
        if (hash == null) {
            LOG.debug("Skipping duplicate check for {} — no content or empty stream", nodeRef);
            return;
        }

        LOG.debug("Duplicate check: node={}, hash={}, scope-depth={}, newContent={}",
                nodeRef, hash, scope.size(), newContent);

        NodeRef duplicate = findDuplicate(hash, nodeRef, scope);

        if (duplicate != null) {
            String name = (String) nodeService.getProperty(duplicate, ContentModel.PROP_NAME);
            String path = resolveDisplayPath(duplicate);
            LOG.debug("Duplicate detected — existing: id={}, name={}, path={}", duplicate, name, path);
            throw new DuplicateContentException(duplicate, name, path);
        }

        // No duplicate — record the hash on this node.
        storeHash(nodeRef, hash);
        LOG.debug("Hash {} stored on node {}", hash, nodeRef);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Streams the primary content of {@code nodeRef} through the configured
     * {@link MessageDigest} and returns the lowercase hexadecimal digest, or
     * {@code null} if the node has no content or the stream is empty.
     */
    private String computeHash(NodeRef nodeRef) {
        ContentReader reader = contentService.getReader(nodeRef, ContentModel.PROP_CONTENT);
        if (reader == null || !reader.exists() || reader.getSize() == 0) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
            byte[] buffer = new byte[8192];
            int bytesRead;
            try (InputStream is = reader.getContentInputStream()) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return toHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Hash algorithm unavailable: " + hashAlgorithm, e);
        } catch (IOException e) {
            LOG.error("Failed to read content for hash computation on {}", nodeRef, e);
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Walks the ancestor chain from {@code parentFolder} upward, looking for a
     * folder that carries {@code cdd:hierarchyCheckEnabled}.
     *
     * <h4>Return value semantics</h4>
     * <ul>
     *   <li><b>Empty list</b> — no {@code cdd:hierarchyCheckEnabled} found anywhere
     *       in the ancestor chain. Dedup is not configured for this folder tree;
     *       the caller must skip hashing and all further processing entirely.</li>
     *   <li><b>Non-empty list</b> — dedup is active. The list contains every folder
     *       from {@code parentFolder} up to and including the folder bearing the
     *       aspect (the "dedup boundary"). The duplicate search is scoped to exactly
     *       these folders via {@code PARENT:} clauses.</li>
     * </ul>
     *
     * <h4>Placement examples</h4>
     * <ul>
     *   <li>Aspect on the direct parent → scope = [parentFolder] (folder-only check).</li>
     *   <li>Aspect on a Site's {@code documentLibrary} → scope = all folders from
     *       the upload target up to {@code documentLibrary}, covering the entire
     *       site subtree without tagging every subfolder.</li>
     *   <li>No aspect anywhere → empty list → no hashing overhead.</li>
     * </ul>
     */
    private List<NodeRef> buildScope(NodeRef parentFolder) {
        List<NodeRef> scope = new ArrayList<>();
        NodeRef current = parentFolder;

        while (current != null) {
            scope.add(current);
            if (nodeService.hasAspect(current, ASPECT_HIERARCHY_CHECK)) {
                // Found the dedup boundary — return everything accumulated so far.
                return scope;
            }
            ChildAssociationRef assoc = nodeService.getPrimaryParent(current);
            current = (assoc != null) ? assoc.getParentRef() : null;
        }

        // Reached the repository root without finding the aspect — dedup not configured.
        return Collections.emptyList();
    }

    /**
     * Executes a transactional (DB-backed) FTS query to locate any existing
     * node in {@code scope} that carries a {@code cdd:sha256Hash} equal to
     * {@code hash}, excluding the node being processed ({@code excludeRef}).
     *
     * <p>{@link QueryConsistency#TRANSACTIONAL} bypasses Solr so the result
     * reflects the current database state without indexing lag.</p>
     *
     * @return the first matching {@link NodeRef}, or {@code null} if none found
     */
    private NodeRef findDuplicate(String hash, NodeRef excludeRef, List<NodeRef> scope) {
        // Build: =@cdd\:sha256Hash:"<hash>" AND (PARENT:"ref1" OR PARENT:"ref2" ...)
        //
        // The leading '=' forces EXACT/IDENTIFIER analysis mode instead of DEFAULT.
        // DEFAULT mode is not supported by the DB transactional query engine (ACS 26.1
        // throws QueryModelException "Analysis mode not supported for DB DEFAULT" when
        // a quoted phrase is used without '=').  Since the SHA-256 hash is a single
        // 64-char hex token with no whitespace, exact match is both correct and safe.
        StringBuilder query = new StringBuilder();
        query.append("=@cdd\\:sha256Hash:\"").append(hash).append("\"");
        query.append(" AND (");
        for (int i = 0; i < scope.size(); i++) {
            if (i > 0) {
                query.append(" OR ");
            }
            query.append("PARENT:\"").append(scope.get(i)).append("\"");
        }
        query.append(")");

        SearchParameters sp = new SearchParameters();
        sp.setLanguage(SearchService.LANGUAGE_FTS_ALFRESCO);
        sp.setQuery(query.toString());
        sp.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
        sp.setQueryConsistency(QueryConsistency.TRANSACTIONAL);
        sp.setMaxItems(2); // only need to know whether at least one match exists

        ResultSet results = null;
        try {
            results = searchService.query(sp);
            for (NodeRef found : results.getNodeRefs()) {
                if (!found.equals(excludeRef)) {
                    return found;
                }
            }
        } finally {
            if (results != null) {
                results.close();
            }
        }
        return null;
    }

    /**
     * Applies or updates the {@code cdd:hashable} aspect on {@code nodeRef}
     * with the supplied hash value.
     */
    private void storeHash(NodeRef nodeRef, String hash) {
        Map<QName, Serializable> props = new HashMap<>();
        props.put(PROP_SHA256_HASH, hash);

        if (nodeService.hasAspect(nodeRef, ASPECT_HASHABLE)) {
            // Content update — refresh the stored hash.
            nodeService.setProperty(nodeRef, PROP_SHA256_HASH, hash);
        } else {
            nodeService.addAspect(nodeRef, ASPECT_HASHABLE, props);
        }
    }

    /**
     * Resolves the display path of {@code nodeRef} using
     * {@link FileFolderService#getNamePath}, e.g.
     * {@code /Company Home/Sites/acme/documentLibrary/reports/Q1.pdf}.
     * Falls back to the NodeRef string if path resolution fails.
     */
    private String resolveDisplayPath(NodeRef nodeRef) {
        try {
            List<FileInfo> pathElements = fileFolderService.getNamePath(null, nodeRef);
            StringBuilder sb = new StringBuilder();
            for (FileInfo fi : pathElements) {
                sb.append('/').append(fi.getName());
            }
            return sb.length() == 0 ? nodeRef.toString() : sb.toString();
        } catch (Exception e) {
            LOG.warn("Could not resolve display path for node {}: {}", nodeRef, e.getMessage());
            return nodeRef.toString();
        }
    }
}
