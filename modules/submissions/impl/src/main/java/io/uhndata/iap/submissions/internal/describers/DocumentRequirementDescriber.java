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

import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.mime.MimeTypeService;
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
 * What a document requirement adds to a form: which types it takes, the blank to start from if it offers one, and
 * what has already been attached for it.
 *
 * <p>An upload control cannot be drawn without all three. They come with the form because only the form says which
 * requirements apply now. Read off the schema, they would offer a control for something this submission is not
 * asked.</p>
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

    @Reference
    private MimeTypeService mimeTypes;

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof DocumentRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        final DocumentRequirement document = (DocumentRequirement) requirement;
        // Stated always, not only when false: the upload control marks the optional case, and should do so
        // because the form said so rather than because a key was missing
        json.add(REQUIRED_KEY, document.isRequired());
        final JsonArrayBuilder accepted = Json.createArrayBuilder();
        // Stated always: an empty list means any type is accepted
        document.getAcceptedFileTypes().forEach(accepted::add);
        json.add(ACCEPTED_FILE_TYPES_KEY, accepted);
        final Resource template = document.getTemplate();
        if (template != null) {
            json.add(TEMPLATE_KEY, template.getPath());
            json.add(TEMPLATE_NAME_KEY, getTemplateFileName(document, template));
        }
        // Named rather than counted, so that a form reopened later says which document is there. Without this an
        // upload control looks the same before and after, and the way to check would be to leave the page
        final JsonArrayBuilder attached = Json.createArrayBuilder();
        submission.getDocuments().stream()
            .filter(Document::isAttached)
            .filter(attachment -> attachment.isFulfilling(document))
            .map(DocumentRequirementDescriber::describeAttachment)
            .forEach(attached::add);
        json.add(ATTACHED_KEY, attached);
    }

    /**
     * One attached document: its title, and the path of its newest file, which is where it downloads from.
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
        return json;
    }

    /**
     * The name a downloaded template is saved under. The node is always called {@code template}, so the name is
     * the requirement's label plus the extension of the template's type.
     *
     * @param requirement the requirement offering the template
     * @param template the template file
     * @return a file name
     */
    private String getTemplateFileName(final DocumentRequirement requirement, final Resource template)
    {
        final String label = Objects.toString(requirement.getLabel(), "");
        final String base = label.isBlank() ? requirement.getName() : label;
        final String mimeType = template.getValueMap().get("jcr:content/jcr:mimeType", String.class);
        final String extension = mimeType == null ? null : this.mimeTypes.getExtension(mimeType);
        return extension == null ? base : base + "." + extension;
    }
}
