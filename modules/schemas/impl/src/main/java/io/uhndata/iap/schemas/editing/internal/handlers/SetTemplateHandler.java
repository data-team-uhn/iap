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
package io.uhndata.iap.schemas.editing.internal.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Gives the target document requirement the file the event uploads as its {@code template}, replacing any it had.
 * For a requirement the submitter answers, the template is a blank to start from; for a
 * {@link DocumentRequirement#isGenerated() generated} one, it is what the platform fills in, so it has to be a
 * {@code .docx}. Removing a template needs no handler of its own: {@code replaceContent} with no content does it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SetTemplateHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "setTemplate";

    /** The payload entry carrying the file. */
    static final String FILE_PARAMETER = "file";

    /** The requirement's child holding the template, as the node type names it. */
    static final String TEMPLATE_NODE = "template";

    /** The largest template taken, which is also the largest the document generator will fill in. */
    static final long MAX_TEMPLATE_BYTES = 10L * 1024 * 1024;

    private static final long MEGABYTE = 1024L * 1024;

    private static final String GENERIC_TYPE = "application/octet-stream";

    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Types a browser may not know and send untyped, by the extension that names them. */
    private static final Map<String, String> TYPE_BY_EXTENSION = Map.of(
        "docx", DOCX_TYPE,
        "doc", "application/msword",
        "odt", "application/vnd.oasis.opendocument.text",
        "pdf", "application/pdf");

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        // The model registers as a Requirement, which picks the subtype by resource type
        if (!target.isResourceType(DocumentRequirement.RESOURCE_TYPE)
            || !(target.adaptTo(Requirement.class) instanceof DocumentRequirement requirement)) {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final EventAttachment file = Payloads.attachment(context.getEvent(), FILE_PARAMETER);
        if (file == null) {
            throw new InvalidPayloadException("No file was uploaded");
        }
        if (file.getSize() > MAX_TEMPLATE_BYTES) {
            // Rounded up, so a file just over the limit does not read as being at it
            throw new InvalidPayloadException("That file is " + (file.getSize() + MEGABYTE - 1) / MEGABYTE
                + " MB, and a template can be at most " + MAX_TEMPLATE_BYTES / MEGABYTE + " MB");
        }
        final String mimeType = getMimeType(file);
        // Refused now rather than when the version is published, where GeneratedDocumentCheck would
        if (requirement.isGenerated() && !DOCX_TYPE.equals(mimeType)) {
            throw new InvalidPayloadException(requirement.getLabel()
                + " is generated from the answers, so its template has to be a Word (.docx) file");
        }
        VersioningUtils.checkOut(target);
        write(Objects.requireNonNull(target.adaptTo(Node.class), "A requirement is always backed by a JCR node"),
            file, mimeType);
    }

    /**
     * Replaces the template with the uploaded file.
     *
     * <p>Written through the JCR API rather than the resolver, because streaming into the {@code jcr:data} binary is
     * what keeps the file out of the heap.</p>
     *
     * @param requirement the requirement's node
     * @param file the uploaded file
     * @param mimeType its type, as {@link #getMimeType} reads it
     * @throws PersistenceException when the file cannot be stored
     */
    private static void write(final Node requirement, final EventAttachment file, final String mimeType)
        throws PersistenceException
    {
        try (InputStream bytes = file.openStream()) {
            if (requirement.hasNode(TEMPLATE_NODE)) {
                requirement.getNode(TEMPLATE_NODE).remove();
            }
            final Node template = requirement.addNode(TEMPLATE_NODE, "nt:file");
            final Node content = template.addNode("jcr:content", "nt:resource");
            content.setProperty("jcr:data", requirement.getSession().getValueFactory().createBinary(bytes));
            // What the generator checks, and what names the blank a submitter downloads
            content.setProperty("jcr:mimeType", mimeType);
        } catch (final RepositoryException | IOException e) {
            throw new PersistenceException("Could not store the template", e);
        }
    }

    /**
     * The upload's type, lower-case and without parameters. Where the caller sent no type or only the generic one,
     * the file's extension decides, since browsers often do not know the Office types.
     *
     * @param file the uploaded file
     * @return the type, the generic one when nothing better is known
     */
    private static String getMimeType(final EventAttachment file)
    {
        final String declared = Objects.toString(file.getMimeType(), "").split(";", 2)[0].trim()
            .toLowerCase(Locale.ROOT);
        if (!declared.isEmpty() && !GENERIC_TYPE.equals(declared)) {
            return declared;
        }
        final String name = Objects.toString(file.getFileName(), "");
        final String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return TYPE_BY_EXTENSION.getOrDefault(extension, GENERIC_TYPE);
    }
}
