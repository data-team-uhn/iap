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

import org.apache.commons.text.StringSubstitutor;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Filling in email templates, and handing the result to the mail service.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class EmailUtils
{
    /** Only static methods, no instances. */
    private EmailUtils()
    {
        // Utility class
    }

    /**
     * Substitutes the <code>${variable}</code> placeholders of a template.
     *
     * @param template the text to fill in, may be {@code null}
     * @param values the values to substitute, by variable name
     * @return the filled in text, {@code null} if there was no template to fill in
     */
    @Nullable
    public static String render(@Nullable final String template, @NotNull final Map<String, String> values)
    {
        return template == null ? null : new StringSubstitutor(values).replace(template);
    }

    /**
     * Sends a plain text email.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if sending the email fails
     * @throws IllegalArgumentException if the email has no plain text body, since an email built from a template with
     *             only an HTML part cannot be sent as plain text
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
        mailService.sendMessage(message.build());
    }

    /**
     * Sends an email with an HTML body, and a plain text one for the mail clients that cannot show it.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if sending the email fails
     * @throws IllegalArgumentException if the email has no HTML body, since an email built from a template with only
     *             a plain text part cannot be sent as HTML
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
            // Only a fallback for the clients that cannot show the HTML part, so an email may well have none
            message.text(textBody);
        }
        addSender(message, email);
        addRecipient(message, email);
        addExtraHeaders(message, email);
        for (final InlineAttachment attachment : email.getInlineAttachments()) {
            message.inline(attachment.getContent(), attachment.getMimeType(), attachment.getName(),
                Set.of(new Header("Content-Disposition", "inline; filename=\"" + attachment.getName() + "\"")));
        }
        mailService.sendMessage(message.build());
    }

    /**
     * Sets the sender and the address replies go to. As with the recipient, an address with no display name is set on
     * its own rather than paired with a missing name.
     *
     * @param message the message being built
     * @param email the email being sent
     * @throws MessagingException if the sender or reply-to address is not a valid one
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
     * Addresses the message. A recipient with no display name is addressed by its address alone, rather than with an
     * empty name.
     *
     * @param message the message being built
     * @param email the email being sent
     * @throws MessagingException if the recipient address is not a valid one
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
