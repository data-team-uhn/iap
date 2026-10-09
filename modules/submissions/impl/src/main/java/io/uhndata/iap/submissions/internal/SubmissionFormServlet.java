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
import jakarta.servlet.http.HttpServletResponse;

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
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormItem;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.utils.UserIds;

/**
 * The form a submitter fills in: what this submission's schema version asks of it, with the answers it
 * already holds. Everything that does not currently apply is left out. Served as
 * {@code /Submissions/…/….form.json}.
 *
 * <p><strong>Why this exists rather than a filtered node serialization.</strong> Whether a question applies
 * depends on the answers <em>this</em> submission holds, so it cannot be decided by looking at the schema
 * alone. What an editor needs is a different document from either: the schema's structure and the submission's answers,
 * merged, with conditions already resolved.</p>
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

    private static final String NAME_KEY = "name";

    private static final String LABEL_KEY = "label";

    private static final String DESCRIPTION_KEY = "description";

    private static final String ITEMS_KEY = "items";

    private static final String TYPE_KEY = "type";

    private static final String ACCEPTED_FILE_TYPES_KEY = "acceptedFileTypes";

    private static final String TEMPLATE_KEY = "template";

    private static final String ATTACHED_KEY = "attached";

    private static final String REQUIRED_KEY = "required";

    @Reference
    private transient ConditionEvaluator conditions;

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        // This servlet is bound to the submission resource type, so it is always handed one.
        // A null here would mean the models are not registered at all, not that this request was odd.
        final Submission submission = Objects.requireNonNull(request.getResource().adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        final SchemaVersion version = submission.findSchemaVersion();
        if (version == null) {
            // The reference is mandatory and a REFERENCE, so the repository will not let its target be
            // deleted from under it: what is left is a session that may not read the version. Answered
            // rather than recorded, since that is access control doing its job
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "The schema version this submission answers cannot be read");
            return;
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
            .write(form(submission, version, UserIds.canonical(request.getResourceResolver())).toString());
    }

    /**
     * The whole document: what the submission is, whether it may still be answered, and what it asks.
     *
     * @param submission the submission being read
     * @param version the schema version it answers
     * @param reader the user asking
     * @return the form's JSON
     */
    private JsonObject form(final Submission submission, final SchemaVersion version, final String reader)
    {
        final Map<String, List<String>> answers = answersByQuestion(submission);
        final List<Document> documents = submission.getDocuments();
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        version.getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission, answers, documents)));
        return Json.createObjectBuilder()
            .add("path", submission.getPath())
            .add("title", Objects.toString(submission.getTitle(), ""))
            // The same two rules the save workflow enforces. An editor can then offer editing only where a
            // save would be accepted, rather than discovering it from a refusal
            .add("editable", submission.isDraft() && reader.equals(submission.getCreatedBy()))
            .add("requirements", requirements)
            .build();
    }

    /**
     * One requirement: its own presentation, and, for a set of questions, the items that currently apply.
     *
     * @param requirement the requirement to describe
     * @param submission the submission it is being resolved against
     * @param answers the submission's answers, by the path of the question each answers
     * @param documents the documents attached to the submission
     * @return the requirement's JSON
     */
    private JsonObjectBuilder requirement(final Requirement requirement, final Submission submission,
        final Map<String, List<String>> answers, final List<Document> documents)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(NAME_KEY, requirement.getName())
            // The resource type itself, not a vocabulary of our own. A requirement kind added later names
            // itself here without this servlet learning about it, and the reader already keys on resource types
            .add(TYPE_KEY, requirement.getType())
            .add(LABEL_KEY, Objects.toString(requirement.getLabel(), ""))
            .add(DESCRIPTION_KEY, Objects.toString(requirement.getDescription(), ""));
        if (requirement instanceof FormRequirement) {
            json.add(ITEMS_KEY, items(((FormRequirement) requirement).getChildren(), requirement.getName(),
                submission, answers));
        } else if (requirement instanceof DocumentRequirement) {
            describe((DocumentRequirement) requirement, documents, json);
        }
        return json;
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
     * @param documents the documents attached to the submission
     * @param json the requirement's JSON, added to in place
     */
    private void describe(final DocumentRequirement requirement, final List<Document> documents,
        final JsonObjectBuilder json)
    {
        // Stated always, not only when false: the upload control marks the optional case, and should do so
        // because the form said so rather than because a key was missing
        json.add(REQUIRED_KEY, requirement.isRequired());
        final JsonArrayBuilder accepted = Json.createArrayBuilder();
        // Absent means "no restriction", which a reader has to be able to tell from a list that happens to be
        // empty — so the key is always there and it is the emptiness that carries the meaning
        requirement.getAcceptedFileTypes().forEach(accepted::add);
        json.add(ACCEPTED_FILE_TYPES_KEY, accepted);
        final Resource template = requirement.getTemplate();
        if (template != null) {
            json.add(TEMPLATE_KEY, template.getPath());
        }
        // Named rather than counted, so that a form reopened later says which document is there. Without this an
        // upload control looks the same before and after, and the way to check would be to leave the page
        final JsonArrayBuilder attached = Json.createArrayBuilder();
        documents.stream()
            .filter(document -> document.isFulfilling(requirement))
            .map(document -> Objects.toString(document.getTitle(), document.getName()))
            .forEach(attached::add);
        json.add(ATTACHED_KEY, attached);
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
                        .add(NAME_KEY, section.getName())
                        .add(TYPE_KEY, section.getType())
                        .add(LABEL_KEY, Objects.toString(section.getTitle(), ""))
                        .add(DESCRIPTION_KEY, Objects.toString(section.getDescription(), ""))
                        .add(ITEMS_KEY, items(section.getChildren(), path, submission, answers)));
                } else if (child instanceof Question) {
                    items.add(question((Question) child, path, answers));
                }
            });
        return items;
    }

    /**
     * One question, with the answer it already has.
     *
     * <p>It carries its own {@code path}, relative to the schema version, which is what the save workflow
     * asks for. An editor posts back what it was given instead of working out how to address a question.</p>
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
            .add(NAME_KEY, question.getName())
            .add(TYPE_KEY, question.getType())
            .add("path", path)
            .add("text", Objects.toString(question.getText(), ""))
            .add(DESCRIPTION_KEY, Objects.toString(question.getDescription(), ""))
            .add("dataType", Objects.toString(question.getDataType(), "text"))
            .add(REQUIRED_KEY, question.isRequired())
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
        final Map<String, List<String>> byQuestion = new HashMap<>();
        for (final Answer answer : submission.getAnswers()) {
            final Question question = answer.getQuestion();
            final String[] value = answer.getValue();
            // An answer whose question no longer resolves is the answer to nothing being asked now, and one
            // holding no value has not been answered yet
            if (question != null && value != null) {
                byQuestion.putIfAbsent(question.getPath(), List.of(value));
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
