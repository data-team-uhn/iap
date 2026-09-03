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
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
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

    /** The {@code lifecycle} tag a submission carries once the reviewers have accepted it. */
    public static final String APPROVED_TAG = "approved";

    /** The {@code lifecycle} tag a submission carries while it is still being filled in. */
    public static final String DRAFT_TAG = "draft";

    @OSGiService
    private ConditionEvaluator conditionEvaluator;

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String schemaVersion;

    @ValueMapValue
    private String schema;

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
     * The schema this submission answers, whichever of its versions it was raised against.
     *
     * <p>Held on the submission rather than reached through {@link #getSchemaVersion()}, because "everything
     * submitted against this schema" is the ordinary question and reaching it through the version makes every
     * such query a join. Both are written when the submission is created, so they cannot disagree.</p>
     *
     * @return the schema
     */
    @NotNull
    public Schema getSchema()
    {
        return Objects.requireNonNull(this.getReference(this.schema, Schema.class),
            "Missing mandatory schema reference");
    }

    /**
     * The values answering a particular question, which is named by the tail of its path.
     *
     * <p>A suffix rather than a whole path because a question's path names the schema version it belongs to, and
     * the caller almost always means "this question, in whichever version this submission answers" — a rule
     * written against one version's path stops working the day a second one is published.</p>
     *
     * @param questionPathSuffix the end of the path of the question to read, e.g. {@code /details/startDate}
     * @return the values given, empty when the question is unanswered or not asked here
     */
    @NotNull
    public List<String> getAnswersTo(@NotNull final String questionPathSuffix)
    {
        return this.getAnswersByQuestion().entrySet().stream()
            .filter(answer -> answer.getKey().endsWith(questionPathSuffix))
            .map(Map.Entry::getValue)
            .flatMap(List::stream)
            .filter(value -> !value.isBlank())
            .toList();
    }

    /**
     * The single value answering a particular question, for the questions that take one.
     *
     * @param questionPathSuffix the end of the path of the question to read, e.g. {@code /details/startDate}
     * @return the first value given, or {@code null} when the question is unanswered or not asked here
     */
    @Nullable
    public String getAnswerTo(@NotNull final String questionPathSuffix)
    {
        return this.getAnswersTo(questionPathSuffix).stream().findFirst().orElse(null);
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
     * The reviews recorded against one requirement, in the order they were added.
     *
     * <p>A review that names no requirement is about the submission as a whole and is not returned here, and one
     * naming a requirement of some other schema version is not either: the comparison is by path, so a
     * requirement that was replaced does not collect the reviews of the one that replaced it.</p>
     *
     * @param requirement the requirement to collect the reviews of
     * @return the reviews about it, empty when nobody has reviewed it
     */
    @NotNull
    public List<Review> getReviewsOf(@NotNull final Requirement requirement)
    {
        return this.getReviews().stream()
            .filter(review -> {
                // Resolved into a local: the reference lookup is not free, and a null check would not apply to a
                // second, separate call
                final Requirement reviewed = review.getRequirement();
                return reviewed != null && requirement.getPath().equals(reviewed.getPath());
            })
            .collect(Collectors.toList());
    }

    /**
     * The workflows running over this submission, held in the container the {@code wf:WorkflowAttachable} mixin
     * autocreates. Several may run at once — a review process and a periodic reminder, say — which is why this is
     * a list rather than a single lifecycle.
     *
     * @return a list of workflow instances, empty if none has ever been started
     */
    @NotNull
    public List<WorkflowInstance> getWorkflowInstances()
    {
        // Type-checked: the node type accepts arbitrary children too, so the name alone does not say that what it
        // finds is the container, and an unrelated node by that name would still adapt to the model
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
     * Whether this submission is still being filled in, i.e. it carries the {@code draft} lifecycle tag. What a
     * submitter may still change about their own request is decided by this, so it is asked of the submission
     * rather than of whoever is looking at it.
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
     * The requirements of this submission's schema version that haven't been fulfilled yet: a <em>required</em>
     * {@code DocumentRequirement} with no attached {@link Document}, an {@code ApprovalRequirement} with no
     * approved {@link Review}, or a {@code FormRequirement} with a question given fewer answers than its
     * {@code minAnswers} demands. An optional question left blank fulfils it just as well. Requirements, sections
     * and questions whose condition doesn't currently hold for this submission don't apply, so they are never
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
            // An optional document is asked for but not demanded, so nothing attached still fulfils it. Whether
            // it is asked at all is its condition's decision, made before this is ever reached.
            // The reference is resolved into a local, both because resolving it twice would repeat the whole
            // reference lookup, and because the null check wouldn't apply to a second, separate call
            return !((DocumentRequirement) requirement).isRequired() || this.getDocuments().stream()
                .anyMatch(document -> {
                    final Requirement fulfilled = document.getFulfills();
                    return fulfilled != null && requirement.getPath().equals(fulfilled.getPath());
                });
        }
        if (requirement instanceof ApprovalRequirement) {
            return this.getReviewsOf(requirement).stream().anyMatch(Review::isApproved);
        }
        // FormRequirement is the only other concrete requirement type today. Only questions demanding at least one
        // value can leave it unfulfilled: an optional one is asked, not demanded, and a submission is not
        // incomplete for declining to answer it. Without this filter `minAnswers` would mean nothing at all
        return this.getQuestionsOf((FormRequirement) requirement).stream()
            .filter(Question::isRequired)
            .allMatch(this::isAnswered);
    }

    /**
     * The questions this submission is currently asked: every question of every form requirement that applies,
     * conditions resolved, in the order the schema declares them. A question whose condition does not hold is not
     * being asked, so it is absent even when it still holds an answer from before its condition changed.
     *
     * <p>This is the walk fulfilment is judged by, published so that anything else reading "what is asked and what
     * was answered" — a validator, a projection — counts the same questions the completeness decision counts.</p>
     *
     * @return the questions currently asked, empty when none apply
     */
    @NotNull
    public List<Question> getQuestions()
    {
        return this.getSchemaVersion().getRequirements().stream()
            .filter(this::applies)
            .filter(FormRequirement.class::isInstance)
            .map(FormRequirement.class::cast)
            .map(this::getQuestionsOf)
            .flatMap(List::stream)
            .collect(Collectors.toList());
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
        // Blank counts as unanswered, not as answered. Clearing a field posts an empty value rather than removing
        // the answer node, so the node stays behind holding nothing — and counting that as an answer would let a
        // demanded question be satisfied by emptying it. Counted rather than merely detected, because a question
        // may ask for more than one value, and one of three is as unanswered as none
        final long given = this.getAnswersByQuestion().getOrDefault(question.getPath(), List.of()).stream()
            .filter(value -> !value.isBlank())
            .count();
        return given >= question.getMinAnswers();
    }

    /**
     * What has been answered, by the path of the question it answers.
     *
     * <p>One index, because two readers need it and they must not disagree: this is what decides whether a form
     * requirement is fulfilled, and it is also what a form is rendered from. Two implementations of "counts as an
     * answer" would let a form show a value that the decision to accept the submission did not count, or refuse a
     * submission over a question the reader can see filled in.</p>
     *
     * <p>An answer whose question no longer resolves answers nothing being asked, and is left out. Where more than
     * one answer node addresses the same question — which only degenerate content produces — the one carrying a
     * value wins, so the index agrees with the plain reading that the question <em>has</em> been answered.</p>
     *
     * @return the values given, by question path; empty for a submission nobody has answered
     */
    @NotNull
    public Map<String, List<String>> getAnswersByQuestion()
    {
        // A loop rather than a stream: the question has to be read once into a local — asking twice around a null
        // check is what makes a @Nullable accessor look safe to dereference — and the collision rule below reads
        // more plainly here than as a merge function
        final Map<String, List<String>> byQuestion = new HashMap<>();
        for (final Answer answer : this.getAnswers()) {
            final Question question = answer.getQuestion();
            if (question == null) {
                continue;
            }
            // The value is nullable and List.of would throw on a null array: an answer node carrying no value at
            // all is permitted by the node type, and it means the same as one carrying nothing
            final List<String> value = List.of(Objects.requireNonNullElse(answer.getValue(), new String[0]));
            final List<String> known = byQuestion.get(question.getPath());
            if (known == null || known.isEmpty()) {
                byQuestion.put(question.getPath(), value);
            }
        }
        return byQuestion;
    }
}
