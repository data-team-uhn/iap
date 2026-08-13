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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Submission;

/**
 * The form a submitter fills in: what this submission's schema version asks of it, with the answers it already
 * holds, and with everything that does not currently apply left out. Served as
 * {@code /Submissions/…/….form.json}.
 *
 * <p><strong>Why this exists rather than a filtered node serialization.</strong> Whether a question applies
 * depends on the answers <em>this</em> submission holds, so it cannot be decided by looking at the schema alone —
 * and the schema reaches an ordinary serialization as a dereferenced property, where filtering inside an embedded
 * subtree would be surgery. What an editor needs is a different document from either: the schema's structure and
 * the submission's answers, merged, with conditions already resolved.</p>
 *
 * <p><strong>Why conditions are resolved here and nowhere else.</strong> {@link ConditionEvaluator} is extensible
 * through a whiteboard of operand resolvers, which a downstream project may add to; an editor that evaluated
 * conditions itself could not see those, could not know it could not see them, and would silently hide content
 * because a condition it cannot evaluate is never satisfied. So the browser is told <em>what to show</em> rather
 * than what to work out, and the same evaluator that decides whether a submission is complete decides what its
 * form looks like — the two can never disagree.</p>
 *
 * <p>Each question carries the path the save endpoint expects, relative to the schema version, so an editor never
 * has to construct one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = Submission.RESOURCE_TYPE,
    selectors = "form",
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class SubmissionFormServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 6455351484949339021L;

    /** The lifecycle in which a submitter may still answer. */
    private static final String DRAFT = "draft";

    private static final String NAME = "name";

    private static final String LABEL = "label";

    private static final String DESCRIPTION = "description";

    private static final String ITEMS = "items";

    private static final String TYPE = "type";

    @Reference
    private transient ConditionEvaluator conditions;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        // This servlet is bound to the submission resource type, so what it is handed is always one: a null here
        // would mean the models are not registered at all, not that this particular request was odd
        final Submission submission = Objects.requireNonNull(request.getResource().adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(form(submission, request.getResourceResolver().getUserID()).toString());
    }

    /**
     * The whole document: what the submission is, whether it may still be answered, and what it asks.
     *
     * @param submission the submission being read
     * @param reader the user asking
     * @return the form's JSON
     */
    private JsonObject form(final Submission submission, final String reader)
    {
        final Map<String, List<String>> answers = answersByQuestion(submission);
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        submission.getSchemaVersion().getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission, answers)));
        return Json.createObjectBuilder()
            .add("path", submission.getPath())
            .add("title", Objects.toString(submission.getTitle(), ""))
            // The same two rules the save handler enforces, so an editor can offer editing only where a save
            // would actually be accepted rather than discovering it from a refusal
            .add("editable", submission.getTags().contains(DRAFT) && reader.equals(submission.getCreatedBy()))
            .add("requirements", requirements)
            .build();
    }

    /**
     * One requirement: its own presentation, and — for a set of questions — the items that currently apply.
     *
     * @param requirement the requirement to describe
     * @param submission the submission it is being resolved against
     * @param answers the submission's answers, by the path of the question each answers
     * @return the requirement's JSON
     */
    private JsonObjectBuilder requirement(final Requirement requirement, final Submission submission,
        final Map<String, List<String>> answers)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(NAME, requirement.getName())
            // The resource type itself, not a vocabulary of our own: a requirement kind added later names itself
            // here without this servlet having to learn about it, and the reader already keys on resource types
            .add(TYPE, requirement.getType())
            .add(LABEL, Objects.toString(requirement.getLabel(), ""))
            .add(DESCRIPTION, Objects.toString(requirement.getDescription(), ""));
        if (requirement instanceof FormRequirement) {
            json.add(ITEMS, items(((FormRequirement) requirement).getChildren(), requirement.getName(),
                submission, answers));
        }
        return json;
    }

    /**
     * The items of a form or a section, in the order the schema puts them, skipping whatever does not apply.
     *
     * @param children the form items to describe
     * @param prefix the path of their container, relative to the schema version
     * @param submission the submission they are being resolved against
     * @param answers the submission's answers, by question path
     * @return the items' JSON
     */
    private JsonArrayBuilder items(final List<FormItem> children, final String prefix, final Submission submission,
        final Map<String, List<String>> answers)
    {
        final JsonArrayBuilder items = Json.createArrayBuilder();
        children.stream()
            .filter(child -> this.applies(child, submission))
            .forEach(child -> {
                final String path = prefix + "/" + child.getName();
                if (child instanceof Section) {
                    final Section section = (Section) child;
                    items.add(Json.createObjectBuilder()
                        .add(NAME, section.getName())
                        .add(TYPE, section.getType())
                        .add(LABEL, Objects.toString(section.getTitle(), ""))
                        .add(DESCRIPTION, Objects.toString(section.getDescription(), ""))
                        .add(ITEMS, items(section.getChildren(), path, submission, answers)));
                } else if (child instanceof Question) {
                    items.add(question((Question) child, path, answers));
                }
            });
        return items;
    }

    /**
     * One question, with the answer it already has.
     *
     * <p>It carries its own {@code path} — relative to the schema version, which is what the save endpoint asks
     * for — so that an editor posts back what it was given instead of working out how to address a question.</p>
     *
     * @param question the question to describe
     * @param path its path relative to the schema version
     * @param answers the submission's answers, by question path
     * @return the question's JSON
     */
    private JsonObjectBuilder question(final Question question, final String path,
        final Map<String, List<String>> answers)
    {
        final JsonArrayBuilder value = Json.createArrayBuilder();
        answers.getOrDefault(question.getPath(), List.of()).forEach(value::add);
        return Json.createObjectBuilder()
            .add(NAME, question.getName())
            .add(TYPE, question.getType())
            .add("path", path)
            .add("text", Objects.toString(question.getText(), ""))
            .add(DESCRIPTION, Objects.toString(question.getDescription(), ""))
            .add("dataType", Objects.toString(question.getDataType(), "text"))
            .add("required", question.isRequired())
            .add("multiple", question.isMultiple())
            .add("value", value);
    }

    /**
     * The submission's answers, keyed by the absolute path of the question each one answers. Keyed by path rather
     * than by name because two sections may ask questions of the same name.
     *
     * @param submission the submission to read
     * @return the recorded values, by question path
     */
    private static Map<String, List<String>> answersByQuestion(final Submission submission)
    {
        // A loop rather than a stream on purpose: the question has to be read once into a local — asking twice
        // around a null check is what makes a @Nullable accessor look safe to dereference — and collecting to a
        // map would need a merge function for a collision that only degenerate content can produce, which is a
        // branch nothing would ever cover.
        final Map<String, List<String>> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            // An answer whose question no longer resolves is the answer to nothing being asked now
            if (question != null) {
                byQuestion.putIfAbsent(question.getPath(), List.of(answer.getValue()));
            }
        }
        return byQuestion;
    }

    /**
     * Whether a conditionable part of the schema currently applies to this submission. Delegated in full: the
     * rule, its vocabulary and its extensions all live in the evaluator.
     *
     * @param conditionable the schema part to test
     * @param submission the submission to test it against
     * @return {@code true} if it should be shown
     */
    private boolean applies(final Conditionable conditionable, final Submission submission)
    {
        return this.conditions.applies(conditionable, submission);
    }
}
