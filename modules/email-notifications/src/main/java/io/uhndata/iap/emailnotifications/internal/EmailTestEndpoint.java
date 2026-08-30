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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one fixed email to whoever asks, so that an administrator can tell whether the mail configuration of an
 * instance works without waiting for the platform to have a reason to write to somebody.
 *
 * <p>
 * Sending is asynchronous, so answering as soon as the message has been handed over would report only that it was
 * accepted — which is not the question this endpoint exists to answer. It therefore waits for the send to finish,
 * within a bounded budget, and says which of the three things happened. Whatever the caller is told, the outcome
 * reaches the log, since a send that outlives the wait would otherwise fail with nobody to hear it.
 * </p>
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
@SlingServletResourceTypes(resourceTypes = { "app/Homepage" }, selectors = { "emailtest" })
public final class EmailTestEndpoint extends SlingJakartaSafeMethodsServlet
{
    private static final long serialVersionUID = -3886647765025375822L;

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailTestEndpoint.class);

    private static final String SUBJECT = "IAP test message";

    private static final String TEXT = "Here is a test message from the Institutional Authorization Platform.";

    /**
     * What the caller is told instead of what the mail server said. That text routinely names the relay, its port
     * and the account the instance authenticates with, and this endpoint answers over the network.
     */
    private static final String SEE_THE_LOG = "The reason is in the instance log.";

    private static final String STILL_SENDING = "The message is still being sent. " + SEE_THE_LOG;

    @Reference
    private transient MailService mailService;

    /**
     * How long to wait for a send to finish before answering that it is still going. A request thread is cheap
     * here — this endpoint is administrator-only and driven by hand — but it must not be parked indefinitely by a
     * relay that never answers. Not {@code final} so that tests can shorten it.
     */
    private long sendTimeoutMillis = TimeUnit.SECONDS.toMillis(15);

    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
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

        final CompletableFuture<Void> sending;
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
     * Makes sure a send's outcome reaches the log whether or not anybody is still waiting for it. This is the half
     * that was missing: the future completes on a thread pool, so before it was attached every failure after the
     * message had been assembled reached neither the response nor the log.
     *
     * @param sending the send to watch
     * @return the same send, so the caller can also wait on it
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
     * Waits for the send, within the budget, and tells the caller which of the three things happened. Nothing is
     * logged here: {@link #logTheOutcome} has already done it, or will when the send finishes.
     *
     * @param sending the send to wait for
     * @param response the response to set the status on
     * @param out where to write the explanation
     * @throws IOException if the explanation cannot be written
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
            // Restore the flag: this thread belongs to the container, which is entitled to see that it was asked
            // to stop. The send itself carries on, and reports through logTheOutcome.
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            out.write(STILL_SENDING);
        }
    }
}
