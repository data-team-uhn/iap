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
package io.uhndata.iap.submissions.models;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;

/**
 * A Sling Model wrapping a {@code sub:Submission} node, a submission filed by a submitter against a specific
 * schema version. It holds the submitter's answers to the schema questions, the attached documents, and the
 * reviews added by reviewers.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Submission.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Submission extends Entity
{
    /** The {@code sling:resourceType} of a {@code sub:Submission} node. */
    public static final String RESOURCE_TYPE = "sub/Submission";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String schemaVersion;

    @ValueMapValue
    private String status;

    /**
     * The title of the submission.
     *
     * @return a title
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * The schema version this submission answers.
     *
     * @return a schema version, or {@code null} if not set or unresolvable
     */
    public SchemaVersion getSchemaVersion()
    {
        return this.getReference(this.schemaVersion, SchemaVersion.class);
    }

    /**
     * The current lifecycle state of the submission, managed by the attached user workflow.
     *
     * @return a status name, e.g. {@code draft} or {@code in-review}
     */
    public String getStatus()
    {
        return this.status;
    }

    /**
     * The submitter's answers to the schema questions.
     *
     * @return a list of answers, empty if none
     */
    public List<Answer> getAnswers()
    {
        return this.getChildren(Answer.RESOURCE_TYPE, Answer.class);
    }

    /**
     * The documents attached to this submission.
     *
     * @return a list of documents, empty if none
     */
    public List<Document> getDocuments()
    {
        return this.getChildren(Document.RESOURCE_TYPE, Document.class);
    }

    /**
     * The reviews added by reviewers.
     *
     * @return a list of reviews, empty if none
     */
    public List<Review> getReviews()
    {
        return this.getChildren(Review.RESOURCE_TYPE, Review.class);
    }

    /**
     * Whether this submission has been approved, i.e. its lifecycle state (set by the attached user workflow) is
     * {@code approved}.
     *
     * @return {@code true} if approved
     */
    public boolean isApproved()
    {
        return "approved".equals(this.status);
    }

    /**
     * Every unresolved comment raised across all of this submission's reviews.
     *
     * @return a list of unresolved review comments, empty if none
     */
    public List<ReviewComment> getUnresolvedComments()
    {
        return this.getReviews().stream()
            .flatMap(review -> review.getUnresolvedComments().stream())
            .collect(Collectors.toList());
    }

    /**
     * The requirements of this submission's schema version that haven't been fulfilled yet: a
     * {@code DocumentRequirement} with no attached {@link Document}, an {@code ApprovalRequirement} with no
     * approved {@link Review}, or a {@code FormRequirement} with unanswered questions. Does not (yet) take a
     * requirement's own condition into account, so a requirement that doesn't actually apply to this submission may
     * still be reported as missing.
     *
     * @return a list of unfulfilled requirements, empty if none are missing or the schema version is unresolvable
     */
    public List<Requirement> getMissingRequirements()
    {
        final SchemaVersion version = this.getSchemaVersion();
        if (version == null) {
            return List.of();
        }
        return version.getRequirements().stream()
            .filter(requirement -> !this.isFulfilled(requirement))
            .collect(Collectors.toList());
    }

    private boolean isFulfilled(final Requirement requirement)
    {
        if (requirement instanceof DocumentRequirement) {
            return this.getDocuments().stream()
                .anyMatch(document -> document.getFulfills() != null
                    && requirement.getPath().equals(document.getFulfills().getPath()));
        }
        if (requirement instanceof ApprovalRequirement) {
            return this.getReviews().stream()
                .anyMatch(review -> review.isApproved() && review.getRequirement() != null
                    && requirement.getPath().equals(review.getRequirement().getPath()));
        }
        // FormRequirement is the only other concrete requirement type today.
        return this.getQuestionsOf((FormRequirement) requirement).stream().allMatch(this::isAnswered);
    }

    private List<Question> getQuestionsOf(final FormRequirement form)
    {
        final List<Question> result = new ArrayList<>();
        form.getChildren().forEach(item -> this.collectQuestions(item, result));
        return result;
    }

    private void collectQuestions(final FormItem item, final List<Question> result)
    {
        // Section is the only other concrete item type today.
        if (item instanceof Question) {
            result.add((Question) item);
        } else {
            ((Section) item).getChildren().forEach(child -> this.collectQuestions(child, result));
        }
    }

    private boolean isAnswered(final Question question)
    {
        return this.getAnswers().stream()
            .anyMatch(answer -> answer.getQuestion() != null
                && question.getPath().equals(answer.getQuestion().getPath())
                && answer.getValue() != null && answer.getValue().length > 0);
    }
}
