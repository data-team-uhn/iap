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

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only email, ready to be sent. Emails are built from {@link EmailTemplate templates}: read a template, fill in
 * its variables with {@link EmailTemplate#getEmailBuilder(Map)}, set a recipient, and send it with
 * {@link EmailUtils#sendHtmlEmail} or {@link EmailUtils#sendTextEmail}.
 *
 * @see EmailTemplate
 * @version $Id$
 * @since 0.1.0
 */
public class Email extends EmailTemplate
{
    private String toAddress;

    private String toName;

    private String htmlBody;

    private String textBody;

    private String renderedSubject;

    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    /**
     * Instantiated from a template by a {@link Builder}.
     *
     * @param template the template this email is based on
     */
    protected Email(@NotNull final EmailTemplate template)
    {
        super(template);
    }

    /**
     * The recipient email address, part of the {@code To} header together with {@link #getRecipientName()}.
     *
     * @return a valid email address
     */
    @NotNull
    public String getRecipientAddress()
    {
        return this.toAddress;
    }

    /**
     * The recipient display name, part of the {@code To} header together with {@link #getRecipientAddress()}.
     *
     * @return a display name, {@code null} when the address stands on its own
     */
    @Nullable
    public String getRecipientName()
    {
        return this.toName;
    }

    /**
     * The HTML body of the email.
     *
     * @return a large string, {@code null} if the email has no HTML part
     */
    @Nullable
    public String getHtmlBody()
    {
        return this.htmlBody;
    }

    /**
     * The plain text body of the email.
     *
     * @return a large string, {@code null} if the email has no plain text part
     */
    @Nullable
    public String getTextBody()
    {
        return this.textBody;
    }

    /**
     * The subject line, with its variables already substituted.
     *
     * @return a subject
     */
    @Override
    @NotNull
    public String getSubject()
    {
        return this.renderedSubject == null ? super.getSubject() : this.renderedSubject;
    }

    /**
     * Extra headers to set on the message, e.g. {@code Auto-Submitted}.
     *
     * @return a map from header name to value, in the order they were added
     */
    @NotNull
    public Map<String, String> getExtraHeaders()
    {
        return new LinkedHashMap<>(this.extraHeaders);
    }

    /**
     * A builder for {@link Email}, obtained from {@link EmailTemplate#getEmailBuilder(Map)}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final Email instance;

        /**
         * Instantiates a template into an email under construction.
         *
         * @param template the template to base the email on
         */
        protected Builder(@NotNull final EmailTemplate template)
        {
            this.instance = new Email(template);
        }

        /**
         * Set the body parts of this email. Already done when the builder came from
         * {@link EmailTemplate#getEmailBuilder(Map)}. At least one part is required.
         *
         * @param htmlBody the HTML part, {@code null} for none
         * @param textBody the plain text part, {@code null} for none
         * @return this builder
         */
        @NotNull
        public Builder withBody(@Nullable final String htmlBody, @Nullable final String textBody)
        {
            this.instance.htmlBody = htmlBody;
            this.instance.textBody = textBody;
            return this;
        }

        /**
         * Set the subject line of this email, overriding the template's. Already done when the builder came from
         * {@link EmailTemplate#getEmailBuilder(Map)}.
         *
         * @param subject a short string, {@code null} to keep the template's own subject
         * @return this builder
         */
        @NotNull
        public Builder withSubject(@Nullable final String subject)
        {
            this.instance.renderedSubject = subject;
            return this;
        }

        /**
         * Set the recipient of this email. The address is required.
         *
         * @param address a valid email address
         * @param name an optional display name, {@code null} to address the recipient by address alone
         * @return this builder
         */
        @NotNull
        public Builder withRecipient(@NotNull final String address, @Nullable final String name)
        {
            this.instance.toAddress = address;
            this.instance.toName = name;
            return this;
        }

        /**
         * Add an extra header to set on the message.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        @NotNull
        public Builder withExtraHeader(@NotNull final String name, @NotNull final String value)
        {
            this.instance.extraHeaders.put(name, value);
            return this;
        }

        /**
         * Retrieve the built email. The builder should be discarded afterwards.
         *
         * @return an {@link Email}
         * @throws IllegalStateException if the email has no recipient address, or no body at all
         */
        @NotNull
        public Email build()
        {
            if (this.instance.toAddress == null) {
                // The display name may be missing, the address may not
                throw new IllegalStateException("The email has no recipient address");
            }
            if (this.instance.htmlBody == null && this.instance.textBody == null) {
                throw new IllegalStateException("The email has neither an HTML nor a plain text body");
            }
            return this.instance;
        }
    }
}
