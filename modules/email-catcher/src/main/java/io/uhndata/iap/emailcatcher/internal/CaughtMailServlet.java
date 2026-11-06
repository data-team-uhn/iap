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
package io.uhndata.iap.emailcatcher.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.Servlet;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.DateUtils;

/**
 * Serves every caught message, newest first by {@code caughtAt}, at {@code /CaughtMail.messages.json}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    resourceTypes = "mail/CaughtMailHomepage",
    selectors = "messages",
    extensions = "json",
    methods = { HttpConstants.METHOD_GET })
public class CaughtMailServlet extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = 5541051484949339022L;

    /** The multivalued properties, each written as an array, empty if the message has no value. */
    private static final List<String> MULTIVALUED_PROPERTIES = List.of("from", "replyTo", "to", "cc", "bcc", "headers");

    @Override
    // The caught mail is content, not a command's answer
    @SuppressWarnings("checkstyle:directResponseWriter")
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final List<Resource> caught = new ArrayList<>();
        // Only the messages: the folder's access control policy is a child node too
        request.getResource().getChildren().forEach(child -> {
            if (CaughtMailService.MESSAGE_TYPE.equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                caught.add(child);
            }
        });
        // Newest first. A message without a date sorts last.
        caught.sort(Comparator.comparing(CaughtMailServlet::caughtAt).reversed());

        final JsonArrayBuilder messages = Json.createArrayBuilder();
        caught.forEach(message -> messages.add(describe(message)));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.createObjectBuilder()
            .add("total", caught.size())
            .add("messages", messages)
            .build().toString());
    }

    /**
     * One caught message as JSON. A missing subject or body is an empty string, and a missing {@code caughtAt} is
     * left out.
     *
     * @param message the {@code mail:CaughtMessage} resource
     * @return the message's JSON
     */
    private static JsonObjectBuilder describe(final Resource message)
    {
        final ValueMap properties = message.getValueMap();
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("path", message.getPath())
            .add("subject", properties.get("subject", ""))
            .add("textBody", properties.get("textBody", ""))
            .add("htmlBody", properties.get("htmlBody", ""));
        final String when = DateUtils.toString(properties.get("caughtAt", Calendar.class));
        if (when != null) {
            json.add("caughtAt", when);
        }
        MULTIVALUED_PROPERTIES.forEach(name -> {
            final JsonArrayBuilder values = Json.createArrayBuilder();
            for (final String value : properties.get(name, new String[0])) {
                values.add(value);
            }
            json.add(name, values);
        });
        return json;
    }

    /**
     * When a message was caught, to sort by.
     *
     * @param message the {@code mail:CaughtMessage} resource
     * @return its {@code caughtAt}; the epoch if it has none
     */
    private static Calendar caughtAt(final Resource message)
    {
        final Calendar when = message.getValueMap().get("caughtAt", Calendar.class);
        if (when != null) {
            return when;
        }
        final Calendar epoch = Calendar.getInstance();
        epoch.setTimeInMillis(0);
        return epoch;
    }
}
