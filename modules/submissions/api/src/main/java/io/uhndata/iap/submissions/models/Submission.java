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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * A Sling Model wrapping a {@code sub:Submission} node: what a submitter files against one schema version. It holds
 * the answers to the schema's questions, the documents, and the feedback on the submission.
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

    @ValueMapValue
    private String schema;

    /**
     * The submission's title, as people read it.
     *
     * @return the title
     */
    @NotNull
    public String getTitle()
    {
        return this.title;
    }

    /**
     * The schema version this submission answers. It fails where this session cannot read the version.
     *
     * @return the schema version
     */
    @NotNull
    public SchemaVersion getSchemaVersion()
    {
        return Objects.requireNonNull(this.findSchemaVersion(), "Missing mandatory schemaVersion reference");
    }

    /**
     * The schema version this submission answers, where this session can read it. The reference is mandatory, but a
     * session that may not read the version resolves nothing.
     *
     * @return the schema version; {@code null} if this session cannot resolve the reference
     * @since 0.1.0
     */
    @Nullable
    public SchemaVersion findSchemaVersion()
    {
        return this.getReference(this.schemaVersion, SchemaVersion.class);
    }

    /**
     * The schema this submission answers, whichever of its versions it was raised against.
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
     * The submitter's answers to the schema's questions.
     *
     * @return the answers; empty if there are none
     */
    @NotNull
    public List<Answer> getAnswers()
    {
        return this.getChildren(Answer.RESOURCE_TYPE, Answer.class);
    }

    /**
     * The documents of this submission, including the empty ones nothing has been uploaded into.
     *
     * @return the documents; empty if there are none
     */
    @NotNull
    public List<Document> getDocuments()
    {
        return this.getChildren(Document.RESOURCE_TYPE, Document.class);
    }

    /**
     * The reviews of this submission.
     *
     * @return the reviews; empty if there are none
     */
    @NotNull
    public List<Review> getReviews()
    {
        return this.getChildren(Review.RESOURCE_TYPE, Review.class);
    }

    /**
     * The latest decision on one requirement, which is where that requirement now stands: the most recent review of
     * it that approved or rejected it. Reviews still open are left out.
     *
     * <p>The most recent is the one created last, since the children of a submission have no order of their own. A
     * review with no creation date counts as the oldest.</p>
     *
     * @param requirement the requirement to look up
     * @return the latest review deciding it; {@code null} while nobody has
     */
    @Nullable
    public Review findLatestDecisionOn(@NotNull final Requirement requirement)
    {
        return this.getReviews().stream()
            .filter(review -> review.isAbout(requirement))
            .filter(Review::isDecided)
            .max(Comparator.comparing(Review::getCreated, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    /**
     * The workflows running over this submission, held in the container the {@code wf:WorkflowAttachable} mixin
     * autocreates. Several may run at once.
     *
     * @return the workflow instances; empty if none has ever been started
     */
    @NotNull
    public List<WorkflowInstance> getWorkflowInstances()
    {
        // Type-checked: the node type accepts any child, and an unrelated node by that name would still adapt
        final WorkflowInstances container = this.getChild(WorkflowInstances.NODE_NAME,
            WorkflowInstances.RESOURCE_TYPE, WorkflowInstances.class);
        return container == null ? List.of() : container.getInstances();
    }

    /**
     * Whether this submission has been approved: it carries the {@code approved} lifecycle tag itself.
     *
     * @return {@code true} if approved; {@code false} also when the tags service is unavailable
     */
    public boolean isApproved()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(APPROVED_TAG);
    }

    /**
     * Whether this submission is still being written: it carries the {@code draft} lifecycle tag itself. Only a
     * draft can be changed by its submitter.
     *
     * @return {@code true} while it is a draft; {@code false} also when the tags service is unavailable
     */
    public boolean isDraft()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(DRAFT_TAG);
    }

    /**
     * Every kind of feedback on this submission: reviews, screenings and discussions.
     *
     * @return the feedback; empty if there is none
     */
    @NotNull
    public List<Feedback> getFeedback()
    {
        return this.getChildren(Feedback.RESOURCE_TYPE, Feedback.class);
    }

    /**
     * Every unresolved comment raised across all of this submission's feedback.
     *
     * @return the unresolved comments; empty if there are none
     */
    @NotNull
    public List<Comment> getUnresolvedComments()
    {
        return this.getFeedback().stream()
            .flatMap(feedback -> feedback.getUnresolvedComments().stream())
            .collect(Collectors.toList());
    }

    /**
     * The unresolved comments raised in binding feedback, which are the ones a decision waits on.
     *
     * @return the unresolved comments; empty if there are none
     */
    @NotNull
    public List<Comment> getUnresolvedBindingComments()
    {
        return this.getFeedback().stream()
            .filter(Feedback::isBinding)
            .flatMap(feedback -> feedback.getUnresolvedComments().stream())
            .collect(Collectors.toList());
    }

    /**
     * Whether a requirement, section or question is asked of this submission: its condition holds for it.
     *
     * @param item the requirement, section or question
     * @return {@code true} if its condition holds or it has none; {@code true} also when the condition service is
     *     unavailable
     */
    public boolean isApplicable(@NotNull final Conditionable item)
    {
        return this.conditionEvaluator == null || this.conditionEvaluator.applies(item, this);
    }

    /**
     * Returns the questions one form requirement asks now, chosen by name, in schema order. A requirement, section
     * or question whose condition does not apply is left out. The extraction uses it to read a document one
     * requirement at a time.
     *
     * @param name the requirement's node name, or its full path
     * @return its questions, empty when it does not apply, is not a form, or does not exist
     */
    @NotNull
    public List<Question> getQuestions(@Nullable final String name)
    {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return this.getQuestionsOf(requirement -> name.equals(requirement.getName())
            || name.equals(requirement.getPath()));
    }

    private List<Question> getQuestionsOf(final Predicate<Requirement> chosen)
    {
        return this.getSchemaVersion().getRequirements().stream()
            .filter(chosen)
            .filter(FormRequirement.class::isInstance)
            .filter(this::isApplicable)
            .flatMap(form -> ((FormRequirement) form).getChildren().stream())
            .flatMap(this::getApplicableQuestions)
            .collect(Collectors.toList());
    }

    private Stream<Question> getApplicableQuestions(final FormItem item)
    {
        if (!this.isApplicable(item)) {
            return Stream.empty();
        }
        if (item instanceof Question) {
            return Stream.of((Question) item);
        }
        // Section is the only other item type
        return ((Section) item).getChildren().stream().flatMap(this::getApplicableQuestions);
    }

    /**
     * What has been answered, by the path of the question each answer is for. An answer whose question no longer
     * resolves is left out. When two answers are for the same question, the first one holding values wins.
     *
     * @return the values of each answer, as stored, by the absolute path of its question; an empty list for an answer
     *     holding no value; an empty map if the submission has no answers
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

    /**
     * The values answering one question, named by its path within the schema version. That path stays the same in a
     * new version of the schema, and in a copy of it.
     *
     * @param questionPath the path of the question to read, relative to the schema version, such as
     *     {@code details/startDate}
     * @return the non-blank values of the answer as stored, such as {@code 2026-11-23} for a date; empty if the
     *     question is unanswered, the schema version has no question at that path, or this session cannot read the
     *     schema version
     */
    @NotNull
    public List<String> getAnswersTo(@NotNull final String questionPath)
    {
        final SchemaVersion version = this.findSchemaVersion();
        if (version == null) {
            return List.of();
        }
        return this.getAnswersByQuestion().getOrDefault(version.getPath() + "/" + questionPath, List.of()).stream()
            .filter(value -> !value.isBlank())
            .toList();
    }

    /**
     * The single value answering one question, for the questions that take one.
     *
     * @param questionPath the path of the question to read, relative to the schema version, such as
     *     {@code details/startDate}
     * @return the first non-blank value stored in the answer; {@code null} if the question is unanswered, the schema
     *     version has no question at that path, or this session cannot read the schema version
     */
    @Nullable
    public String getAnswerTo(@NotNull final String questionPath)
    {
        return this.getAnswersTo(questionPath).stream().findFirst().orElse(null);
    }
}
