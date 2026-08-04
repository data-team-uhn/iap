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
    public static String render(final String template, final Map<String, String> values)
    {
        return template == null ? null : new StringSubstitutor(values).replace(template);
    }

    /**
     * Sends a plain text email.
     *
     * @param email the email to send
     * @param mailService the service that sends it
     * @throws MessagingException if sending the email fails
     */
    public static void sendTextEmail(final Email email, final MailService mailService) throws MessagingException
    {
        final MessageBuilder message = mailService.getMessageBuilder()
            .from(email.getSenderAddress(), email.getSenderName())
            .replyTo(email.getReplyToAddress(), email.getReplyToName())
            .subject(email.getSubject())
            .text(email.getTextBody());
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
     */
    public static void sendHtmlEmail(final Email email, final MailService mailService) throws MessagingException
    {
        final MessageBuilder message = mailService.getMessageBuilder()
            .from(email.getSenderAddress(), email.getSenderName())
            .replyTo(email.getReplyToAddress(), email.getReplyToName())
            .subject(email.getSubject())
            .text(email.getTextBody())
            .html(email.getHtmlBody());
        addRecipient(message, email);
        addExtraHeaders(message, email);
        for (final InlineAttachment attachment : email.getInlineAttachments()) {
            message.inline(attachment.getContent(), attachment.getMimeType(), attachment.getName(),
                Set.of(new Header("Content-Disposition", "inline; filename=\"" + attachment.getName() + "\"")));
        }
        mailService.sendMessage(message.build());
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
        if (email.getRecipientName() == null) {
            message.to(email.getRecipientAddress());
        } else {
            message.to(email.getRecipientAddress(), email.getRecipientName());
        }
    }

    private static void addExtraHeaders(final MessageBuilder message, final Email email)
    {
        email.getExtraHeaders().forEach(message::header);
    }
}
