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
import io.uhndata.iap.utils.JsonResponses;

/**
 * Records a decision about one recorded error, at {@code POST /LoggedErrors/<fingerprint>.acknowledge.json}.
 *
 * <p>
 * The {@code resolution} parameter names a tag in the {@value LoggedError#TRIAGE_CATEGORY} category, other than
 * {@value LoggedError#UNACKNOWLEDGED_TAG}. The optional {@code note} says why. The decision is added as a child of the
 * error, beside the earlier ones. The error's triage tags are then computed from its newest decision, so this servlet
 * writes no tags.
 * </p>
 *
 * <p>
 * The decision is written through the caller's own session, so the repository's access control decides who may
 * record one.
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

    /** The name of a decision node, before the number that makes it unique, as in {@code decision1}. */
    private static final String DECISION_NAME = "decision";

    @Reference
    private transient TagManager tagManager;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final Resource target = request.getResource();
        // A Sling Model adapts a resource of any type, so the type is checked first
        final LoggedError error =
            target.isResourceType(LoggedError.RESOURCE_TYPE) ? target.adaptTo(LoggedError.class) : null;
        if (error == null) {
            JsonResponses.writeError(response, HttpServletResponse.SC_NOT_FOUND, "This is not a recorded error");
            return;
        }
        final String resolution = request.getParameter("resolution");
        if (!isTriageTag(resolution)) {
            // The rejected value goes to the log, not into the answer, which never echoes a request value
            LOGGER.debug("Refusing to acknowledge {} with the unknown resolution {}", target.getPath(), resolution);
            JsonResponses.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "resolution must name one of the " + LoggedError.TRIAGE_CATEGORY + " tags");
            return;
        }
        try {
            final Resource decision = record(target, error, resolution, request.getParameter("note"));
            target.getResourceResolver().commit();
            JsonResponses.write(response, HttpServletResponse.SC_OK, "ok",
                Json.createObjectBuilder().add("acknowledgement", decision.getPath()));
        } catch (final PersistenceException e) {
            LOGGER.warn("Could not acknowledge the error at {}: {}", target.getPath(), e.getMessage(), e);
            JsonResponses.writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Could not record the decision");
        }
    }

    /**
     * Adds a decision to the error it is about.
     *
     * @param target the resource that records the error
     * @param error the same, as a model
     * @param resolution the name of a triage tag, such as {@code known-issue}
     * @param note why, in the words of whoever decided; left out if {@code null} or blank
     * @return the new decision node, not yet committed
     * @throws PersistenceException if the decision cannot be created
     */
    private Resource record(final Resource target, final LoggedError error, final String resolution,
        final String note) throws PersistenceException
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "err:Acknowledgement");
        properties.put("resolution", resolution);
        // Once the error's count passes this, it needs attention again
        properties.put("acknowledgedOccurrences", error.getOccurrences());
        if (note != null && !note.isBlank()) {
            properties.put("note", note);
        }
        return target.getResourceResolver().create(target, freeName(target), properties);
    }

    /**
     * The first name of the form {@code decision<n>} that no child of the error has.
     *
     * @param target the resource that records the error
     * @return a name free among the error's children, such as {@code decision2}
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
     * Whether a resolution names a tag defined in the triage category, other than
     * {@value LoggedError#UNACKNOWLEDGED_TAG}. A tag a deployment adds to the category qualifies.
     *
     * @param resolution the {@code resolution} parameter, or {@code null} if the request has none
     * @return {@code true} if the resolution names such a tag
     */
    private boolean isTriageTag(final String resolution)
    {
        if (resolution == null || resolution.isBlank() || LoggedError.UNACKNOWLEDGED_TAG.equals(resolution)) {
            return false;
        }
        final List<TagDefinition> triage = this.tagManager.findDefinitions(LoggedError.TRIAGE_CATEGORY, null);
        return triage.stream().anyMatch(definition -> resolution.equals(definition.getName()));
    }
}
