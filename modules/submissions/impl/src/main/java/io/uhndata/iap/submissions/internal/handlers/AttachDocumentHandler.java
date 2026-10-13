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
package io.uhndata.iap.submissions.internal.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.documents.api.DocumentLimits;
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
 * The service task that attaches an uploaded file to the document requirement it answers. Each upload adds a version
 * to the requirement's document.
 *
 * <p>What a submitter may attach, and when, is decided here, not by an ACL. The rule is the one saving answers
 * follows: only the person who raised the request, and only while it is a draft.</p>
 *
 * <p>The type is checked before the content is read, and it is the type the caller claims. Nothing here checks
 * that the bytes are of that type.</p>
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

    private static final long BYTES_PER_KILOBYTE = 1024L;

    private static final long BYTES_PER_MEGABYTE = 1024L * 1024;

    /** Where the document records what it fulfills. */
    private static final String FULFILLS_PROPERTY = "fulfills";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String TITLE_PROPERTY = "title";

    private static final String GENERIC_TYPE = "application/octet-stream";

    private static final String PDF_TYPE = "application/pdf";

    /**
     * Types a browser may not know and send untyped, by the extension that names them. The browser checks against
     * its own copy, {@code MIME_TYPE_BY_EXTENSION} in frontend-commons' {@code fileValidation.ts}.
     */
    private static final Map<String, String> TYPE_BY_EXTENSION = Map.of(
        "pdf", PDF_TYPE,
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "doc", "application/msword");

    /** The name the node type gives a version's file. */
    private static final String FILE_NODE = "file";

    /** The name the node type gives the upload inside a file. */
    private static final String UPLOADED_FILE_NODE = "uploadedFile";

    /** The document limits, the same ones the browser checks against; only the file size is checked here. */
    private final DocumentLimits limits;

    /**
     * The handler, checking each upload against the configured limits.
     *
     * @param limits the document limits
     */
    @Activate
    public AttachDocumentHandler(@Reference final DocumentLimits limits)
    {
        this.limits = limits;
    }

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = SubmissionHost.read(target, HANDLER_NAME);
        SaveAnswersHandler.checkMayEdit(submission, context.getActor());

        final EventAttachment file = Payloads.attachment(context.getEvent(), FILE_PARAMETER);
        if (file == null) {
            throw new InvalidPayloadException("No file was uploaded");
        }
        final DocumentRequirement requirement = requirement(submission, context);
        final String mimeType = getMimeType(file);
        checkAcceptedType(requirement, mimeType);
        checkSize(file);
        checkPages(file, mimeType);

        VersioningUtils.checkOut(target);
        final ResourceResolver resolver = context.getResourceResolver();
        final Resource document = findOrCreateDocument(target, submission, requirement, file, resolver);
        // Not named by its number, which is its position among the document's versions and is not stored
        final Resource version = resolver.create(document, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:DocumentVersion"));
        final Resource stored = resolver.create(version, FILE_NODE, Map.of(PRIMARY_TYPE, "sub:File"));
        write(Objects.requireNonNull(stored.adaptTo(Node.class),
            "A freshly created file is always backed by a JCR node"), file, mimeType);
        context.recordRole(document, "attached a document to " + requirement.getLabel());
    }

    /**
     * The requirement the file answers. It is looked up among the requirements of this submission's own schema
     * version, so a path into another schema is refused.
     *
     * @param submission the submission being attached to
     * @param context the executing task's context
     * @return the requirement
     * @throws InvalidPayloadException when the event names no requirement, or names one this submission is not asked
     */
    private DocumentRequirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws InvalidPayloadException
    {
        final String named = Payloads.requireText(context.getEvent(), REQUIREMENT_PARAMETER,
            "The upload does not say which requirement it answers");
        return findRequirement(submission, named)
            // One whose condition does not hold is not asked, and the form does not offer it
            .filter(submission::isApplicable)
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no document requirement " + named + " in this request"));
    }

    /**
     * The document requirement of the submission's own schema version that a caller names, by path or by name.
     *
     * @param submission the submission
     * @param named the requirement's absolute path or node name, as the caller sent it
     * @return the requirement; empty when the schema version has no such document requirement
     */
    static Optional<DocumentRequirement> findRequirement(final Submission submission, final String named)
    {
        return submission.getSchemaVersion().getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(candidate -> candidate.getPath().equals(named) || candidate.getName().equals(named))
            .findFirst();
    }

    /**
     * The document answering the requirement, created here when the submission has none for it yet. Each upload
     * names the document after its file, so the title follows the newest file.
     *
     * @param target the submission's resource
     * @param submission the submission being attached to
     * @param requirement the requirement the file answers
     * @param file the uploaded file
     * @param resolver the resolver to write with
     * @return the document resource
     * @throws PersistenceException when the document cannot be written
     */
    private Resource findOrCreateDocument(final Resource target, final Submission submission,
        final DocumentRequirement requirement, final EventAttachment file, final ResourceResolver resolver)
        throws PersistenceException
    {
        final String title = Objects.requireNonNullElse(file.getFileName(), "Attachment");
        final Resource existing = submission.getDocuments().stream()
            .filter(document -> document.isFulfilling(requirement))
            .findFirst()
            .map(document -> resolver.getResource(document.getPath()))
            .orElse(null);
        if (existing != null) {
            Objects.requireNonNull(existing.adaptTo(ModifiableValueMap.class),
                "A document read through a writing resolver is always modifiable").put(TITLE_PROPERTY, title);
            return existing;
        }
        // A UUID, since the uploader chooses the title, and two documents may share one
        final Resource document = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Document", TITLE_PROPERTY, title));
        ReferenceUtils.setReference(document, FULFILLS_PROPERTY, requirement);
        return document;
    }

    /**
     * The upload's type, lower-case and without parameters. Where the caller sent no type or only the generic one,
     * the file's extension decides, since browsers often do not know the Office types.
     *
     * @param file the uploaded file
     * @return the media type; {@code null} when none was sent and the extension is not a known one
     */
    private static String getMimeType(final EventAttachment file)
    {
        final String declared = Objects.toString(file.getMimeType(), "").split(";", 2)[0].trim()
            .toLowerCase(Locale.ROOT);
        if (!declared.isEmpty() && !GENERIC_TYPE.equals(declared)) {
            return declared;
        }
        final String name = Objects.requireNonNullElse(file.getFileName(), "");
        // A name with no dot has no extension: a file called "pdf" is not a PDF
        final int dot = name.lastIndexOf('.');
        final String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPE_BY_EXTENSION.getOrDefault(extension, declared.isEmpty() ? null : declared);
    }

    /**
     * Refuses a file of a type the requirement does not accept.
     *
     * @param requirement the requirement being fulfilled
     * @param mimeType the upload's type, as {@link #getMimeType} reads it
     * @throws InvalidPayloadException when the type is not among the accepted ones
     */
    private void checkAcceptedType(final DocumentRequirement requirement, final String mimeType)
        throws InvalidPayloadException
    {
        final List<String> accepted = requirement.getAcceptedFileTypes();
        // A requirement that lists no type accepts any
        if (accepted.isEmpty()) {
            return;
        }
        if (accepted.stream().noneMatch(type -> type.equalsIgnoreCase(mimeType))) {
            throw new InvalidPayloadException("A " + Objects.requireNonNullElse(mimeType, "file with no declared type")
                + " is not accepted here; " + requirement.getLabel() + " takes " + String.join(", ", accepted));
        }
    }

    /**
     * Refuses a file larger than the configured limit, before its content is opened.
     *
     * @param file the uploaded file
     * @throws InvalidPayloadException when the file is too large
     */
    private void checkSize(final EventAttachment file) throws InvalidPayloadException
    {
        final long maxFileSize = this.limits.getMaxFileSize();
        if (file.getSize() > maxFileSize) {
            // The file's size rounds up and the limit's down, so a file over the limit never reads as being at it
            throw new InvalidPayloadException("That file is " + formatSize(file.getSize(), RoundingMode.CEILING)
                + ", and the limit is " + formatSize(maxFileSize, RoundingMode.FLOOR));
        }
    }

    /**
     * A size for a person to read, written as the browser's check writes it: KB under a megabyte, two decimals at
     * most.
     *
     * @param bytes the size
     * @param rounding how to round the last decimal
     * @return the size, such as {@code 50 MB} or {@code 512.5 KB}
     */
    private static String formatSize(final long bytes, final RoundingMode rounding)
    {
        final boolean megabytes = bytes >= BYTES_PER_MEGABYTE;
        return BigDecimal.valueOf(bytes)
            .divide(BigDecimal.valueOf(megabytes ? BYTES_PER_MEGABYTE : BYTES_PER_KILOBYTE), 2, rounding)
            .stripTrailingZeros().toPlainString() + (megabytes ? " MB" : " KB");
    }

    /**
     * Refuses a PDF with more pages than the configured limit. A PDF that cannot be read is let through: the parser
     * refuses it later, with its own reason.
     *
     * @param file the uploaded file
     * @param mimeType the upload's type, as {@link #getMimeType} reads it
     * @throws InvalidPayloadException when the PDF has too many pages
     * @throws PersistenceException when the upload cannot be read
     */
    private void checkPages(final EventAttachment file, final String mimeType)
        throws InvalidPayloadException, PersistenceException
    {
        if (!PDF_TYPE.equals(mimeType)) {
            return;
        }
        final OptionalInt pages;
        try (InputStream content = file.openStream()) {
            pages = PdfPageCounter.countPages(content);
        } catch (final IOException e) {
            throw new PersistenceException("Could not read the uploaded file", e);
        }
        final int maxPages = this.limits.getMaxPdfPages();
        if (pages.isPresent() && pages.getAsInt() > maxPages) {
            throw new InvalidPayloadException(
                "That PDF has " + pages.getAsInt() + " pages, and the limit is " + maxPages);
        }
    }

    /**
     * Stores the uploaded bytes as the {@code uploadedFile} of a {@code sub:File}. The content is streamed into a JCR
     * binary.
     *
     * @param stored the {@code sub:File} node to store the upload under
     * @param file the uploaded file
     * @param mimeType the upload's type, as {@link #getMimeType} reads it
     * @throws PersistenceException when the file cannot be stored
     */
    private void write(final Node stored, final EventAttachment file, final String mimeType)
        throws PersistenceException
    {
        try (InputStream content = file.openStream()) {
            final Node fileNode = stored.addNode(UPLOADED_FILE_NODE, "nt:file");
            final Node resource = fileNode.addNode("jcr:content", "nt:resource");
            resource.setProperty("jcr:data",
                fileNode.getSession().getValueFactory().createBinary(content));
            // The type the file is served back with
            resource.setProperty("jcr:mimeType", Objects.requireNonNullElse(mimeType, GENERIC_TYPE));
        } catch (final RepositoryException | IOException e) {
            throw new PersistenceException("Could not store the uploaded file", e);
        }
    }
}
