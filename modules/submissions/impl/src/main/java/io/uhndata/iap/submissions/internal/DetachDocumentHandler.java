/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.submissions.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that takes a document back off a submission, so the requirement it answered can be answered
 * again with a different file.
 *
 * <p>The whole document goes, not its latest version. Attaching twice makes a second version of the same
 * document, which is what keeps a replacement readable as a replacement - but somebody removing an attachment is
 * saying this was the wrong file, not that the right one has a history worth keeping. Leaving the earlier
 * versions would also leave the requirement fulfilled by a file nobody can see any more.</p>
 *
 * <p>Detaching is a workflow step rather than a write, and under the same rule as attaching: the person who
 * raised the request, while it is still a draft. A reviewer who wants different evidence sends the request back
 * rather than removing what was filed.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class DetachDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "detachDocument";

    /** The payload entry naming the requirement whose document is to go. */
    static final String REQUIREMENT = "requirement";

    /** Said when a document the submission just reported is no longer where it said. */
    private static final String DOCUMENT_GONE =
        "A document the submission just reported is still where it said";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The detach workflow only applies to submissions");
        checkMayDetach(submission, context.getActor());

        final DocumentRequirement requirement = requirement(submission, context);
        final Document attached = attachedFor(submission, requirement);
        if (attached == null) {
            throw new InvalidPayloadException(
                "Nothing is attached for " + requirement.getLabel() + ", so there is nothing to remove");
        }
        final ResourceResolver resolver = context.getResourceResolver();
        // A reading stores strong references to the revisions it read. Oak refuses to delete a revision
        // that is still referenced, so those readings go before the document does.
        dropReadings(resolver, attached);
        resolver.delete(Objects.requireNonNull(resolver.getResource(attached.getPath()), DOCUMENT_GONE));
    }

    /**
     * Removes every extraction that read this document.
     *
     * <p>An answer the submitter typed stays. Only the reading of this file goes, including a quote that
     * names one of its revisions when several files were read together.</p>
     *
     * @param resolver the session to write through
     * @param document the document about to be removed
     * @throws PersistenceException when a reading cannot be removed
     */
    private static void dropReadings(final ResourceResolver resolver, final Document document)
        throws PersistenceException
    {
        try {
            final Resource node = Objects.requireNonNull(resolver.getResource(document.getPath()), DOCUMENT_GONE);
            final Resource submission = Objects.requireNonNull(node.getParent(),
                "A document is always part of a submission");
            final Set<String> versions = versionIdentifiers(node);
            for (final Resource reading : readingsOf(submission, versions)) {
                resolver.delete(reading);
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not drop the reading of " + document.getPath(), e);
        }
    }

    /** The identifiers of a document's revisions, which is what a reference stores. */
    private static Set<String> versionIdentifiers(final Resource document) throws RepositoryException
    {
        final Set<String> versions = new HashSet<>();
        for (final Resource child : document.getChildren()) {
            if (isNodeType(child, DocumentVersion.RESOURCE_TYPE, "sub:DocumentVersion")) {
                versions.add(identifier(child));
            }
        }
        return versions;
    }

    /**
     * The extractions that read one of these revisions.
     *
     * @param submission the submission the document belongs to
     * @param versions the revisions about to be removed
     * @return the extractions to delete, which may be empty
     * @throws RepositoryException when a reference cannot be read
     */
    private static List<Resource> readingsOf(final Resource submission, final Set<String> versions)
        throws RepositoryException
    {
        final List<Resource> readings = new ArrayList<>();
        for (final Resource child : submission.getChildren()) {
            if (!isNodeType(child, Answer.RESOURCE_TYPE, "sub:Answer")) {
                continue;
            }
            for (final Resource extraction : child.getChildren()) {
                if (isNodeType(extraction, Extraction.RESOURCE_TYPE, "sub:Extraction")
                    && reads(extraction, versions)) {
                    readings.add(extraction);
                }
            }
        }
        return readings;
    }

    /**
     * Whether a reading used one of these revisions: its own sources, or a quote that names one of them.
     *
     * @param extraction the extraction
     * @param versions the revisions being removed
     * @return {@code true} when deleting the document would be refused because of this reading
     * @throws RepositoryException when a reference cannot be read
     */
    private static boolean reads(final Resource extraction, final Set<String> versions) throws RepositoryException
    {
        if (references(extraction, "sources", versions)) {
            return true;
        }
        for (final Resource evidence : extraction.getChildren()) {
            if (references(evidence, "source", versions)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a reference property points at one of these nodes. Absent counts as no. */
    private static boolean references(final Resource resource, final String property, final Set<String> identifiers)
        throws RepositoryException
    {
        final Node node = Objects.requireNonNull(resource.adaptTo(Node.class),
            "A node read from the repository is always backed by JCR");
        if (!node.hasProperty(property)) {
            return false;
        }
        final Property stored = node.getProperty(property);
        if (stored.isMultiple()) {
            for (final Value value : stored.getValues()) {
                if (identifiers.contains(value.getString())) {
                    return true;
                }
            }
            return false;
        }
        return identifiers.contains(stored.getString());
    }

    private static String identifier(final Resource resource) throws RepositoryException
    {
        return Objects.requireNonNull(resource.adaptTo(Node.class),
            "A document read from the repository is always backed by a JCR node").getIdentifier();
    }

    private static boolean isNodeType(final Resource resource, final String resourceType, final String primaryType)
    {
        return resource.isResourceType(resourceType)
            || primaryType.equals(resource.getValueMap().get("jcr:primaryType", String.class));
    }

    /**
     * The document currently answering a requirement.
     *
     * @param submission the submission
     * @param requirement what it answers
     * @return the document, or {@code null} when nothing is attached for it
     */
    private static Document attachedFor(final Submission submission, final DocumentRequirement requirement)
    {
        for (final Document document : submission.getDocuments()) {
            final Requirement fulfilled = document.getFulfills();
            if (fulfilled != null && requirement.getPath().equals(fulfilled.getPath())) {
                return document;
            }
        }
        return null;
    }

    /**
     * Refuses anybody but the author, and any moment after the request has been sent.
     *
     * @param submission the submission being detached from
     * @param actor the user who fired the event
     * @throws NotAuthorizedException when they may not change it now
     */
    private static void checkMayDetach(final Submission submission, final String actor)
        throws NotAuthorizedException
    {
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may remove what is attached");
        }
        if (!submission.isDraft()) {
            throw new NotAuthorizedException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The requirement named by the event, which has to be one this submission's own schema asks for.
     *
     * <p>Resolved through the schema version rather than by trusting the path, for the same reason attaching
     * does it: a caller naming a requirement of some other schema would otherwise reach a document this
     * submission never asked for.</p>
     *
     * @param submission the submission
     * @param context the executing task's context
     * @return the requirement
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission lacks
     */
    private static DocumentRequirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final Object named = context.getEvent().get(REQUIREMENT);
        if (!(named instanceof String) || ((String) named).isBlank()) {
            throw new InvalidPayloadException("The request does not say which attachment is to be removed");
        }
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no document requirement " + named + " in this request"));
    }
}
