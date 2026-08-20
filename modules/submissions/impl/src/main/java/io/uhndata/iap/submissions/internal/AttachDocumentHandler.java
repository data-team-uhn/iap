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
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that attaches an uploaded file to the requirement it answers.
 *
 * <p>Attaching a document is a workflow step rather than a write, for the same reason answering a question is: a
 * submitter is granted read on their own submission and nothing else, so there is no path by which they could put
 * a file there themselves. What a submitter may attach, and when, is decided here rather than by an ACL.</p>
 *
 * <p>The rule is the save workflow's own, and asked of it: the person who raised the request, while it is still a
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
    public static final String HANDLER_NAME = "attachDocument";

    /** The payload entry naming the requirement the file answers. */
    static final String REQUIREMENT_PARAMETER = "requirement";

    /** The payload entry carrying the file itself. */
    static final String FILE_PARAMETER = "file";

    /** Where the document records what it fulfills. */
    private static final String FULFILLS_PROPERTY = "fulfills";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The attach workflow only applies to submissions");
        SaveAnswersHandler.checkMayEdit(submission, context.getActor());

        final EventAttachment file = Payloads.attachment(context.getEvent(), FILE_PARAMETER);
        if (file == null) {
            throw new InvalidPayloadException("No file was uploaded");
        }
        final DocumentRequirement requirement = requirement(submission, context);
        checkAcceptedType(requirement, file);
        final Resource fulfilled = context.getResourceResolver().getResource(requirement.getPath());
        if (fulfilled == null) {
            throw new PersistenceException("Could not read the requirement being fulfilled");
        }

        VersioningUtils.checkOut(target);
        // A UUID rather than the file's name: two documents may legitimately be called the same thing, and a name
        // taken from what somebody uploaded is a name chosen by them for a node in our tree
        final Resource document = context.getResourceResolver().create(target, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Document", "title",
                Objects.requireNonNullElse(file.getFileName(), "Attachment")));
        ReferenceUtils.setReference(document, FULFILLS_PROPERTY, fulfilled);
        write(Objects.requireNonNull(document.adaptTo(Node.class),
            "A freshly created document is always backed by a JCR node"), file);
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
        final String named = Payloads.requireText(context.getEvent(), REQUIREMENT_PARAMETER,
            "The upload does not say which requirement it answers");
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
     * A file name turned into something a repository will accept as a node name.
     *
     * <p>A file name is somebody else's string, and a JCR name cannot hold {@code : / [ ] | *}, cannot be blank,
     * and cannot be {@code .} or {@code ..}. A legitimate upload must not fail because of what its file happens to
     * be called, so what cannot be part of a name becomes an underscore. Nothing is lost by it: the name the
     * person gave is kept verbatim as the document's title, which is what anybody is shown.</p>
     *
     * @param fileName the name the file arrived under, possibly {@code null}
     * @return a usable node name
     */
    private static String nodeName(final String fileName)
    {
        final String usable = Objects.requireNonNullElse(fileName, "").trim().replaceAll("[:/\\[\\]|*]", "_");
        return usable.isEmpty() || usable.chars().allMatch(character -> character == '.') ? "attachment" : usable;
    }

    /**
     * Stores the uploaded bytes as an {@code nt:file} child of the document.
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
            final Node fileNode = document.addNode(nodeName(file.getFileName()), "nt:file");
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
}
