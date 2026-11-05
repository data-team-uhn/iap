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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.utils.UserIds;

/**
 * Sends one fixed email, so that an administrator can check the mail configuration of an instance. It needs the
 * {@code fromEmail}, {@code fromName}, {@code toEmail} and {@code toName} parameters, and takes {@code isHtml=true} to
 * add an HTML body.
 *
 * <p>
 * It waits up to fifteen seconds for the send to finish. It answers {@code 200} if the message was sent, {@code 500}
 * if it was not, and {@code 202} if it is still being sent. The outcome is logged in every case.
 * </p>
 *
 * <p>
 * Only the {@code admin} user may use it, since it mails any address.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { "app/Homepage" }, selectors = { "emailtest" })
public final class EmailTestEndpoint extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -3886647765025375822L;

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailTestEndpoint.class);

    private static final String MESSAGE_SUBJECT = "IAP test message";

    private static final String MESSAGE_TEXT = "Here is a test message from the Institutional Authorization Platform.";

    /**
     * What the caller is told instead of what the mail server said. That text can name the relay, its port and the
     * account the instance signs in with.
     */
    private static final String SEE_THE_LOG = "The reason is in the instance log.";

    private static final String STILL_SENDING = "The message is still being sent. " + SEE_THE_LOG;

    // Dynamic and greedy, so that a mail service registered later with a higher ranking replaces this one at once
    @Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile MailService mailService;

    /** How long to wait for a send to finish before answering that it is still going. Not final, for the tests. */
    private long sendTimeoutMillis = TimeUnit.SECONDS.toMillis(15);

    @Override
    // This test endpoint answers in plain text
    @SuppressWarnings("checkstyle:directResponseWriter")
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
        throws IOException
    {
        final Writer out = response.getWriter();
        if (!"admin".equals(UserIds.canonical(request.getResourceResolver()))) {
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

        final CompletableFuture<Void> sending;
        try {
            final MessageBuilder message = this.mailService.getMessageBuilder()
                .from(fromEmail, fromName)
                .to(toEmail, toName)
                .replyTo(fromEmail)
                .subject(MESSAGE_SUBJECT)
                .text(MESSAGE_TEXT);
            if ("true".equals(request.getParameter("isHtml"))) {
                message.html("<html><head><title>Rich Text</title></head><body><p>" + MESSAGE_TEXT
                    + "</p></body></html>");
            }
            sending = logTheOutcome(this.mailService.sendMessage(message.build()));
        } catch (final MessagingException e) {
            LOGGER.error("The email test endpoint could not assemble its message: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("Could not send the message. " + SEE_THE_LOG);
            return;
        }

        reportTheOutcome(sending, response, out);
    }

    /**
     * Logs the outcome of a send when it finishes, whether or not the request is still waiting for it.
     *
     * @param sending the send to watch
     * @return a future that completes as the send does, once the outcome is logged
     */
    private static CompletableFuture<Void> logTheOutcome(final CompletableFuture<Void> sending)
    {
        return sending.whenComplete((ignored, failure) -> {
            if (failure == null) {
                LOGGER.info("The email test endpoint sent its message.");
            } else {
                LOGGER.error("The email test endpoint could not send its message: {}", failure.getMessage(), failure);
            }
        });
    }

    /**
     * Waits for the send, up to the time limit, and answers whether it was sent, failed or is still going. The
     * outcome is logged by {@link #logTheOutcome}.
     *
     * @param sending the send to wait for
     * @param response the HTTP response to write into
     * @param out the writer of that response
     * @throws IOException if the answer cannot be written
     */
    private void reportTheOutcome(final CompletableFuture<Void> sending,
        final SlingJakartaHttpServletResponse response, final Writer out) throws IOException
    {
        try {
            sending.get(this.sendTimeoutMillis, TimeUnit.MILLISECONDS);
            response.setStatus(HttpServletResponse.SC_OK);
            out.write("The message was sent.");
        } catch (final ExecutionException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("Could not send the message. " + SEE_THE_LOG);
        } catch (final TimeoutException e) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            out.write(STILL_SENDING);
        } catch (final InterruptedException e) {
            // The thread belongs to the container, which must see the interrupt. The send carries on, and its
            // outcome is still logged.
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            out.write(STILL_SENDING);
        }
    }
}
