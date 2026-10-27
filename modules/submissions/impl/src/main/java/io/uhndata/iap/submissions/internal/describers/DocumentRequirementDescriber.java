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
package io.uhndata.iap.submissions.internal.describers;

import java.util.Calendar;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.mime.MimeTypeService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

/**
 * What a document requirement adds to a form: whether it is required, the types it takes, its blank if it offers
 * one, and what is already attached. An upload control cannot be drawn without them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class DocumentRequirementDescriber implements RequirementDescriber
{
    private static final String ACCEPTED_FILE_TYPES_KEY = "acceptedFileTypes";

    private static final String TEMPLATE_KEY = "template";

    private static final String TEMPLATE_NAME_KEY = "templateName";

    private static final String ATTACHED_KEY = "attached";

    private static final String REQUIRED_KEY = "required";

    private static final String GENERATED_KEY = "generated";

    private static final String OUTDATED_KEY = "outdated";

    private static final String DOCX_KEY = "docx";

    /** Stamped by the save workflow when it changes an answer, see SaveAnswersHandler. */
    private static final String LAST_MODIFIED = "jcr:lastModified";

    private final MimeTypeService mimeTypes;

    /**
     * Constructor.
     *
     * @param mimeTypes gives the file extension a downloaded template is saved with
     */
    @Activate
    public DocumentRequirementDescriber(@Reference final MimeTypeService mimeTypes)
    {
        this.mimeTypes = mimeTypes;
    }

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof DocumentRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        final DocumentRequirement asked = (DocumentRequirement) requirement;
        // Stated always, not only when false
        json.add(REQUIRED_KEY, asked.isRequired());
        // Stated always, for the same reason: the form offers to generate a generated document, as well as to upload
        json.add(GENERATED_KEY, asked.isGenerated());
        // Until submitting is a workflow step (IAP-88): generated while the request is still being answered, a
        // document can fall behind the answers it was filled in from
        if (asked.isGenerated()) {
            json.add(OUTDATED_KEY, isOutdated(asked, submission));
        }
        json.add(ACCEPTED_FILE_TYPES_KEY, acceptedFileTypes(asked));
        addTemplate(json, asked);
        json.add(ATTACHED_KEY, attachments(asked, submission));
    }

    /**
     * The file types a requirement takes.
     *
     * @param asked the document requirement
     * @return the MIME types it accepts; empty when it accepts any type
     */
    private static JsonArrayBuilder acceptedFileTypes(final DocumentRequirement asked)
    {
        final JsonArrayBuilder accepted = Json.createArrayBuilder();
        asked.getAcceptedFileTypes().forEach(accepted::add);
        return accepted;
    }

    /**
     * Adds the blank a requirement offers to start from, if it offers one: where it downloads from, and the name it
     * is saved under.
     *
     * @param json the requirement's object in the form's JSON
     * @param asked the document requirement
     */
    private void addTemplate(final JsonObjectBuilder json, final DocumentRequirement asked)
    {
        final Resource template = asked.getTemplate();
        // A generated document's template is what the platform fills in, not a blank for the submitter
        if (template != null && !asked.isGenerated()) {
            json.add(TEMPLATE_KEY, template.getPath());
            json.add(TEMPLATE_NAME_KEY, getTemplateFileName(asked, template));
        }
    }

    /**
     * What has been attached for a requirement, named, so that a reopened form says which document is there.
     *
     * @param asked the document requirement
     * @param submission the submission holding the documents
     * @return one object per attached document fulfilling the requirement; empty when none is attached
     */
    private static JsonArrayBuilder attachments(final DocumentRequirement asked, final Submission submission)
    {
        final JsonArrayBuilder attached = Json.createArrayBuilder();
        submission.getDocuments().stream()
            .filter(Document::isAttached)
            .filter(document -> document.isFulfilling(asked))
            .map(DocumentRequirementDescriber::describeAttachment)
            .forEach(attached::add);
        return attached;
    }

    /**
     * Whether an answer changed after the newest generation of a generated document. Until submitting is a workflow
     * step (IAP-88).
     *
     * @param asked the generated requirement
     * @param submission the submission whose answers it was filled in from
     * @return {@code false} when nothing was generated yet, which is missing rather than outdated, or when the newest
     *         version is one the submitter uploaded, which is theirs whatever it says
     */
    private static boolean isOutdated(final DocumentRequirement asked, final Submission submission)
    {
        final Optional<Calendar> generated = submission.getDocuments().stream()
            .filter(document -> document.isFulfilling(asked))
            .filter(document -> editableOf(document).isPresent())
            .map(Document::getCurrentVersion)
            .map(DocumentVersion::getCreated)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder());
        return generated.isPresent() && submission.getAnswers().stream()
            .map(answer -> Optional.ofNullable(answer.get(LAST_MODIFIED, Calendar.class)).orElse(answer.getCreated()))
            .filter(Objects::nonNull)
            .anyMatch(changed -> changed.after(generated.get()));
    }

    /**
     * One attached document: its title, the path of its newest file, which is where it downloads from, and, when the
     * platform generated that file, the path of the {@code .docx} it was filled in as, for the submitter to edit.
     *
     * @param document the attached document
     * @return the document's JSON, without a path when no file has landed yet
     */
    private static JsonObjectBuilder describeAttachment(final Document document)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(FormKeys.TITLE_KEY, Objects.toString(document.getTitle(), document.getName()));
        Optional.ofNullable(document.getCurrentVersion())
            .map(DocumentVersion::getFile)
            .map(File::getUploadedFile)
            .ifPresent(file -> json.add(FormKeys.PATH_KEY, file.getPath()));
        editableOf(document).ifPresent(docx -> json.add(DOCX_KEY, docx.getPath()));
        return json;
    }

    /**
     * The filled {@code .docx} beside a document's newest file, which only a generated version has.
     *
     * @param document the document
     * @return the {@code .docx}, or empty when the newest version is an upload, or there is none
     */
    private static Optional<Resource> editableOf(final Document document)
    {
        return Optional.ofNullable(document.getCurrentVersion())
            .map(DocumentVersion::getFile)
            .map(File::getGeneratedDocx);
    }

    /**
     * The name a downloaded template is saved under. The node is always called {@code template}, so the name is
     * the requirement's label plus the extension of the template's type.
     *
     * @param asked the requirement offering the template
     * @param template the template file
     * @return a file name, such as {@code Consent form.pdf}; the bare label when the type has no known extension
     */
    private String getTemplateFileName(final DocumentRequirement asked, final Resource template)
    {
        final String label = Objects.toString(asked.getLabel(), "");
        final String base = label.isBlank() ? asked.getName() : label;
        final String mimeType = template.getValueMap().get("jcr:content/jcr:mimeType", String.class);
        final String extension = mimeType == null ? null : this.mimeTypes.getExtension(mimeType);
        return extension == null ? base : base + "." + extension;
    }
}
