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
package io.uhndata.iap.emailnotifications.api;

import java.util.Map;
import java.util.Set;

import jakarta.mail.Header;
import jakarta.mail.MessagingException;

import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.emailnotifications.internal.EmailTemplateRenderer;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Fills in email templates, and sends emails through Sling's mail service.
 *
 * <p>
 * The send methods do not wait for the email to go out. The mail service sends it on a thread pool, so a refused
 * address or an unreachable relay fails after the method has returned. That failure is logged and recorded through
 * {@link ErrorLogger}, and the caller never sees it.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class EmailUtils
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailUtils.class);

    /** Only static methods. */
    private EmailUtils()
    {
        // Utility class
    }

    /**
     * Fills in a template without escaping its values, for a subject or a plain text body.
     *
     * <p>
     * Templates are written in <a href="https://velocity.apache.org/engine/devel/user-guide.html">Apache Velocity</a>,
     * in strict mode. A variable that nobody supplied is an error, so write {@code #if($name)} around one that may be
     * missing.
     * </p>
     *
     * @param template the Velocity template text, or {@code null} for an email with no such part
     * @param values the values the template may use, by variable name
     * @return the filled in text, or {@code null} if the template is {@code null}
     * @throws EmailTemplateException if the template does not parse, or uses a variable that is not among the values
     */
    @Nullable
    public static String render(@Nullable final String template, @NotNull final Map<String, ?> values)
    {
        return EmailTemplateRenderer.get().render(template, values, false);
    }

    /**
     * Fills in an HTML template, and escapes each value it puts in. Otherwise the same as {@link #render}.
     *
     * @param template the Velocity template text, or {@code null} for an email with no HTML body
     * @param values the values the template may use, by variable name
     * @return the filled in HTML, or {@code null} if the template is {@code null}
     * @throws EmailTemplateException if the template does not parse, or uses a variable that is not among the values
     */
    @Nullable
    public static String renderHtml(@Nullable final String template, @NotNull final Map<String, ?> values)
    {
        return EmailTemplateRenderer.get().render(template, values, true);
    }

    /**
     * Sends an email the way {@link #sendHtmlEmail} does if it has an HTML body, and the way {@link #sendTextEmail}
     * does otherwise.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if the message cannot be assembled, such as for an address that does not parse
     * @throws IllegalArgumentException if the email has no body at all
     * @since 0.1.0
     */
    public static void sendEmail(@NotNull final Email email, @NotNull final MailService mailService)
        throws MessagingException
    {
        if (email.getHtmlBody() == null) {
            sendTextEmail(email, mailService);
        } else {
            sendHtmlEmail(email, mailService);
        }
    }

    /**
     * Sends the plain text body of an email, without its HTML body or its attachments.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if the message cannot be assembled, such as for an address that does not parse
     * @throws IllegalArgumentException if the email has no plain text body
     */
    public static void sendTextEmail(@NotNull final Email email, @NotNull final MailService mailService)
        throws MessagingException
    {
        final String textBody = email.getTextBody();
        if (textBody == null) {
            throw new IllegalArgumentException("The email has no plain text body, it cannot be sent as plain text");
        }
        final MessageBuilder message = mailService.getMessageBuilder()
            .subject(email.getSubject())
            .text(textBody);
        addSender(message, email);
        addRecipient(message, email);
        addExtraHeaders(message, email);
        handOver(mailService, message);
    }

    /**
     * Sends the HTML body of an email with its attachments, and its plain text body if it has one.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if the message cannot be assembled, such as for an address that does not parse
     * @throws IllegalArgumentException if the email has no HTML body
     */
    public static void sendHtmlEmail(@NotNull final Email email, @NotNull final MailService mailService)
        throws MessagingException
    {
        final String htmlBody = email.getHtmlBody();
        if (htmlBody == null) {
            throw new IllegalArgumentException("The email has no HTML body, it cannot be sent as HTML");
        }
        final MessageBuilder message = mailService.getMessageBuilder()
            .subject(email.getSubject())
            .html(htmlBody);
        final String textBody = email.getTextBody();
        if (textBody != null) {
            message.text(textBody);
        }
        addSender(message, email);
        addRecipient(message, email);
        addExtraHeaders(message, email);
        for (final InlineAttachment attachment : email.getInlineAttachments()) {
            message.inline(attachment.getContent(), attachment.getMimeType(), attachment.getName(),
                Set.of(new Header("Content-Disposition", "inline; filename=\"" + attachment.getName() + "\"")));
        }
        handOver(mailService, message);
    }

    /**
     * Hands a message to the mail service without waiting for it to be sent. A failed send is logged and recorded
     * when it happens.
     *
     * @param mailService the service that sends it
     * @param message the message to send
     * @throws MessagingException if the message cannot be assembled
     */
    private static void handOver(final MailService mailService, final MessageBuilder message)
        throws MessagingException
    {
        mailService.sendMessage(message.build()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                // This line adds no recipient address, since an address names a person
                LOGGER.error("An email could not be sent: {}", failure.getMessage(), failure);
                ErrorLogger.logError(failure, ErrorContext.of(EmailUtils.class, "sendEmail"));
            }
        });
    }

    /**
     * Sets the {@code From} and {@code Reply-To} headers. An address with no display name is set alone.
     *
     * @param message the message being built
     * @param email the email being sent
     * @throws MessagingException if the sender or reply-to address does not parse
     */
    private static void addSender(final MessageBuilder message, final Email email) throws MessagingException
    {
        final String senderName = email.getSenderName();
        if (senderName == null) {
            message.from(email.getSenderAddress());
        } else {
            message.from(email.getSenderAddress(), senderName);
        }
        final String replyToName = email.getReplyToName();
        if (replyToName == null) {
            message.replyTo(email.getReplyToAddress());
        } else {
            message.replyTo(email.getReplyToAddress(), replyToName);
        }
    }

    /**
     * Sets the {@code To} header. An address with no display name is set alone.
     *
     * @param message the message being built
     * @param email the email being sent
     * @throws MessagingException if the recipient address does not parse
     */
    private static void addRecipient(final MessageBuilder message, final Email email) throws MessagingException
    {
        final String recipientName = email.getRecipientName();
        if (recipientName == null) {
            message.to(email.getRecipientAddress());
        } else {
            message.to(email.getRecipientAddress(), recipientName);
        }
    }

    private static void addExtraHeaders(final MessageBuilder message, final Email email)
    {
        email.getExtraHeaders().forEach(message::header);
    }
}
