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
package io.uhndata.iap.submissions.internal.parts;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.PartCreator;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;

/**
 * Gives every document requirement that applies a document, empty until something is uploaded into it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = PartCreator.class)
public class DocumentPartCreator implements PartCreator
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public void createMissing(final Resource submission) throws PersistenceException
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission has parts");
        final Set<String> fulfilled = model.getDocuments().stream()
            .map(Document::getFulfills)
            .filter(Objects::nonNull)
            .map(Requirement::getPath)
            .collect(Collectors.toSet());
        for (final DocumentRequirement requirement : ApplicableItems.getDocumentRequirements(model, this.conditions)) {
            if (!fulfilled.contains(requirement.getPath())) {
                createDocument(submission, requirement);
            }
        }
    }

    /**
     * Creates the empty document for a requirement.
     *
     * @param submission the submission
     * @param requirement the requirement
     * @throws PersistenceException when it cannot be written
     */
    private static void createDocument(final Resource submission, final DocumentRequirement requirement)
        throws PersistenceException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        VersioningUtils.checkOut(submission);
        final Resource document = resolver.create(submission, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Document"));
        ReferenceUtils.setReference(document, "fulfills", requirement);
    }
}
