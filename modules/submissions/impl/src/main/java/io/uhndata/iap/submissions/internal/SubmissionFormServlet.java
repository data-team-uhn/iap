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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.conditions.models.Conditionable;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.describers.FormKeys;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;
import io.uhndata.iap.utils.UserIds;

/**
 * The form a submitter fills in: what this submission's schema version asks of it, with the answers it already holds.
 * Served as {@code <submission>.form.json}.
 *
 * <p>Whether an item applies depends on this submission's answers. The form therefore merges the schema's structure
 * with the answers, and leaves out every requirement, section and question whose condition does not hold now.</p>
 *
 * <p>Each requirement gets its kind's own fields from every {@link RequirementDescriber} that handles it. A kind
 * declared in another module reaches the form without this module depending on it.</p>
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

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionFormServlet.class);

    private final transient ConditionEvaluator conditions;

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private transient volatile List<RequirementDescriber> describers;

    /**
     * Constructor.
     *
     * @param conditions decides which requirements apply now
     */
    @Activate
    public SubmissionFormServlet(@Reference final ConditionEvaluator conditions)
    {
        this.conditions = conditions;
    }

    /**
     * Replaces the describers asked about each requirement, as the service registry does when they come and go.
     *
     * @param describers every describer, in the order they are applied
     */
    void setDescribers(final List<RequirementDescriber> describers)
    {
        this.describers = describers;
    }

    @Override
    // The form is content, not a command's answer
    @SuppressWarnings("checkstyle:directResponseWriter")
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        // Bound to the submission resource type, so a null means the models are not registered
        final Submission submission = Objects.requireNonNull(request.getResource().adaptTo(Submission.class),
            "A submission resource always reads as a submission");
        final SchemaVersion version = submission.findSchemaVersion();
        if (version == null) {
            // A REFERENCE keeps its target from being deleted, so this session may not read the version. That is
            // access control at work, so it is answered and not recorded.
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
     * The whole document: what the submission is, whether the reader may still answer it, and what it asks.
     *
     * @param submission the submission being read
     * @param version the schema version it answers
     * @param reader the canonical user id of whoever asks
     * @return the form's JSON
     */
    private JsonObject form(final Submission submission, final SchemaVersion version, final String reader)
    {
        // Read once, so one form is described by one set of describers while the registry changes. Null until the
        // first describer arrives.
        final List<RequirementDescriber> current = Objects.requireNonNullElse(this.describers, List.of());
        final JsonArrayBuilder requirements = Json.createArrayBuilder();
        version.getRequirements().stream()
            .filter(requirement -> this.applies(requirement, submission))
            .forEach(requirement -> requirements.add(requirement(requirement, submission, current)));
        return Json.createObjectBuilder()
            .add(FormKeys.PATH_KEY, submission.getPath())
            .add(FormKeys.TITLE_KEY, Objects.toString(submission.getTitle(), ""))
            // The two rules the save workflow enforces, so an editor offers editing only where a save is accepted
            .add("editable", submission.isDraft() && reader.equals(submission.getCreatedBy()))
            .add("requirements", requirements)
            .build();
    }

    /**
     * One requirement: what every kind has, then what the describers of its kind add.
     *
     * @param requirement a requirement that applies to the submission
     * @param submission the submission whose form is being served
     * @param describers every describer, in the order they are applied
     * @return the requirement's object in the form's JSON
     */
    private JsonObjectBuilder requirement(final Requirement requirement, final Submission submission,
        final List<RequirementDescriber> describers)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(FormKeys.NAME_KEY, requirement.getName())
            // Names repeat across schema versions, and paths do not
            .add(FormKeys.PATH_KEY, requirement.getPath())
            .add(FormKeys.TYPE_KEY, requirement.getType())
            .add(FormKeys.LABEL_KEY, Objects.toString(requirement.getLabel(), ""))
            .add(FormKeys.DESCRIPTION_KEY, Objects.toString(requirement.getDescription(), ""));
        // A requirement nobody describes is still listed, with the common fields only
        describers.forEach(describer -> describe(describer, requirement, submission, json));
        return json;
    }

    /**
     * Applies one describer to a requirement. A describer may come from any bundle, so one that fails is recorded
     * and skipped, and the requirement keeps what was written before it.
     *
     * @param describer the describer to ask
     * @param requirement a requirement that applies to the submission
     * @param submission the submission whose form is being served
     * @param json the requirement's object in the form's JSON
     */
    private static void describe(final RequirementDescriber describer, final Requirement requirement,
        final Submission submission, final JsonObjectBuilder json)
    {
        try {
            if (describer.handles(requirement)) {
                final JsonObjectBuilder own = Json.createObjectBuilder();
                describer.describe(requirement, submission, own);
                json.addAll(own);
            }
        } catch (final RuntimeException e) {
            LOGGER.error("{} failed to describe {}", describer.getClass().getName(), requirement.getPath(), e);
            ErrorLogger.logError(e, ErrorContext.of(describer.getClass(), "describe")
                .about(submission.getPath())
                .with("requirement", requirement.getPath()));
        }
    }

    /**
     * Whether a part of the schema applies to this submission now, as the condition evaluator decides.
     *
     * @param conditionable the schema part to test
     * @param submission the submission to test it against
     * @return {@code true} if it is shown
     */
    private boolean applies(final Conditionable conditionable, final Submission submission)
    {
        return this.conditions.applies(conditionable, submission);
    }
}
