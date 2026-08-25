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
package io.uhndata.iap.llm.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletPaths;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMMessage;

/**
 * Servlet that proxies POST requests to the active LLM provider's client.
 * The provider and its credentials stay on the server; clients only send message content.
 *
 * <p>Endpoint: {@code POST /system/llm/chat}
 *
 * <p>Single-turn request:
 * <pre>{"message": "Hello", "system": "(optional)"}</pre>
 *
 * <p>Multi-turn request:
 * <pre>{"messages": [{"role": "user", "content": "Hello"}, ...], "system": "(optional)"}</pre>
 *
 * <p>Response: {@code {"response": "..."}}
 *
 * <p>Error response: {@code {"error": "..."}} with an appropriate HTTP status code.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(LLMServlet.PATH)
public class LLMServlet extends SlingJakartaAllMethodsServlet
{
    /** The path this servlet answers on. */
    public static final String PATH = "/system/llm/chat";

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMServlet.class);

    private static final long serialVersionUID = 4938271560024819437L;

    @Reference
    private transient LLMClientFactory llmClientFactory;

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json;charset=UTF-8");

        final JsonObject body;
        try (JsonReader reader = Json.createReader(request.getReader())) {
            body = reader.readObject();
        } catch (Exception e) {
            sendError(response, 400, "Invalid JSON request body");
            return;
        }

        final String system = body.getString("system", null);
        final String message = body.getString("message", null);
        final JsonArray messages = body.getJsonArray("messages");

        try {
            final LLMClient client = this.llmClientFactory.getActiveClient();
            final String reply;
            if (messages != null) {
                reply = client.chat(system, toMessageList(messages));
            } else if (StringUtils.isNotBlank(message)) {
                reply = StringUtils.isNotBlank(system)
                    ? client.chat(system, message)
                    : client.chat(message);
            } else {
                sendError(response, 400, "Request body must include 'message' or 'messages'");
                return;
            }

            try (Writer out = response.getWriter()) {
                out.write(Json.createObjectBuilder().add("response", reply).build().toString());
            }
        } catch (IOException e) {
            // What went wrong can name the endpoint that was unreachable, or quote the provider's own
            // answer, so it is logged rather than sent to whoever asked.
            LOGGER.warn("An LLM chat request failed", e);
            sendError(response, 502, "The LLM request could not be completed");
        }
    }

    private List<LLMMessage> toMessageList(final JsonArray messages)
    {
        final List<LLMMessage> result = new ArrayList<>(messages.size());
        for (final JsonObject msg : messages.getValuesAs(JsonObject.class)) {
            result.add(new LLMMessage(msg.getString("role"), msg.getString("content")));
        }
        return result;
    }

    private void sendError(final SlingJakartaHttpServletResponse response, final int status, final String message)
        throws IOException
    {
        response.setStatus(status);
        try (Writer out = response.getWriter()) {
            out.write(Json.createObjectBuilder().add("error", message).build().toString());
        }
    }
}
