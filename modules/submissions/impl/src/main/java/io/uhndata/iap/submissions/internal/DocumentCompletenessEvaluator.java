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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;

/**
 * Judges a submission's documents. Every document requirement that applies has at least one document, empty until
 * something is uploaded into it, and its empty documents are incomplete while the requirement is required and
 * nothing has been uploaded for it.
 *
 * <p>An empty document for a requirement that has stopped applying is removed. One holding a file is kept.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class DocumentCompletenessEvaluator implements CompletenessEvaluator
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public List<Resource> evaluate(final Resource submission) throws PersistenceException
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        final Map<String, List<Document>> documents = documents(model);
        final List<Resource> incomplete = new ArrayList<>();
        final List<DocumentRequirement> asked = model.getSchemaVersion().getRequirements().stream()
            .filter(DocumentRequirement.class::isInstance)
            .map(DocumentRequirement.class::cast)
            .filter(requirement -> this.conditions.applies(requirement, model))
            .collect(Collectors.toList());
        for (final DocumentRequirement requirement : asked) {
            final List<Document> existing = documents.remove(requirement.getPath());
            if (existing == null) {
                final Resource created = create(submission, requirement);
                if (requirement.isRequired()) {
                    incomplete.add(created);
                }
            } else if (requirement.isRequired() && existing.stream().noneMatch(Document::isAttached)) {
                incomplete.addAll(resources(existing, submission.getResourceResolver()));
            }
        }
        // What is left is filed against a requirement no longer asked
        for (final Document stale : documents.values().stream().flatMap(List::stream).collect(Collectors.toList())) {
            if (!stale.isAttached()) {
                VersioningUtils.checkOut(submission);
                submission.getResourceResolver().delete(Objects.requireNonNull(
                    submission.getResourceResolver().getResource(stale.getPath()), "A listed document exists"));
            }
        }
        return incomplete;
    }

    /**
     * The submission's documents, by the path of the requirement each one fulfills. A document fulfilling nothing
     * that resolves is left out.
     *
     * @param submission the submission
     * @return the documents, by requirement path
     */
    private static Map<String, List<Document>> documents(final Submission submission)
    {
        final Map<String, List<Document>> byRequirement = new HashMap<>();
        for (final Document document : submission.getDocuments()) {
            final Requirement fulfilled = document.getFulfills();
            if (fulfilled != null) {
                byRequirement.computeIfAbsent(fulfilled.getPath(), path -> new ArrayList<>()).add(document);
            }
        }
        return byRequirement;
    }

    private static List<Resource> resources(final List<Document> documents, final ResourceResolver resolver)
    {
        return documents.stream()
            .map(document -> Objects.requireNonNull(resolver.getResource(document.getPath()),
                "A listed document exists"))
            .collect(Collectors.toList());
    }

    /**
     * Creates the empty document for a requirement nothing has been uploaded for.
     *
     * @param submission the submission
     * @param requirement the requirement
     * @return the document created
     * @throws PersistenceException when it cannot be written
     */
    private static Resource create(final Resource submission, final DocumentRequirement requirement)
        throws PersistenceException
    {
        final ResourceResolver resolver = submission.getResourceResolver();
        VersioningUtils.checkOut(submission);
        final Resource document = resolver.create(submission, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", "sub:Document"));
        ReferenceUtils.setReference(document, "fulfills", Objects.requireNonNull(
            resolver.getResource(requirement.getPath()), "A requirement the schema lists can be read"));
        return document;
    }
}
