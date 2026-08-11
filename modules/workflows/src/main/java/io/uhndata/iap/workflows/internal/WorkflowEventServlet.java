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
package io.uhndata.iap.workflows.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.TaskInstance;
import io.uhndata.iap.workflows.models.WorkflowsHomepage;

/**
 * The HTTP-to-workflow translator: a deliberately dumb servlet that turns a {@code POST} to a workflow-managed
 * homepage into a {@code create} domain event, hands it to the {@link WorkflowEngine engine}, and translates the
 * outcome back into HTTP. Which workflow runs — if any — is entirely the engine's and the definitions' business.
 *
 * <p>The outcome mapping follows the three layers of event acceptance: nothing waiting for the event is 409, a
 * firing user the repository refuses is 403, unusable data is 400, and a broken definition or failed machinery is
 * 500. When the workflow reports a created entity, the answer is a redirect to it.</p>
 *
 * <p>Binding this servlet to a homepage's resource type is what replaces direct-CRUD semantics with
 * workflow-managed ones for that homepage. As more homepages come under workflow control, they are added to the
 * {@code resourceTypes} here.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    // The homepages under workflow control, the entities that are themselves editable through a workflow, and the
    // user tasks of running instances. Literals where the owning module must not be depended on: submissions
    // depends on workflows, so workflows can only name its resource types, not import them.
    resourceTypes = { WorkflowsHomepage.RESOURCE_TYPE, TaskInstance.RESOURCE_TYPE, "sub/SubmissionsHomepage",
        "sub/Submission" },
    methods = { HttpConstants.METHOD_POST })
public class WorkflowEventServlet extends SlingJakartaAllMethodsServlet
{
    /** The domain event a POST to a homepage translates to. */
    public static final String CREATE_EVENT = "create";

    /** The domain event a POST to an entity that is editable through a workflow translates to. */
    public static final String SAVE_EVENT = "save";

    /** Named rather than imported, for the same reason as in the resource types above. */
    private static final String SUBMISSION_RESOURCE_TYPE = "sub/Submission";

    private static final long serialVersionUID = -6273669283473534077L;

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEventServlet.class);

    @Reference
    private transient WorkflowEngine engine;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        try {
            final String name = eventName(request);
            final WorkflowResult result =
                this.engine.receiveEvent(request.getResource(), new WorkflowEvent(name, payload(request)));
            final Object createdPath = result.getVariable(WorkflowResult.CREATED_PATH);
            if (createdPath instanceof String) {
                redirect(response, (String) createdPath);
            } else {
                reply(response, HttpServletResponse.SC_OK, "status", "completed");
            }
        } catch (final NoApplicableWorkflowException e) {
            reply(response, HttpServletResponse.SC_CONFLICT, "error", e.getMessage());
        } catch (final NotAuthorizedException e) {
            reply(response, HttpServletResponse.SC_FORBIDDEN, "error", e.getMessage());
        } catch (final InvalidPayloadException e) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, "error", e.getMessage());
        } catch (final WorkflowException e) {
            // A broken definition or failed machinery: not the client's fault, so log it for the deployer
            LOGGER.error("Executing the {} event on {} failed: {}", eventName(request),
                request.getResource().getPath(), e.getMessage(), e);
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "error", e.getMessage());
        }
    }

    /**
     * Which domain event a POST means, decided by what it was aimed at: posting to a homepage asks for something
     * to be created, posting to a user task says it has been decided. The servlet stays dumb — it names the event
     * and hands it over; what happens next is the definitions' business.
     *
     * @param request the incoming request
     * @return the domain event name
     */
    private String eventName(final SlingJakartaHttpServletRequest request)
    {
        final Resource target = request.getResource();
        if (target.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return TaskCompletion.COMPLETE_EVENT;
        }
        // Posting to an entity rather than to the homepage that holds them means changing that one, not making
        // another. Which is as far as the distinction needs to go for now: the other things one might do to a
        // submission — send it for review, withdraw it — are steps of its own workflow, so they arrive as user
        // tasks and are already told apart above.
        return target.isResourceType(SUBMISSION_RESOURCE_TYPE) ? SAVE_EVENT : CREATE_EVENT;
    }

    /**
     * The event payload: every ordinary request parameter, single values as strings and repeated ones as string
     * arrays. Sling's own control parameters, {@code :}-prefixed, are the transport's business and stay out.
     *
     * @param request the incoming request
     * @return the payload for the translated event
     */
    private Map<String, Object> payload(final SlingJakartaHttpServletRequest request)
    {
        return request.getRequestParameterMap().entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith(":") && !"_charset_".equals(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                final RequestParameter[] values = entry.getValue();
                return values.length == 1 ? values[0].getString()
                    : Arrays.stream(values).map(RequestParameter::getString).toArray(String[]::new);
            }));
    }

    /**
     * Answers with a redirect to a created entity.
     *
     * @param response the response to write
     * @param path the created entity's path
     * @throws IOException when the response cannot be written
     */
    private void redirect(final SlingJakartaHttpServletResponse response, final String path) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
        response.setHeader("Location", path);
        reply(response, HttpServletResponse.SC_MOVED_TEMPORARILY, "path", path);
    }

    /**
     * Writes a one-entry JSON body with the given status.
     *
     * @param response the response to write
     * @param status the HTTP status code
     * @param key the single JSON key
     * @param value its value
     * @throws IOException when the response cannot be written
     */
    private void reply(final SlingJakartaHttpServletResponse response, final int status, final String key,
        final String value) throws IOException
    {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.createObjectBuilder().add(key, value).build().toString());
    }
}
