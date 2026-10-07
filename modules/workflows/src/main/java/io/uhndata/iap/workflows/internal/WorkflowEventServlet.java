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
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.TaskInstance;

/**
 * The HTTP door into the {@link WorkflowEngine}: it turns a {@code POST} into a domain event and answers with
 * what the engine made of it. Which workflow runs, if any, is the engine's and the definitions' business.
 *
 * <p>The outcome mapping follows the three layers of event acceptance: nothing waiting for the event is 409, a
 * firing user the repository refuses is 403, unusable data is 400, and a broken definition or failed machinery is
 * 500. When the workflow reports a created entity, the answer is a redirect to it.</p>
 *
 * <p>The event a POST means is the target's, unless a selector names one:
 * {@code POST <path>.attachDocument.json} sends that message instead of the default. Nothing is registered per
 * message — the definitions decide which messages exist, and a message nothing is waiting for is a 409.</p>
 *
 * <p><strong>The extension is not optional.</strong> Sling reads the last dot-separated token of a URL as the
 * extension, so {@code <path>.attachDocument} names no selector at all: the POST falls through to the target's
 * default event, succeeds at being the wrong thing, and comes back as a refusal from whichever handler that
 * default reached — which reads as the named event being broken rather than as never having been asked for.</p>
 *
 * <p>It is not registered by type here. {@link WorkflowEventServletRegistrar} binds it to the resource types the
 * system workflows target, so a type nothing targets is still directly writable. The one exception is the
 * {@code .import} extension, forwarded untouched to the Sling POST servlet, so that an administrator can still
 * import content. Any other Sling operation is refused.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class WorkflowEventServlet extends SlingJakartaAllMethodsServlet
{
    /** The domain event a POST to a workflow-managed homepage translates to. */
    public static final String CREATE_EVENT = "create";

    /** The domain event a POST to an entity that is editable through a workflow translates to. */
    public static final String SAVE_EVENT = "save";

    /** The extension that bypasses the engine, for the Sling POST servlet. */
    static final String IMPORT_EXTENSION = "import";

    /** The request parameter naming a Sling POST servlet operation. */
    private static final String OPERATION_PARAMETER = ":operation";

    /** The key a refusal is reported under in the JSON answer. */
    private static final String ERROR_KEY = "error";

    /**
     * The supertype every entity homepage carries, which is how a POST that means "make me one of these" is told
     * from one that means "change this one" without naming a single homepage type.
     */
    private static final String HOMEPAGE_RESOURCE_TYPE = "data/EntityHomepage";

    /** The resource type for the default Sling POST servlet. */
    private static final String SLING_DEFAULT_TYPE = "sling/servlet/default";

    private static final long serialVersionUID = 4735148026553286411L;

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEventServlet.class);

    private final transient WorkflowEngine engine;

    /**
     * Constructor.
     *
     * @param engine the engine receiving the translated events
     */
    WorkflowEventServlet(final WorkflowEngine engine)
    {
        this.engine = engine;
    }

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException, ServletException
    {
        if (IMPORT_EXTENSION.equals(request.getRequestPathInfo().getExtension())) {
            final RequestDispatcherOptions options = new RequestDispatcherOptions();
            options.setForceResourceType(SLING_DEFAULT_TYPE);
            Objects.requireNonNull(request.getRequestDispatcher(request.getResource(), options),
                "Sling always dispatches to an existing resource").forward(request, response);
            return;
        }
        // Read as an event, a Sling operation arrives empty and answers 200 having done nothing
        if (request.getRequestParameter(OPERATION_PARAMETER) != null) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_KEY,
                "Sling operations are not accepted here: send a workflow event, or an HTTP DELETE to remove it");
            return;
        }
        try {
            final String name = eventName(request);
            final WorkflowResult result =
                this.engine.receiveEvent(request.getResource(), new WorkflowEvent(name, payload(request)));
            final Object createdPath = result.getVariable(WorkflowResult.CREATED_PATH_VARIABLE);
            if (createdPath instanceof String) {
                redirect(response, (String) createdPath);
            } else {
                reply(response, HttpServletResponse.SC_OK, "status", "completed");
            }
        } catch (final NoApplicableWorkflowException | InvalidStateException e) {
            // Both are "not here, not now" rather than "not you" or "not like that": nothing was waiting for this
            // event, or something was and the target is not in a state that admits it
            reply(response, HttpServletResponse.SC_CONFLICT, ERROR_KEY, e.getMessage());
        } catch (final NotAuthorizedException e) {
            reply(response, HttpServletResponse.SC_FORBIDDEN, ERROR_KEY, e.getMessage());
        } catch (final InvalidPayloadException e) {
            reply(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_KEY, e.getMessage());
        } catch (final WorkflowException e) {
            // A broken definition or failed machinery: not the client's fault, and the one refusal here that
            // nobody outside can act on. Recorded as well as logged, because whoever has to fix it is the
            // deployer, and /LoggedErrors is where they look
            LOGGER.error("Executing the {} event on {} failed: {}", eventName(request),
                request.getResource().getPath(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(getClass(), "receiveEvent")
                .about(request.getResource())
                .actingFor(request.getResourceResolver().getUserID())
                .with("event", eventName(request)));
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_KEY, e.getMessage());
        }
    }

    /**
     * Which domain event a POST means: the one a selector names, otherwise the target's default. Posting to a
     * homepage asks for something to be created, posting to a user task says it has been decided.
     *
     * @param request the incoming request
     * @return the domain event name
     */
    private String eventName(final SlingJakartaHttpServletRequest request)
    {
        final String named = request.getRequestPathInfo().getSelectorString();
        if (named != null && !named.isEmpty()) {
            return named;
        }
        final Resource target = request.getResource();
        if (target.isResourceType(TaskInstance.RESOURCE_TYPE)) {
            return TaskCompletion.COMPLETE_EVENT;
        }
        // Posting to an entity rather than to the homepage that holds them means changing that one, not making
        // another. Which is as far as the default needs to go: the other things one might do to a submission —
        // send it for review, withdraw it — are steps of its own workflow, so they arrive as user tasks and are
        // already told apart above, and the other things one might do to a workflow version — promote it, draft
        // a copy of it — name their event outright.
        return target.isResourceType(HOMEPAGE_RESOURCE_TYPE) ? CREATE_EVENT : SAVE_EVENT;
    }

    /**
     * The event payload: every ordinary request parameter, single values as strings and repeated ones as string
     * arrays. Sling's own control parameters, {@code :}-prefixed, are the transport's business and stay out.
     *
     * <p>An uploaded file is the one thing that does not become a string. Reading it as one would decode its bytes
     * as text and corrupt anything that is not text, so it arrives as an {@link EventAttachment} for a handler that
     * has somewhere to put it. Whether a given activity wants one is the handler's business; this only declines to
     * destroy it on the way in.</p>
     *
     * @param request the incoming request
     * @return the payload for the translated event
     */
    private Map<String, Object> payload(final SlingJakartaHttpServletRequest request)
    {
        return request.getRequestParameterMap().entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith(":") && !"_charset_".equals(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> value(entry.getValue())));
    }

    /**
     * What one parameter contributes to the payload: a file, a string, or an array of strings.
     *
     * <p>Package-visible so a test can drive it with a real file part: the mock request the other tests use
     * turns everything it is given into a form field, so the one case worth pinning here is the one it cannot
     * express.</p>
     *
     * @param values everything sent under one name
     * @return the payload value
     */
    static Object value(final RequestParameter[] values)
    {
        if (values.length == 1 && !values[0].isFormField()) {
            return new RequestParameterAttachment(values[0]);
        }
        return values.length == 1 ? values[0].getString()
            : Arrays.stream(values).map(RequestParameter::getString).toArray(String[]::new);
    }

    /**
     * A multipart part, offered to a handler as an attachment.
     *
     * <p>Holds the part rather than its bytes: Sling has already buffered it wherever it saw fit, and copying it
     * into the heap to hand it over would double that for no reason — a handler writes it straight into the
     * repository.</p>
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class RequestParameterAttachment implements EventAttachment
    {
        private final RequestParameter part;

        RequestParameterAttachment(final RequestParameter part)
        {
            this.part = part;
        }

        @Override
        public String getFileName()
        {
            return this.part.getFileName();
        }

        @Override
        public String getMimeType()
        {
            return this.part.getContentType();
        }

        @Override
        public InputStream openStream() throws IOException
        {
            // Sling declares this nullable, and the annotation is load-bearing rather than defensive: a part with
            // nothing to read is a broken request rather than an empty file, since a zero-byte upload still has a
            // stream. Asserted rather than branched on, so there is no path a test cannot reach
            return Objects.requireNonNull(this.part.getInputStream(), "A file part always has content to read");
        }
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
