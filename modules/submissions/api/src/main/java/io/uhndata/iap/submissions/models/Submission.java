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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.models.WorkflowInstance;
import io.uhndata.iap.workflows.models.WorkflowInstances;

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

    /** The {@code lifecycle} tag a submission carries until it is submitted. */
    public static final String DRAFT_TAG = "draft";

    /** The {@code lifecycle} tag a submission carries once the reviewers have accepted it. */
    public static final String APPROVED_TAG = "approved";

    @OSGiService
    private ConditionEvaluator conditionEvaluator;

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String schemaVersion;

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
        return Objects.requireNonNull(this.findSchemaVersion(), "Missing mandatory schemaVersion reference");
    }

    /**
     * The schema version this submission answers, where this session can reach it.
     *
     * <p>{@link #getSchemaVersion()} states the node type's rule, that the reference is mandatory, and that
     * rule is about the content rather than about every reader: a session denied the version resolves
     * nothing. Ask this where the caller has to be answered rather than failed.</p>
     *
     * @return the schema version, or {@code null} where this session cannot resolve the reference
     * @since 0.1.0
     */
    @Nullable
    public SchemaVersion findSchemaVersion()
    {
        return this.getReference(this.schemaVersion, SchemaVersion.class);
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
     * The workflows running over this submission, held in the container the {@code wf:WorkflowAttachable} mixin
     * autocreates. Several may run at once, which is why this is a list rather than a single lifecycle.
     *
     * @return a list of workflow instances, empty if none has ever been started
     */
    @NotNull
    public List<WorkflowInstance> getWorkflowInstances()
    {
        // Type-checked, because the node type accepts arbitrary children too. The name alone does not say
        // that what it finds is the container, and an unrelated node by that name would still adapt to the model
        final WorkflowInstances container = this.getChild(WorkflowInstances.NODE_NAME,
            WorkflowInstances.RESOURCE_TYPE, WorkflowInstances.class);
        return container == null ? List.of() : container.getInstances();
    }

    /**
     * Whether this submission has been approved, i.e. it carries the {@code approved} lifecycle tag (set by the
     * attached user workflow).
     *
     * @return {@code true} if approved, {@code false} also when the tags service is unavailable
     */
    public boolean isApproved()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(APPROVED_TAG);
    }

    /**
     * Whether this submission is still being written, i.e. it carries the {@code draft} lifecycle tag. A
     * submission that has moved on is read-only to its submitter, so this is what an editor asks before
     * offering to edit.
     *
     * @return {@code true} while it is a draft, {@code false} also when the tags service is unavailable
     */
    public boolean isDraft()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(DRAFT_TAG);
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
     * Whether a requirement, section or question is asked of this submission, i.e. its condition holds for it.
     *
     * @param item the requirement, section or question
     * @return {@code true} if its condition holds or it has none, also when the condition service is unavailable
     */
    public boolean isApplicable(@NotNull final Conditionable item)
    {
        return this.conditionEvaluator == null || this.conditionEvaluator.applies(item, this);
    }

    /**
     * What has been answered, by the path of the question each answer is for.
     *
     * <p>Both the completeness of a question and the form shown to the submitter are read from this, so the two count
     * the same answers. An answer whose question no longer resolves is left out. When two answers are for
     * the same question, the one holding values wins.</p>
     *
     * @return the values given, by question path; empty for a submission nobody has answered
     */
    @NotNull
    public Map<String, List<String>> getAnswersByQuestion()
    {
        final Map<String, List<String>> byQuestion = new HashMap<>();
        for (final Answer answer : this.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question == null) {
                continue;
            }
            final List<String> value = List.of(Objects.requireNonNullElse(answer.getValue(), new String[0]));
            final List<String> known = byQuestion.get(question.getPath());
            if (known == null || known.isEmpty()) {
                byQuestion.put(question.getPath(), value);
            }
        }
        return byQuestion;
    }
}
