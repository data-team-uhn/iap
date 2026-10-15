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
package io.uhndata.iap.submissions.internal.evaluators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.internal.ApplicableItems;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;

/**
 * Judges a submission's documents. If a document requirement that applies and is mandatory doesn't have an attached
 * file, it is incomplete.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CompletenessEvaluator.class)
public class DocumentCompletenessEvaluator implements CompletenessEvaluator
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentCompletenessEvaluator.class);

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public List<Resource> evaluate(final Resource submission)
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission is judged");
        final Map<String, List<Document>> documents = getDocuments(model);
        final List<Resource> incomplete = new ArrayList<>();
        for (final DocumentRequirement requirement : ApplicableItems.getDocumentRequirements(model, this.conditions)) {
            final List<Document> existing = documents.get(requirement.getPath());
            if (existing == null) {
                LOGGER.warn("{} has no document for {}, it should have been automatically created", submission.getPath(),
                    requirement.getPath());
                // A problem rather than a failure: a part creator left an item without its part
                ErrorLogger.logProblem("A submission has no document for a requirement",
                    ErrorContext.of(DocumentCompletenessEvaluator.class, "evaluate")
                        .about(submission.getPath())
                        .with("requirement", requirement.getPath()));
            }
            if (requirement.isRequired() && (existing == null || existing.stream().noneMatch(Document::isAttached))) {
                incomplete.addAll(existing == null ? List.of(submission)
                    : toResources(existing, submission.getResourceResolver()));
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
    private static Map<String, List<Document>> getDocuments(final Submission submission)
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

    private static List<Resource> toResources(final List<Document> documents, final ResourceResolver resolver)
    {
        return documents.stream()
            .map(document -> Objects.requireNonNull(resolver.getResource(document.getPath()),
                "A listed document exists"))
            .collect(Collectors.toList());
    }
}
