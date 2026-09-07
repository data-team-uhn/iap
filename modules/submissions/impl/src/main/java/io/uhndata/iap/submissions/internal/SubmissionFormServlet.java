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

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;
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
 * <p><strong>What each kind of requirement needs is not this servlet's business.</strong> It writes the fields
 * every requirement has and then asks the registered {@link RequirementDescriber}s, so a kind of requirement can
 * be declared by a module this one has never heard of. Conditions are still resolved here, for the same reason
 * they are not resolved in the browser: {@link ConditionEvaluator} is extensible through a whiteboard of operand
 * resolvers a downstream project may add to, so a reader that evaluated conditions itself could not see those,
 * could not know it could not see them, and would silently hide content because a condition it cannot evaluate is
 * never satisfied.</p>
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

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.REPLACE,
        policy = ReferencePolicy.DYNAMIC)
    private transient volatile List<RequirementDescriber> describers;

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
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        submission.getSchemaVersion().getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission)));
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
     * One requirement: what every requirement has, and then whatever its own kind adds.
     *
     * @param requirement the requirement to describe
     * @param submission the submission it is being resolved against
     * @return the requirement's JSON
     */
    private JsonObjectBuilder requirement(final Requirement requirement, final Submission submission)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("name", requirement.getName())
            // The resource type itself, not a vocabulary of our own: a requirement kind added later names itself
            // here without this servlet having to learn about it, and the reader already keys on resource types
            .add("type", requirement.getType())
            .add("label", Objects.toString(requirement.getLabel(), ""))
            .add("description", Objects.toString(requirement.getDescription(), ""));
        currentDescribers().stream()
            .filter(describer -> describer.handles(requirement))
            .forEach(describer -> describer.describe(requirement, submission, json));
        return json;
    }

    /**
     * The describers registered right now. A dynamic whiteboard field is null until something registers, and the
     * list itself can change under a request, so it is copied before being walked.
     *
     * @return the describers to consult, empty if none are registered
     */
    private List<RequirementDescriber> currentDescribers()
    {
        final List<RequirementDescriber> current = this.describers;
        return current == null ? List.of() : List.copyOf(current);
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
