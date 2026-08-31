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
import java.util.List;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.describers.FormKeys;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;
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
 * <p>Each kind of requirement adds its own fields through the {@link RequirementDescriber}s registered for it. A
 * kind declared in another module reaches the form without this module depending on it.</p>
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

    @Reference
    private transient ConditionEvaluator conditions;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private transient volatile List<RequirementDescriber> describers;

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
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        version.getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission)));
        return Json.createObjectBuilder()
            .add(FormKeys.PATH_KEY, submission.getPath())
            .add(FormKeys.TITLE_KEY, Objects.toString(submission.getTitle(), ""))
            // The same two rules the save workflow enforces. An editor can then offer editing only where a
            // save would be accepted, rather than discovering it from a refusal
            .add("editable", submission.isDraft() && reader.equals(submission.getCreatedBy()))
            .add("requirements", requirements)
            .build();
    }

    /**
     * One requirement: what every kind has, then what the describers of its kind add.
     *
     * @param requirement a requirement that applies to the submission
     * @param submission the submission whose form is being served
     * @return the requirement's object in the form's JSON
     */
    private JsonObjectBuilder requirement(final Requirement requirement, final Submission submission)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(FormKeys.NAME_KEY, requirement.getName())
            // What a document's reference is compared against, since names repeat across schema versions
            .add(FormKeys.PATH_KEY, requirement.getPath())
            // The resource type itself, not a vocabulary of our own. A kind nothing here knows names itself, and
            // the reader already keys on resource types
            .add(FormKeys.TYPE_KEY, requirement.getType())
            .add(FormKeys.LABEL_KEY, Objects.toString(requirement.getLabel(), ""))
            .add(FormKeys.DESCRIPTION_KEY, Objects.toString(requirement.getDescription(), ""));
        // A kind nobody describes still gets the common fields, so the form never reports a schema asking for less
        // than it does
        Objects.requireNonNullElse(this.describers, List.<RequirementDescriber>of()).stream()
            .filter(describer -> describer.handles(requirement))
            .forEach(describer -> describer.describe(requirement, submission, json));
        return json;
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
