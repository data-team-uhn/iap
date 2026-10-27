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

import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.documents.generation.api.DocumentGenerator;
import io.uhndata.iap.documents.generation.api.GeneratedDocument;
import io.uhndata.iap.documents.generation.api.GenerationResult;
import io.uhndata.iap.documents.generation.api.GenerationResult.Status;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that fills in a {@link DocumentRequirement#isGenerated() generated} document from the submission,
 * and attaches it as the requirement's document. The activity names the requirement in its {@code requirement}
 * property, by name or by path.
 *
 * <p>Until submitting is a workflow step (IAP-88), the submitter asks for the document from the form instead: an
 * activity naming no requirement takes it from the event, and only from somebody who may still change the request.
 * </p>
 *
 * <p>The result is stored as an upload is, so every reader of a submission's documents treats it alike: the PDF is
 * the file's {@code uploadedFile}, and the filled {@code .docx} sits beside it as {@link File#getGeneratedDocx()}. Generating
 * again adds a version rather than replacing one, so the PDF the submitter was shown stays where it was.</p>
 *
 * <p>A requirement whose condition does not hold for this submission is not asked, so nothing is generated for it.
 * Any failure to generate fails the step, with the generator's reason, rather than leaving the requirement looking
 * answered.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class GenerateDocumentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "generateDocument";

    /** The variable holding the path of the stored PDF, for the nodes downstream and the channel. */
    public static final String GENERATED_PATH_VARIABLE = "generatedDocumentPath";

    /** The activity property naming the requirement to fill in. */
    static final String REQUIREMENT_PARAMETER = "requirement";

    private final DocumentGenerator generator;

    /**
     * Create the handler.
     *
     * @param generator what fills the templates in
     */
    @Activate
    public GenerateDocumentHandler(@Reference final DocumentGenerator generator)
    {
        this.generator = generator;
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
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "The generate step only applies to submissions");
        final DocumentRequirement requirement = requirement(submission, context);
        if (!submission.isApplicable(requirement)) {
            return;
        }
        final Resource template = requirement.getTemplate();
        if (template == null) {
            throw new WorkflowFailedException(requirement.getLabel() + " has no template to be generated from");
        }

        // Generate it
        final GenerationResult result = this.generator.generate(template, target);
        final GeneratedDocument generated = result.getDocument();
        if (generated == null) {
            // Busy or down; asking again later may work
            throw new WorkflowFailedException(result.getStatus() == Status.UNAVAILABLE
                ? requirement.getLabel() + " could not be generated right now, as the document service is busy or "
                    + "unreachable. Try again in a moment."
                : requirement.getLabel() + " could not be generated: " + result.getMessage());
        }

        // Store it as the requirement's document
        VersioningUtils.checkOut(target);
        final ResourceResolver resolver = context.getResourceResolver();
        final Resource document = StoredDocuments.findOrCreateDocument(target, submission, requirement,
            requirement.getLabel() + ".pdf", resolver);
        final Resource stored = StoredDocuments.addVersion(document, resolver);
        StoredDocuments.write(stored, StoredDocuments.UPLOADED_FILE_NODE, generated.openPdf(),
            GeneratedDocument.PDF_MIME_TYPE);
        StoredDocuments.write(stored, File.GENERATED_DOCX_CHILD, generated.openDocx(),
            GeneratedDocument.DOCX_MIME_TYPE);
        context.setVariable(GENERATED_PATH_VARIABLE, stored.getPath() + "/" + StoredDocuments.UPLOADED_FILE_NODE);
    }

    /**
     * The generated requirement the activity names, which has to be one this submission's own schema asks for.
     *
     * @param submission the submission being generated for
     * @param context the executing task's context
     * @return the requirement
     * @throws WorkflowDefinitionException when the activity's {@code requirement} is not a name, or names one this
     *             submission's schema does not have, or one that is not generated; the definition is wrong, not the
     *             request
     * @throws WorkflowException when the activity names none and the event's request is refused, see
     *             {@link #requested}
     */
    private static DocumentRequirement requirement(final Submission submission, final WorkflowTaskContext context)
        throws WorkflowException
    {
        final Object named = context.getActivity().get(REQUIREMENT_PARAMETER);
        if (named == null) {
            return requested(submission, context);
        }
        if (!(named instanceof String name) || name.isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not say which requirement to generate");
        }
        final DocumentRequirement requirement = AttachDocumentHandler.findRequirement(submission, name)
            .orElseThrow(() -> new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " names " + name + ", which is not a document requirement of this request's schema"));
        if (!requirement.isGenerated()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " names " + requirement.getLabel() + ", which is not generated");
        }
        return requirement;
    }

    /**
     * The generated requirement the submitter asked for. Until submitting is a workflow step (IAP-88).
     *
     * @param submission the submission being generated for
     * @param context the executing task's context
     * @return the requirement
     * @throws WorkflowException when the actor may not change the request, or the event names no generated
     *             requirement that this request is asked
     */
    private static DocumentRequirement requested(final Submission submission, final WorkflowTaskContext context)
        throws WorkflowException
    {
        SaveAnswersHandler.checkMayEdit(submission, context.getActor());
        final String named = Payloads.requireText(context.getEvent(), REQUIREMENT_PARAMETER,
            "The request does not say which document to generate");
        return AttachDocumentHandler.findRequirement(submission, named)
            .filter(submission::isApplicable)
            .filter(DocumentRequirement::isGenerated)
            .orElseThrow(() -> new InvalidPayloadException(
                "There is no generated document " + named + " in this request"));
    }
}
