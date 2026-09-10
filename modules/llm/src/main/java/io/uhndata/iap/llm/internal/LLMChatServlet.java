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
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.utils.PaginatedJsonResponse;

/**
 * Servlet that proxies POST requests to the active LLM provider's client.
 * The provider and its credentials stay on the server; clients only send message content.
 *
 * <p>Endpoint: {@code POST /system/llm/chat}
 *
 * <p>Single-turn request:
 * {@snippet lang=json :
 * {"message": "Hello", "system": "(optional)"}
 * }
 *
 * <p>Multi-turn request:
 * {@snippet lang=json :
 * {"messages": [{"role": "user", "content": "Hello"}, ...], "system": "(optional)"}
 * }
 *
 * <p>Response: {@code {"response": "..."}}
 *
 * <p>Error response: {@code {"error": "..."}} with an appropriate HTTP status code.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletPaths(LLMChatServlet.PATH)
public class LLMChatServlet extends SlingJakartaAllMethodsServlet
{
    /** The path this servlet answers on. */
    public static final String PATH = "/system/llm/chat";

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMChatServlet.class);

    private static final long serialVersionUID = 4938271560024819437L;

    @Reference
    private transient LLMClientFactory llmClientFactory;

    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
        justification = "LLMClient#chat is documented @NotNull, but that is a contract on well-behaved "
            + "implementations, not something the JVM enforces; a provider client is exactly the kind of "
            + "third-party-facing code worth checking anyway")
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        response.setContentType("application/json;charset=UTF-8");

        final JsonObject body;
        try (JsonReader reader = Json.createReader(request.getReader())) {
            body = reader.readObject();
        } catch (Exception e) {
            PaginatedJsonResponse.writeError(response, 400, "Invalid JSON request body");
            return;
        }

        final String system = body.getString("system", null);
        final String message = body.getString("message", null);
        final List<LLMMessage> conversation;
        if (body.containsKey("messages")) {
            conversation = toMessageList(body.get("messages"));
            if (conversation == null) {
                PaginatedJsonResponse.writeError(response, 400,
                    "'messages' must be an array of objects, each with a 'role' and a 'content' string");
                return;
            }
        } else {
            conversation = null;
        }

        try {
            final LLMClient client = this.llmClientFactory.getActiveClient();
            final String reply;
            if (conversation != null) {
                reply = client.chat(system, conversation);
            } else if (StringUtils.isNotBlank(message)) {
                reply = StringUtils.isNotBlank(system)
                    ? client.chat(system, message)
                    : client.chat(message);
            } else {
                PaginatedJsonResponse.writeError(response, 400, "Request body must include 'message' or 'messages'");
                return;
            }
            if (reply == null) {
                LOGGER.warn("An LLM chat request returned no reply");
                PaginatedJsonResponse.writeError(response, 502, "The LLM request could not be completed");
                return;
            }
            try (Writer out = response.getWriter()) {
                out.write(Json.createObjectBuilder().add("response", reply).build().toString());
            }
        } catch (IOException e) {
            // What went wrong can name the endpoint that was unreachable, or quote the provider's own
            // answer, so it is logged rather than sent to whoever asked.
            LOGGER.warn("An LLM chat request failed", e);
            PaginatedJsonResponse.writeError(response, 502, "The LLM request could not be completed");
        }
    }

    /**
     * Turn the request's {@code messages} value into conversation turns, refusing anything that is not an
     * array of objects each carrying a string {@code role} and a string {@code content} — the shape is caller
     * input, not guaranteed by the JSON parse alone.
     *
     * @param value whatever the request sent as {@code messages}
     * @return the conversation turns, or {@code null} when the value is not shaped as expected
     */
    private static List<LLMMessage> toMessageList(final JsonValue value)
    {
        if (value.getValueType() != JsonValue.ValueType.ARRAY) {
            return null;
        }
        final JsonArray messages = value.asJsonArray();
        final List<LLMMessage> result = new ArrayList<>(messages.size());
        for (final JsonValue entry : messages) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                return null;
            }
            final JsonObject turn = entry.asJsonObject();
            final String role = stringProperty(turn, "role");
            final String content = stringProperty(turn, "content");
            if (role == null || content == null) {
                return null;
            }
            result.add(new LLMMessage(role, content));
        }
        return result;
    }

    /**
     * Read a string property, without the {@link ClassCastException} {@link JsonObject#getString} throws when
     * the property exists but is not a string.
     *
     * @param object the JSON object to read from
     * @param key the property to read
     * @return the string value, or {@code null} when the property is missing or not a string
     */
    private static String stringProperty(final JsonObject object, final String key)
    {
        final JsonValue value = object.get(key);
        return value != null && value.getValueType() == JsonValue.ValueType.STRING
            ? ((JsonString) value).getString() : null;
    }
}
