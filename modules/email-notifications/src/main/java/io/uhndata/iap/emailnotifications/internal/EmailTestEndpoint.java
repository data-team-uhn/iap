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
package io.uhndata.iap.emailnotifications.internal;

import java.io.IOException;
import java.io.Writer;

import jakarta.mail.MessagingException;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.SlingJakartaSafeMethodsServlet;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Sends one fixed email to whoever asks, so that an administrator can tell whether the mail configuration of an
 * instance works without waiting for the platform to have a reason to write to somebody.
 *
 * <p>
 * Reserved to administrators: it sends mail to an arbitrary address, which is not something an instance should let
 * anyone else do.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "iap/Homepage" }, selectors = { "emailtest" })
public final class EmailTestEndpoint extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -3886647765025375822L;

    private static final String SUBJECT = "IAP test message";

    private static final String TEXT = "Here is a test message from the Institutional Authorization Platform.";

    @Reference
    private transient MailService mailService;

    @Override
    public void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final Writer out = response.getWriter();
        if (!"admin".equals(request.getRemoteUser())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.write("Only admin can perform this operation.");
            return;
        }

        final String fromEmail = request.getParameter("fromEmail");
        final String fromName = request.getParameter("fromName");
        final String toEmail = request.getParameter("toEmail");
        final String toName = request.getParameter("toName");
        if (fromEmail == null || fromName == null || toEmail == null || toName == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("Missing required URL parameters");
            return;
        }

        try {
            final MessageBuilder message = this.mailService.getMessageBuilder()
                .from(fromEmail, fromName)
                .to(toEmail, toName)
                .replyTo(fromEmail)
                .subject(SUBJECT)
                .text(TEXT);
            if ("true".equals(request.getParameter("isHtml"))) {
                message.html("<html><head><title>Rich Text</title></head><body><p>" + TEXT + "</p></body></html>");
            }
            this.mailService.sendMessage(message.build());
            response.setStatus(HttpServletResponse.SC_OK);
            out.write("Email prepared for sending");
        } catch (final MessagingException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("Could not send the message: " + e.getMessage());
        }
    }
}
