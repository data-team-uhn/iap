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
package io.uhndata.iap.storednotifications.internal;

import java.io.IOException;

import jakarta.json.Json;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingJakartaAllMethodsServlet;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.storednotifications.api.StoredNotifications;
import io.uhndata.iap.utils.UserIds;

/**
 * Marks one stored notification as read: {@code POST /Notifications/…/<id>.markRead.json}.
 *
 * <p>
 * <strong>The {@code .json} extension is not optional.</strong> Sling reads the last dot-separated token as
 * the extension, so a bare {@code .markRead} matches no selector and falls through to the default POST servlet.
 * </p>
 *
 * <p>
 * Two answers have to agree: the repository must give the caller's own session a writable view, and the
 * notification must name them as its recipient. The second is not redundant. An administrative session
 * bypasses access control, so on the repository's answer alone an administrator opening their own bell would
 * mark everybody's notifications read. Marking an already-read notification read again is fine and does
 * nothing: reading twice is not an event.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { StoredNotifications.NOTIFICATION_RESOURCE_TYPE }, methods = { "POST" },
    selectors = { "markRead" }, extensions = { "json" })
public class MarkReadServlet extends SlingJakartaAllMethodsServlet
{
    private static final long serialVersionUID = -1198427543960813261L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MarkReadServlet.class);

    @Override
    protected void doPost(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    {
        final Resource target = request.getResource();
        // Whatever access control is configured, asked of the caller's own session
        final ModifiableValueMap writable = target.adaptTo(ModifiableValueMap.class);
        if (writable == null) {
            reply(response, HttpServletResponse.SC_FORBIDDEN, "This is not yours to mark");
            return;
        }
        // And whose it is, because an administrative session is handed a writable view of everything.
        // A notification naming nobody is nobody's to mark, which is why the recipient is asked first
        final String recipient = writable.get(StoredNotifications.RECIPIENT_PROPERTY, String.class);
        final String caller = UserIds.canonical(target.getResourceResolver());
        if (recipient == null || !recipient.equals(caller)) {
            LOGGER.warn("Somebody who may write {} is not its recipient, so the marker stays as it is",
                target.getPath());
            reply(response, HttpServletResponse.SC_FORBIDDEN, "This is not yours to mark");
            return;
        }
        try {
            writable.put(StoredNotifications.READ_PROPERTY, Boolean.TRUE);
            target.getResourceResolver().commit();
            reply(response, HttpServletResponse.SC_OK, null);
        } catch (final PersistenceException e) {
            LOGGER.error("Could not mark {} as read: {}", target.getPath(), e.getMessage(), e);
            ErrorLogger.logError(e,
                ErrorContext.of(MarkReadServlet.class, "markRead").about(target.getPath()));
            reply(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not record this");
        }
    }

    /**
     * Answers the caller in the one shape the interface reads.
     *
     * @param response where to write
     * @param status the HTTP status to answer with
     * @param error what went wrong, or {@code null} for a success
     * @throws IOException when the response cannot be written
     */
    private static void reply(final SlingJakartaHttpServletResponse response, final int status,
        final String error) throws IOException
    {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);
        final var body = Json.createObjectBuilder().add("status", error == null ? "ok" : "error");
        if (error != null) {
            body.add("error", error);
        }
        response.getWriter().write(body.build().toString());
    }
}
