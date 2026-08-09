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
package io.uhndata.iap.errortracking.internal;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.models.LoggedError;
import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.models.TagDefinition;

/**
 * Records a decision about one recorded error, at {@code POST /LoggedErrors/<fingerprint>.acknowledge}.
 *
 * <p>
 * Takes a {@code resolution} naming one of the tags in the {@value LoggedError#TRIAGE_CATEGORY} category, and an
 * optional {@code note} saying why. The decision is appended as a child of the error rather than replacing anything:
 * an error that was acknowledged, recurred, and was acknowledged again keeps all three facts. The triage markers on
 * the error are then derived from the newest decision, so nothing here writes tags directly.
 * </p>
 *
 * <p>
 * The write goes through the caller's own session, so who may triage an error is a question for the repository's
 * access control rather than for this servlet.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { LoggedError.RESOURCE_TYPE }, methods = { "POST" },
    selectors = { "acknowledge" })
public class AcknowledgeServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = 6106542097861185354L;

    private static final Logger LOGGER = LoggerFactory.getLogger(AcknowledgeServlet.class);

    /** The name given to the node recording a decision, before a number is appended to make it unique. */
    private static final String DECISION_NAME = "decision";

    @Reference
    private transient TagManager tagManager;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        final Resource target = request.getResource();
        // The resource type is checked before adapting, never by adapting: a Sling Model registered for one
        // resource type will happily adapt a resource of an unrelated one
        final LoggedError error =
            target.isResourceType(LoggedError.RESOURCE_TYPE) ? target.adaptTo(LoggedError.class) : null;
        if (error == null) {
            reply(response, HttpServletResponse.SC_NOT_FOUND, "This is not a recorded error");
            return;
        }
        final String resolution = request.getParameter("resolution");
        if (!isTriageTag(resolution)) {
            // What was rejected goes to the log rather than into the answer: the caller already knows what it sent,
            // and an error response is not the place to hand a value from the request back to a browser
            LOGGER.debug("Refusing to acknowledge {} with the unknown resolution {}", target.getPath(), resolution);
            reply(response, HttpServletResponse.SC_BAD_REQUEST,
                "resolution must name one of the " + LoggedError.TRIAGE_CATEGORY + " tags");
            return;
        }
        try {
            final Resource decision = record(target, error, resolution, request.getParameter("note"));
            target.getResourceResolver().commit();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(Json.createObjectBuilder()
                .add("status", "ok")
                .add("acknowledgement", decision.getPath())
                .build().toString());
        } catch (final PersistenceException e) {
            LOGGER.warn("Could not acknowledge the error at {}: {}", target.getPath(), e.getMessage(), e);
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not record the decision");
        }
    }

    /**
     * Appends the decision to the error it is about.
     *
     * @param target the resource recording the error
     * @param error the same, as a model
     * @param resolution what was decided
     * @param note why, may be {@code null}
     * @return the newly created node, not yet committed
     * @throws PersistenceException if the decision cannot be created
     */
    private Resource record(final Resource target, final LoggedError error, final String resolution,
        final String note) throws PersistenceException
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "err:Acknowledgement");
        properties.put("resolution", resolution);
        // What the count has reached now is what a later occurrence is measured against: pass it, and the error goes
        // back to needing attention by itself
        properties.put("acknowledgedOccurrences", error.getOccurrences());
        if (note != null && !note.isBlank()) {
            properties.put("note", note);
        }
        return target.getResourceResolver().create(target, freeName(target), properties);
    }

    /**
     * A name no decision on this error has taken yet. Decisions accumulate, so the first one cannot simply own the
     * name.
     *
     * @param target the resource recording the error
     * @return a name free among the error's children
     */
    private static String freeName(final Resource target)
    {
        int suffix = 1;
        while (target.getChild(DECISION_NAME + suffix) != null) {
            suffix++;
        }
        return DECISION_NAME + suffix;
    }

    /**
     * Whether a resolution names one of the triage tags. Checked against the definitions rather than a list in code,
     * so a deployment that adds a triage tag of its own can use it without a change here.
     *
     * @param resolution what the caller asked for, may be {@code null}
     * @return {@code true} when a tag by that name is defined in the triage category
     */
    private boolean isTriageTag(final String resolution)
    {
        if (resolution == null || resolution.isBlank() || LoggedError.UNACKNOWLEDGED.equals(resolution)) {
            return false;
        }
        final List<TagDefinition> triage = this.tagManager.findDefinitions(LoggedError.TRIAGE_CATEGORY, null);
        return triage.stream().anyMatch(definition -> resolution.equals(definition.getName()));
    }

    /**
     * Answers with a status and an explanation.
     *
     * @param response the response to write to
     * @param status the HTTP status to answer with
     * @param error what went wrong
     * @throws IOException if the response cannot be written
     */
    private static void reply(final SlingJakartaHttpServletResponse response, final int status, final String error)
        throws IOException
    {
        response.setStatus(status);
        response.getWriter().write(Json.createObjectBuilder()
            .add("status", "error")
            .add("error", error)
            .build().toString());
    }
}
