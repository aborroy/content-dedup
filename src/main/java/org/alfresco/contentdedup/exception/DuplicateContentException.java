package org.alfresco.contentdedup.exception;

import org.alfresco.service.cmr.repository.NodeRef;

/**
 * Thrown by {@code DuplicateContentCheckBehaviour} when an upload is found to
 * be byte-for-byte identical to an existing document in the target folder (or
 * its ancestor hierarchy when {@code cdd:hierarchyCheckEnabled} is present).
 *
 * <p>Extends {@link RuntimeException} so that throwing it inside an
 * {@code OnContentUpdatePolicy} callback causes the enclosing transaction to
 * roll back, aborting the upload before the new node is committed.</p>
 */
public class DuplicateContentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final NodeRef existingNodeRef;
    private final String  existingName;
    private final String  existingPath;

    public DuplicateContentException(NodeRef existingNodeRef, String existingName, String existingPath) {
        super(String.format(
                "Duplicate content detected. Existing node: id=%s, name=%s, path=%s",
                existingNodeRef, existingName, existingPath));
        this.existingNodeRef = existingNodeRef;
        this.existingName    = existingName;
        this.existingPath    = existingPath;
    }

    /** NodeRef of the already-persisted document with identical content. */
    public NodeRef getExistingNodeRef() { return existingNodeRef; }

    /** {@code cm:name} of the already-persisted document. */
    public String getExistingName() { return existingName; }

    /** Full repository display path (e.g. {@code /Company Home/Sites/…}) of the existing document. */
    public String getExistingPath() { return existingPath; }
}
