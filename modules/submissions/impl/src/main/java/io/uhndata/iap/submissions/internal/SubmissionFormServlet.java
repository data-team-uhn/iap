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
import java.util.Arrays;
import java.util.Calendar;
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
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.DateUtils;
import io.uhndata.iap.utils.UserIds;

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
        response.getWriter().write(
            form(submission, UserIds.canonical(request.getResourceResolver())).toString());
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
        // The submission's own index, which is also what decides whether its form requirements are fulfilled:
        // two indexes that disagreed about what counts as an answer would have the form and the decision to
        // accept it disagree too
        final Map<String, List<String>> answers = submission.getAnswersByQuestion();
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        submission.getSchemaVersion().getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission, answers)));
        return Json.createObjectBuilder()
            .add("path", submission.getPath())
            .add("title", Objects.toString(submission.getTitle(), ""))
            // The same two rules the save handler enforces, so an editor can offer editing only where a save
            // would actually be accepted rather than discovering it from a refusal
            .add("editable", submission.isDraft() && reader.equals(submission.getCreatedBy()))
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
        } else if (requirement instanceof DocumentRequirement) {
            describe((DocumentRequirement) requirement, submission, json);
        } else if (requirement instanceof ApprovalRequirement) {
            describe((ApprovalRequirement) requirement, submission, json);
        }
        return json;
    }

    /**
     * What an approval requirement adds: who it waits on, and the decision once somebody has made one.
     *
     * <p>Nobody fills an approval in here, so what the form can offer is an honest account of where it stands.
     * That is worth projecting rather than leaving the reader to infer it, because the alternative — a section
     * that says only that it cannot be completed here — is indistinguishable from a part of the form that is
     * broken.</p>
     *
     * <p>Approved is the model's own predicate, an approved review naming this requirement, so the form and the
     * completeness tag cannot disagree about what an approval means. The decision is reported from the same
     * review; a rejection is a review that is not approved, which is why the reviewer and the date are given
     * whenever a review exists rather than only when it granted the approval.</p>
     *
     * @param requirement the requirement being described
     * @param submission the submission it is being resolved against
     * @param json the requirement's JSON, added to in place
     */
    private void describe(final ApprovalRequirement requirement, final Submission submission,
        final JsonObjectBuilder json)
    {
        // Always stated, empty meaning "not narrowed to a group": a reader has to tell that from "nobody has said
        // who decides", and both are things the section says out loud
        json.add("approverGroup", Objects.toString(requirement.getApproverGroup(), ""));
        final List<Review> reviews = submission.getReviewsOf(requirement);
        json.add("approved", reviews.stream().anyMatch(Review::isApproved));
        // The last word rather than the first: an approval that was revisited is reported as it now stands
        reviews.stream().reduce((first, second) -> second).ifPresent(review -> {
            json.add("decidedBy", Objects.toString(review.getReviewer(), ""));
            final Calendar decided = review.getCreated();
            if (decided != null) {
                // The same spelling the resource JSON uses for a date, so the reader parses one format
                json.add("decidedAt", DateUtils.PREFERRED_DATETIME_FORMAT
                    .format(decided.toInstant().atZone(decided.getTimeZone().toZoneId())));
            }
        });
    }

    /**
     * What a document requirement adds: which types it takes, the blank to start from if it offers one, and what
     * has already been attached for it.
     *
     * <p>All three are here because an upload control cannot be drawn without them, and this projection is the
     * only place that says which requirements currently apply — reading them off the schema instead would mean a
     * control offering to answer something this submission is not being asked.</p>
     *
     * @param requirement the requirement being described
     * @param submission the submission it is being resolved against
     * @param json the requirement's JSON, added to in place
     */
    private void describe(final DocumentRequirement requirement, final Submission submission,
        final JsonObjectBuilder json)
    {
        // Stated always, not only when false: an upload control marks the optional case, and it should do so
        // because the form said so rather than because a key was missing
        json.add("required", requirement.isRequired());
        final JsonArrayBuilder accepted = Json.createArrayBuilder();
        // Absent means "no restriction", which a reader has to be able to tell from a list that happens to be
        // empty — so the key is always there and it is the emptiness that carries the meaning
        Arrays.stream(Objects.requireNonNullElse(requirement.getAcceptedFileTypes(), new String[0]))
            .forEach(accepted::add);
        json.add("acceptedFileTypes", accepted);
        final Resource template = requirement.getTemplate();
        if (template != null) {
            json.add("template", template.getPath());
        }
        // Named rather than counted, so that a form reopened later says which document is there. Without this an
        // upload control looks the same before and after, and the way to check would be to leave the page
        final JsonArrayBuilder attached = Json.createArrayBuilder();
        submission.getDocuments().stream()
            .filter(document -> fulfills(document, requirement))
            .map(document -> Objects.toString(document.getTitle(), document.getName()))
            .forEach(attached::add);
        json.add("attached", attached);
    }

    /**
     * Whether one document was attached in answer to one requirement.
     *
     * @param document the attached document
     * @param requirement the requirement in question
     * @return {@code true} if the document says it fulfills that requirement
     */
    private boolean fulfills(final Document document, final Requirement requirement)
    {
        final Requirement fulfilled = document.getFulfills();
        return fulfilled != null && requirement.getPath().equals(fulfilled.getPath());
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
        // Emitted even when empty, so that "answered freely" is something the form states rather than something a
        // reader infers from a missing field
        final JsonArrayBuilder options = Json.createArrayBuilder();
        question.getOptions().forEach(option -> options.add(Json.createObjectBuilder()
            .add("value", option.getValue())
            .add("label", option.getLabel())));
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(NAME, question.getName())
            .add(TYPE, question.getType())
            .add("path", path)
            .add("text", Objects.toString(question.getText(), ""))
            .add(DESCRIPTION, Objects.toString(question.getDescription(), ""))
            .add("dataType", Objects.toString(question.getDataType(), "text"))
            // The pair itself rather than derived required/multiple flags: one vocabulary on the wire, read the
            // same way it is stored, so the two sides cannot disagree about what a count means
            .add("minAnswers", question.getMinAnswers())
            .add("maxAnswers", question.getMaxAnswers());
        // The constraints are stated only where the schema states them; the editor maps them onto the input's own
        // hints, and the save is where they are enforced. Each is read once into a local, so the null check
        // guards the very value that is written.
        final Double minValue = question.getMinValue();
        final Double maxValue = question.getMaxValue();
        final String pattern = question.getPattern();
        final String patternMessage = question.getPatternMessage();
        if (minValue != null) {
            json.add("minValue", minValue);
        }
        if (maxValue != null) {
            json.add("maxValue", maxValue);
        }
        if (pattern != null) {
            json.add("pattern", pattern);
        }
        if (patternMessage != null) {
            json.add("patternMessage", patternMessage);
        }
        return json
            .add("options", options)
            .add("value", value);
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
