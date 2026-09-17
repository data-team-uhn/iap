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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that attaches an uploaded file to the requirement it answers.
 *
 * <p>Attaching a document is a workflow step rather than a write, for the same reason answering a question is: a
 * submitter is granted read on their own submission and nothing else, so there is no path by which they could put
 * a file there themselves. What a submitter may attach, and when, is decided here rather than by an ACL.</p>
 *
 * <p>The rule is the one the save workflow already applies — the person who raised the request, while it is still a
 * draft. A document is part of what is being said, so it stops being changeable at exactly the moment the answers
 * do; a reviewer wanting more evidence sends the request back rather than editing it in place.</p>
 *
 * <p>An accepted type is checked before the content is read, because refusing early costs nothing and the check is
 * against what the caller claims. It is not a guarantee about the bytes: whoever needs to know that a document
 * really is what it says it is has to look, which is what {@code aiCheckPrompt} is eventually for.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class AttachDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "attachDocument";

    /** The payload entry naming the requirement the file answers. */
    static final String REQUIREMENT = "requirement";

    /** The payload entry carrying the file itself. */
    static final String FILE = "file";

    /** Where the file keeps the name it arrived under, since its node cannot. */
    static final String FILE_NAME = "fileName";

    /** Where the document records what it fulfills. */
    private static final String FULFILLS = "fulfills";

    private static final String TITLE = "title";

    private static final String VERSION_TYPE = "sub:DocumentVersion";

    /** The one file a version consists of, named by the node type. */
    private static final String FILE_CHILD = "file";

    /** The upload as it arrived, named by the node type. */
    private static final String UPLOADED_FILE = "uploadedFile";

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
            "The attach workflow only applies to submissions");
        checkMayAttach(submission, context.getActor());

        final EventAttachment file = attachment(context);
        final DocumentRequirement requirement = requirement(submission, context);
        checkAcceptedType(requirement, file);

        write(documentFor(submission, target, requirement, file), file);
    }

    /**
     * The document the upload becomes a version of: the one already fulfilling this requirement, or a new one. A
     * replacement is a new version of the same document rather than a document of its own, so what a reviewer has
     * already read stays where it was and the history is one list.
     *
     * @param submission the submission being attached to
     * @param target its resource, through the engine's resolver
     * @param requirement what the upload answers
     * @param file the upload, whose name becomes the document's title
     * @return the document's node
     * @throws PersistenceException when the document cannot be created or named
     */
    private Node documentFor(final Submission submission, final Resource target,
        final DocumentRequirement requirement, final EventAttachment file) throws PersistenceException
    {
        final String title = Objects.requireNonNullElse(file.getFileName(), "Attachment");
        for (final Document existing : submission.getDocuments()) {
            final Requirement fulfilled = existing.getFulfills();
            if (fulfilled != null && requirement.getPath().equals(fulfilled.getPath())) {
                final Node node = Objects.requireNonNull(Objects.requireNonNull(
                    target.getResourceResolver().getResource(existing.getPath()),
                    "A document the submission just reported is still where it said").adaptTo(Node.class),
                    "A document read from the repository is always backed by a JCR node");
                try {
                    node.setProperty(TITLE, title);
                } catch (final RepositoryException e) {
                    throw new PersistenceException("Could not rename the document", e);
                }
                return node;
            }
        }
        // A UUID rather than the file's name: two documents may legitimately be called the same thing, and a name
        // taken from what somebody uploaded is a name chosen by them for a node in our tree
        final Resource document = target.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Document", TITLE, title));
        final Node node = Objects.requireNonNull(document.adaptTo(Node.class),
            "A freshly created document is always backed by a JCR node");
        reference(node, document.getResourceResolver(), requirement);
        return node;
    }

    /**
     * Refuses anybody but the author, and any moment after the request has been sent.
     *
     * @param submission the submission being attached to
     * @param actor the user who fired the event
     * @throws NotAuthorizedException when they may not attach to it now
     */
    private void checkMayAttach(final Submission submission, final String actor) throws NotAuthorizedException
    {
        // getCreatedBy prefers what the engine recorded over jcr:createdBy, which names the engine's own service user
        if (!actor.equals(submission.getCreatedBy())) {
            throw new NotAuthorizedException("Only the person who raised a request may attach to it");
        }
        if (!submission.isDraft()) {
            throw new NotAuthorizedException("This request has been submitted and can no longer be changed");
        }
    }

    /**
     * The file the event carries.
     *
     * @param context the executing task's context
     * @return the attachment
     * @throws InvalidPayloadException when the event carries no file
     */
    private EventAttachment attachment(final WorkflowTaskContext context) throws InvalidPayloadException
    {
        final Object file = context.getEvent().get(FILE);
        if (!(file instanceof EventAttachment)) {
            throw new InvalidPayloadException("No file was uploaded");
        }
        return (EventAttachment) file;
    }

    /**
     * The requirement the file answers, which has to be one this submission's own schema asks for.
     *
     * <p>Resolved through the schema version rather than by trusting the path: a caller naming a requirement of
     * some other schema would otherwise attach a document that nothing on this submission ever asked for.</p>
     *
     * @param submission the submission being attached to
     * @param context the executing task's context
     * @return the requirement
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission lacks
     */
    private DocumentRequirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final Object named = context.getEvent().get(REQUIREMENT);
        if (!(named instanceof String) || ((String) named).isBlank()) {
            throw new InvalidPayloadException("The upload does not say which requirement it answers");
        }
        // The node type makes the reference mandatory and the model declares it non-null, so a submission always
        // knows what it is answering
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst()
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no document requirement " + named + " in this request"));
    }

    /**
     * Refuses a file of a type the requirement does not accept.
     *
     * @param requirement the requirement being fulfilled
     * @param file the uploaded file
     * @throws InvalidPayloadException when the declared type is not among the accepted ones
     */
    private void checkAcceptedType(final DocumentRequirement requirement, final EventAttachment file)
        throws InvalidPayloadException
    {
        // The model hands back the raw property, so absent arrives as null rather than as an empty array
        final List<String> accepted =
            List.of(Objects.requireNonNullElse(requirement.getAcceptedFileTypes(), new String[0]));
        // Accepting nothing in particular means accepting anything: a requirement that has not said what it wants
        // is not one that wants nothing
        if (accepted.isEmpty()) {
            return;
        }
        if (!accepted.contains(file.getMimeType())) {
            throw new InvalidPayloadException("A " + file.getMimeType() + " is not accepted here; "
                + requirement.getLabel() + " takes " + String.join(", ", accepted));
        }
    }

    /**
     * Records which requirement the document fulfills.
     *
     * @param document the node of the document just created
     * @param resolver the resolver to read the requirement back through
     * @param requirement the requirement it answers
     * @throws PersistenceException when the repository refuses the reference
     */
    private void reference(final Node document, final ResourceResolver resolver,
        final DocumentRequirement requirement) throws PersistenceException
    {
        final Resource resource = resolver.getResource(requirement.getPath());
        if (resource == null) {
            throw new PersistenceException("Could not read the requirement being fulfilled");
        }
        final Node requirementNode = Objects.requireNonNull(resource.adaptTo(Node.class),
            "A requirement read from the schema is always backed by a JCR node");
        try {
            // A REFERENCE cannot be written as a string through the resolver: Sling stores it as a STRING and Oak's
            // type validation refuses the commit rather than coercing it
            document.setProperty(FULFILLS, requirementNode);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference the requirement", e);
        }
    }

    /**
     * Stores the upload as the next version of the document: a {@code sub:DocumentVersion} holding a
     * {@code sub:File}, whose {@code uploadedFile} is the bytes exactly as they arrived. That is the shape the node
     * types describe and the parsing pipeline reads, and it leaves room beside the upload for what the pipeline
     * makes of it.
     *
     * <p>The node names are fixed by the node types, so the name the file arrived under is kept as a property: a
     * file name is somebody else's string and need not be a usable node name, but a parser still needs its
     * extension.</p>
     *
     * <p>Written through the JCR API rather than the resolver, because a binary is a {@code jcr:data} property on an
     * {@code nt:resource} child and streaming into it is what keeps the file out of the heap.</p>
     *
     * @param document the node of the document to store the file under
     * @param file the uploaded file
     * @throws PersistenceException when the file cannot be stored
     */
    private void write(final Node document, final EventAttachment file) throws PersistenceException
    {
        try (InputStream content = file.openStream()) {
            final Node version = document.addNode("v" + (countVersions(document) + 1), VERSION_TYPE);
            final Node stored = version.addNode(FILE_CHILD, "sub:File");
            stored.setProperty(FILE_NAME, Objects.requireNonNullElse(file.getFileName(), "attachment"));
            final Node fileNode = stored.addNode(UPLOADED_FILE, "nt:file");
            final Node resource = fileNode.addNode("jcr:content", "nt:resource");
            resource.setProperty("jcr:data",
                fileNode.getSession().getValueFactory().createBinary(content));
            // Recorded because the repository has to serve the file back with a type, and it is the only statement
            // about what this is that anybody has made
            resource.setProperty("jcr:mimeType",
                Objects.requireNonNullElse(file.getMimeType(), "application/octet-stream"));
        } catch (final RepositoryException | IOException e) {
            throw new PersistenceException("Could not store the uploaded file", e);
        }
    }

    private static int countVersions(final Node document) throws RepositoryException
    {
        int count = 0;
        final NodeIterator children = document.getNodes();
        while (children.hasNext()) {
            if (VERSION_TYPE.equals(children.nextNode().getPrimaryNodeType().getName())) {
                count++;
            }
        }
        return count;
    }
}
