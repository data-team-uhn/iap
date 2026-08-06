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
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
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

    @OSGiService
    private ConditionEvaluator conditionEvaluator;

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String schemaVersion;

    @ValueMapValue
    private List<String> tags;

    /**
     * The title of the submission.
     *
     * @return a title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * The schema version this submission answers.
     *
     * @return a schema version
     */
    @NotNull
    public SchemaVersion getSchemaVersion()
    {
        return Objects.requireNonNull(this.getReference(this.schemaVersion, SchemaVersion.class),
            "Missing mandatory schemaVersion reference");
    }

    /**
     * The tags explicitly placed on this submission. The lifecycle state, managed by the attached user workflow,
     * is one of them: a {@code lifecycle}-category tag like {@code draft} or {@code in-review}, defined under
     * {@code /Tags}.
     *
     * @return the tag names, an empty list if there are none
     */
    @NotNull
    public List<String> getTags()
    {
        return this.tags == null ? List.of() : this.tags;
    }

    /**
     * The submitter's answers to the schema questions.
     *
     * @return a list of answers, empty if none
     */
    @NotNull
    public List<Answer> getAnswers()
    {
        return this.getChildren(Answer.RESOURCE_TYPE, Answer.class);
    }

    /**
     * The documents attached to this submission.
     *
     * @return a list of documents, empty if none
     */
    @NotNull
    public List<Document> getDocuments()
    {
        return this.getChildren(Document.RESOURCE_TYPE, Document.class);
    }

    /**
     * The reviews added by reviewers.
     *
     * @return a list of reviews, empty if none
     */
    @NotNull
    public List<Review> getReviews()
    {
        return this.getChildren(Review.RESOURCE_TYPE, Review.class);
    }

    /**
     * Whether this submission has been approved, i.e. it carries the {@code approved} lifecycle tag (set by the
     * attached user workflow).
     *
     * @return {@code true} if approved
     */
    public boolean isApproved()
    {
        return this.getTags().contains("approved");
    }

    /**
     * Every unresolved comment raised across all of this submission's reviews.
     *
     * @return a list of unresolved review comments, empty if none
     */
    @NotNull
    public List<ReviewComment> getUnresolvedComments()
    {
        return this.getReviews().stream()
            .flatMap(review -> review.getUnresolvedComments().stream())
            .collect(Collectors.toList());
    }

    /**
     * The requirements of this submission's schema version that haven't been fulfilled yet: a
     * {@code DocumentRequirement} with no attached {@link Document}, an {@code ApprovalRequirement} with no
     * approved {@link Review}, or a {@code FormRequirement} with unanswered questions. Requirements, sections and
     * questions whose condition doesn't currently hold for this submission don't apply, so they are never
     * reported as missing.
     *
     * @return a list of unfulfilled requirements, empty if none are missing
     */
    @NotNull
    public List<Requirement> getMissingRequirements()
    {
        return this.getSchemaVersion().getRequirements().stream()
            .filter(this::applies)
            .filter(requirement -> !this.isFulfilled(requirement))
            .collect(Collectors.toList());
    }

    private boolean applies(final Conditionable item)
    {
        return this.conditionEvaluator == null || this.conditionEvaluator.applies(item, this);
    }

    private boolean isFulfilled(final Requirement requirement)
    {
        if (requirement instanceof DocumentRequirement) {
            // The reference is resolved into a local, both because resolving it twice would repeat the whole
            // reference lookup, and because the null check wouldn't apply to a second, separate call
            return this.getDocuments().stream().anyMatch(document -> {
                final Requirement fulfilled = document.getFulfills();
                return fulfilled != null && requirement.getPath().equals(fulfilled.getPath());
            });
        }
        if (requirement instanceof ApprovalRequirement) {
            return this.getReviews().stream().anyMatch(review -> {
                if (!review.isApproved()) {
                    return false;
                }
                final Requirement reviewed = review.getRequirement();
                return reviewed != null && requirement.getPath().equals(reviewed.getPath());
            });
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
        // An item whose condition doesn't hold is not presented to the submitter, so it (and,
        // for a section, everything inside it) doesn't need an answer.
        if (!this.applies(item)) {
            return;
        }
        // Section is the only other concrete item type today.
        if (item instanceof Question) {
            result.add((Question) item);
        } else {
            ((Section) item).getChildren().forEach(child -> this.collectQuestions(child, result));
        }
    }

    private boolean isAnswered(final Question question)
    {
        return this.getAnswers().stream().anyMatch(answer -> {
            final Question answered = answer.getQuestion();
            if (answered == null || !question.getPath().equals(answered.getPath())) {
                return false;
            }
            // Only read once the question matched: every call resolves the reference and copies the value array
            final String[] value = answer.getValue();
            return value != null && value.length > 0;
        });
    }
}
