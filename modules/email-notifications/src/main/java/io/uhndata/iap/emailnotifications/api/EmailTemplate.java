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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sender, subject and body of an email the platform sends. A template stored in the repository lets a deployment
 * reword an email without touching code. {@link #getEmailBuilder(Map) Filling in its variables} and setting a
 * recipient turns it into an {@link Email}.
 *
 * <p>
 * The subject and both bodies are <a href="https://velocity.apache.org/engine/devel/user-guide.html">Apache
 * Velocity</a> templates. Their variables are the template's own extra properties, and the caller's values replace
 * those of the same name.
 * </p>
 *
 * <p>
 * <strong>The HTML body escapes its values</strong>, and the subject and the plain text body do not. See
 * {@link EmailUtils#render} and {@link EmailUtils#renderHtml}.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class EmailTemplate
{
    /** The node type of a stored email template. */
    public static final String TEMPLATE_TYPE = "mail:Template";

    /**
     * The folder holding the headers and footers shared by every template. Its {@code commonAttachments} child holds
     * the shared attachments.
     */
    public static final String COMMON_TEMPLATES_PATH = "/libs/iap/mailTemplates/";

    /** The name of the property holding the sender address. */
    public static final String SENDER_ADDRESS_PROPERTY = "senderAddress";

    /** The name of the property holding the sender display name. */
    public static final String SENDER_NAME_PROPERTY = "senderName";

    /** The name of the property holding the reply-to address. */
    public static final String REPLY_TO_ADDRESS_PROPERTY = "replyToAddress";

    /** The name of the property holding the reply-to display name. */
    public static final String REPLY_TO_NAME_PROPERTY = "replyToName";

    /** The name of the property holding the subject line. */
    public static final String SUBJECT_PROPERTY = "subject";

    /** The name of the child file holding the HTML body template. */
    public static final String HTML_TEMPLATE_NODE = "bodyTemplate.html";

    /** The name of the child file holding the plain text body template. */
    public static final String TEXT_TEMPLATE_NODE = "bodyTemplate.txt";

    /** The name of the child file holding the HTML header prepended to the body. */
    public static final String HTML_BODY_HEADER = "bodyTemplate.header.html";

    /** The name of the child file holding the HTML footer appended to the body. */
    public static final String HTML_BODY_FOOTER = "bodyTemplate.footer.html";

    /** The name of the child file holding the plain text header prepended to the body. */
    public static final String TEXT_BODY_HEADER = "bodyTemplate.header.txt";

    /** The name of the child file holding the plain text footer appended to the body. */
    public static final String TEXT_BODY_FOOTER = "bodyTemplate.footer.txt";

    /**
     * The prefix of a property that includes one of the shared attachments, such as
     * {@code includeAttachment_logo.png}. Its value is ignored.
     */
    public static final String INCLUDE_ATTACHMENT_PREFIX = "includeAttachment_";

    private String senderAddress;

    private String senderName;

    private String replyToAddress;

    private String replyToName;

    private String subject;

    private String htmlTemplate;

    private String textTemplate;

    private final Map<String, String> properties = new HashMap<>();

    private final List<InlineAttachment> inlineAttachments = new LinkedList<>();

    /** Only built through a {@link #builder() builder}, or copied into an {@link Email}. */
    protected EmailTemplate()
    {
        // Filled in by the builder
    }

    /**
     * Copies another template, for an {@link Email} made from it.
     *
     * @param other the template to copy
     */
    protected EmailTemplate(@NotNull final EmailTemplate other)
    {
        this.senderAddress = other.senderAddress;
        this.senderName = other.senderName;
        this.replyToAddress = other.replyToAddress;
        this.replyToName = other.replyToName;
        this.subject = other.subject;
        this.htmlTemplate = other.htmlTemplate;
        this.textTemplate = other.textTemplate;
        this.inlineAttachments.addAll(other.inlineAttachments);
        this.properties.putAll(other.properties);
    }

    /**
     * The address the email is sent from, which goes in the {@code From} header with {@link #getSenderName()}.
     *
     * @return an email address, such as {@code noreply@example.org}
     */
    @NotNull
    public String getSenderAddress()
    {
        return this.senderAddress;
    }

    /**
     * The name shown as the sender, which goes in the {@code From} header with {@link #getSenderAddress()}.
     *
     * @return a display name, or {@code null} if the template sets none
     */
    @Nullable
    public String getSenderName()
    {
        return this.senderName;
    }

    /**
     * The address replies go to.
     *
     * @return an email address, the sender address if the template sets none
     */
    @NotNull
    public String getReplyToAddress()
    {
        return StringUtils.defaultIfBlank(this.replyToAddress, getSenderAddress());
    }

    /**
     * The name shown with the address replies go to.
     *
     * @return a display name, the sender name if the template sets none, or {@code null} if it sets neither
     */
    @Nullable
    public String getReplyToName()
    {
        return StringUtils.defaultIfBlank(this.replyToName, getSenderName());
    }

    /**
     * The subject line, which is itself a template.
     *
     * @return the subject as written in the template, before its variables are filled in
     */
    @NotNull
    public String getSubject()
    {
        return this.subject;
    }

    /**
     * The variables the template sets for its subject and bodies. A value passed to {@link #getEmailBuilder(Map)}
     * replaces the one of the same name.
     *
     * @return a copy of the map from variable name to value, empty if the template sets none
     */
    @NotNull
    public Map<String, String> getExtraProperties()
    {
        return new HashMap<>(this.properties);
    }

    /**
     * The files sent with the email and shown inside it.
     *
     * @return a copy of the attachments, empty if there are none
     */
    @NotNull
    public List<InlineAttachment> getInlineAttachments()
    {
        return new ArrayList<>(this.inlineAttachments);
    }

    /**
     * The template of the HTML body. A template read from the repository has its header and footer included.
     *
     * @return the Velocity template text, or {@code null} if the template has no HTML body
     */
    @Nullable
    public String getHtmlTemplate()
    {
        return this.htmlTemplate;
    }

    /**
     * The template of the plain text body. A template read from the repository has its header and footer included.
     *
     * @return the Velocity template text, or {@code null} if the template has no plain text body
     */
    @Nullable
    public String getTextTemplate()
    {
        return this.textTemplate;
    }

    /**
     * Starts an email from this template, with the subject and both bodies filled in. The email still needs a
     * {@link Email.Builder#withRecipient recipient} before it can be built.
     *
     * @param variables the values the subject and bodies may use, by variable name; each replaces the template's own
     *            property of the same name. A value can be any object, such as a {@code Resource} or a {@code List}.
     *            {@code null} to use only the template's own properties
     * @return an email builder with the subject and bodies set
     * @throws EmailTemplateException if the subject or either body does not parse, or uses a variable that neither the
     *             template nor the caller supplied
     */
    @NotNull
    public Email.Builder getEmailBuilder(@Nullable final Map<String, ?> variables)
    {
        final Map<String, Object> values = new HashMap<>(getExtraProperties());
        if (variables != null) {
            values.putAll(variables);
        }
        final Email.Builder builder = new Email.Builder(this);
        builder.withSubject(EmailUtils.render(this.subject, values));
        // Only the HTML body escapes its values, since it is the only part a mail client reads as markup
        return builder.withBody(EmailUtils.renderHtml(this.htmlTemplate, values),
            EmailUtils.render(this.textTemplate, values));
    }

    /**
     * Starts an email from this template with no body, and the subject as written in the template. Nothing is
     * filled in.
     *
     * @return an email builder with no body set
     */
    @NotNull
    public Email.Builder getEmailBuilder()
    {
        return new Email.Builder(this);
    }

    /**
     * Starts building a template in code.
     *
     * @return an empty template builder
     */
    @NotNull
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Starts building a template from one stored in the repository. The builder holds everything the stored template
     * has, and may add properties or attachments before it {@link Builder#build() builds}.
     *
     * <p>
     * The bodies are wrapped in the shared headers and footers under {@value #COMMON_TEMPLATES_PATH}, unless the
     * template has its own. A header and footer alone do not make a body.
     * </p>
     *
     * @param template a JCR node of type {@value #TEMPLATE_TYPE}
     * @param resolver reads the shared attachments and the media type of every attachment
     * @return a template builder holding the stored template
     * @throws RepositoryException if reading the repository fails
     * @throws IOException if reading a body, a header, a footer or an attachment fails
     */
    @NotNull
    public static Builder builder(@NotNull final Node template, @NotNull final ResourceResolver resolver)
        throws RepositoryException, IOException
    {
        return new Builder(template, resolver);
    }

    /**
     * Builds an {@link EmailTemplate}. Obtained from {@link EmailTemplate#builder()} or
     * {@link EmailTemplate#builder(Node, ResourceResolver)}.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public static final class Builder
    {
        private final EmailTemplate instance;

        private Builder()
        {
            this.instance = new EmailTemplate();
        }

        private Builder(final Node template, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            this();
            readProperties(template);
            readCommonAttachments(resolver);
            readBodiesAndAttachments(template, resolver);
        }

        /**
         * Sets the template of the HTML body.
         *
         * @param body Velocity template text, or {@code null} for an email with no HTML body
         * @return this builder
         */
        @NotNull
        public Builder withHtmlTemplate(@Nullable final String body)
        {
            this.instance.htmlTemplate = body;
            return this;
        }

        /**
         * Sets the template of the plain text body.
         *
         * @param body Velocity template text, or {@code null} for an email with no plain text body
         * @return this builder
         */
        @NotNull
        public Builder withTextTemplate(@Nullable final String body)
        {
            this.instance.textTemplate = body;
            return this;
        }

        /**
         * Sets the sender address. A template cannot be built without one.
         *
         * @param address an email address, such as {@code noreply@example.org}
         * @return this builder
         */
        @NotNull
        public Builder withSenderAddress(@NotNull final String address)
        {
            this.instance.senderAddress = address;
            return this;
        }

        /**
         * Sets the name shown as the sender.
         *
         * @param name a display name, or {@code null} to send from the address alone
         * @return this builder
         */
        @NotNull
        public Builder withSenderName(@Nullable final String name)
        {
            this.instance.senderName = name;
            return this;
        }

        /**
         * Sets the address replies go to.
         *
         * @param address an email address, or {@code null} or blank for replies to go to the sender address
         * @return this builder
         */
        @NotNull
        public Builder withReplyToAddress(@Nullable final String address)
        {
            this.instance.replyToAddress = address;
            return this;
        }

        /**
         * Sets the name shown with the address replies go to.
         *
         * @param name a display name, or {@code null} or blank to use the sender name
         * @return this builder
         */
        @NotNull
        public Builder withReplyToName(@Nullable final String name)
        {
            this.instance.replyToName = name;
            return this;
        }

        /**
         * Sets the subject line. A template cannot be built without one.
         *
         * @param subject the subject as Velocity template text, such as {@code Your request ${subjectTitle}}
         * @return this builder
         */
        @NotNull
        public Builder withSubject(@NotNull final String subject)
        {
            this.instance.subject = subject;
            return this;
        }

        /**
         * Adds a variable for the subject and the bodies.
         *
         * @param name the variable name, such as {@code role} for {@code ${role}}
         * @param value the value to put in its place
         * @return this builder
         */
        @NotNull
        public Builder withProperty(@NotNull final String name, @NotNull final String value)
        {
            this.instance.properties.put(name, value);
            return this;
        }

        /**
         * Adds a file to send with the email and show inside it. It replaces an attachment of the same name, which is
         * how a stored template overrides a shared attachment.
         *
         * @param name the file name, which is also the content ID the HTML body uses, as in {@code cid:logo.png}
         * @param mimeType the media type of the content, such as {@code image/png}
         * @param content the bytes of the file, or {@code null} for an empty file
         * @return this builder
         */
        @NotNull
        public Builder withInlineAttachment(@NotNull final String name, @NotNull final String mimeType,
            @Nullable final byte[] content)
        {
            this.instance.inlineAttachments.removeIf(attachment -> attachment.getName().equals(name));
            this.instance.inlineAttachments.add(new InlineAttachment(name, mimeType, content));
            return this;
        }

        /**
         * Returns the built template. The builder must not be used afterwards, since it changes the same template.
         *
         * @return the template
         * @throws IllegalStateException if the sender address or the subject is missing
         */
        @NotNull
        public EmailTemplate build()
        {
            if (this.instance.senderAddress == null) {
                throw new IllegalStateException("The email template has no sender address");
            }
            if (this.instance.subject == null) {
                throw new IllegalStateException("The email template has no subject");
            }
            return this.instance;
        }

        private void readProperties(final Node template) throws RepositoryException
        {
            final PropertyIterator properties = template.getProperties();
            while (properties.hasNext()) {
                final Property property = properties.nextProperty();
                final String name = property.getName();
                if (name.startsWith("jcr:") || name.startsWith("sling:") || property.isMultiple()) {
                    continue;
                }
                final String value = property.getString();
                switch (name) {
                    case SENDER_ADDRESS_PROPERTY -> withSenderAddress(value);
                    case SENDER_NAME_PROPERTY -> withSenderName(value);
                    case REPLY_TO_ADDRESS_PROPERTY -> withReplyToAddress(value);
                    case REPLY_TO_NAME_PROPERTY -> withReplyToName(value);
                    case SUBJECT_PROPERTY -> withSubject(value);
                    default -> withProperty(name, value);
                }
            }
        }

        /**
         * Adds each shared attachment that the template names with an
         * {@value EmailTemplate#INCLUDE_ATTACHMENT_PREFIX} property.
         *
         * @param resolver reads the {@code commonAttachments} folder
         * @throws RepositoryException if reading the repository fails
         * @throws IOException if reading an attachment fails
         */
        private void readCommonAttachments(final ResourceResolver resolver) throws RepositoryException, IOException
        {
            final Resource common = resolver.getResource(COMMON_TEMPLATES_PATH + "commonAttachments");
            final Node commonNode = common == null ? null : common.adaptTo(Node.class);
            if (commonNode == null) {
                return;
            }
            final NodeIterator children = commonNode.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                if (child.isNodeType("nt:file")
                    && this.instance.properties.containsKey(INCLUDE_ATTACHMENT_PREFIX + child.getName())) {
                    withInlineAttachment(child.getName(), getMimeType(child, resolver), readBytes(child));
                }
            }
        }

        private void readBodiesAndAttachments(final Node template, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            final Session session = template.getSession();
            final String[] html = { readCommon(session, HTML_BODY_HEADER), null,
                readCommon(session, HTML_BODY_FOOTER) };
            final String[] text = { readCommon(session, TEXT_BODY_HEADER), null,
                readCommon(session, TEXT_BODY_FOOTER) };
            final NodeIterator children = template.getNodes();
            while (children.hasNext()) {
                final Node child = children.nextNode();
                switch (child.getName()) {
                    case HTML_BODY_HEADER -> html[0] = readString(child);
                    case HTML_TEMPLATE_NODE -> html[1] = readString(child);
                    case HTML_BODY_FOOTER -> html[2] = readString(child);
                    case TEXT_BODY_HEADER -> text[0] = readString(child);
                    case TEXT_TEMPLATE_NODE -> text[1] = readString(child);
                    case TEXT_BODY_FOOTER -> text[2] = readString(child);
                    default -> addIfFile(child, resolver);
                }
            }
            withHtmlTemplate(wrapBody(html));
            withTextTemplate(wrapBody(text));
        }

        /**
         * Wraps a body in its header and footer.
         *
         * @param parts the header, the body and the footer, in that order; the body is {@code null} if the template
         *            has none of this kind
         * @return the three joined, or {@code null} if there is no body
         */
        private static String wrapBody(final String[] parts)
        {
            return parts[1] == null ? null : String.join("", parts);
        }

        private void addIfFile(final Node child, final ResourceResolver resolver)
            throws RepositoryException, IOException
        {
            if (child.isNodeType("nt:file")) {
                withInlineAttachment(child.getName(), getMimeType(child, resolver), readBytes(child));
            }
        }

        private String readCommon(final Session session, final String name) throws RepositoryException, IOException
        {
            final String path = COMMON_TEMPLATES_PATH + name;
            return session.nodeExists(path) ? readString(session.getNode(path)) : "";
        }

        private String readString(final Node file) throws RepositoryException, IOException
        {
            final Binary content = getContent(file);
            try (InputStream stream = content.getStream()) {
                return IOUtils.toString(stream, StandardCharsets.UTF_8);
            } finally {
                content.dispose();
            }
        }

        private byte[] readBytes(final Node file) throws RepositoryException, IOException
        {
            final Binary content = getContent(file);
            try (InputStream stream = content.getStream()) {
                return stream.readAllBytes();
            } finally {
                content.dispose();
            }
        }

        /**
         * The content of a file node. The caller must close the stream it takes from it, and then dispose of it.
         *
         * @param file an {@code nt:file} node
         * @return the {@code jcr:data} of its {@code jcr:content}
         * @throws RepositoryException if the node is not a readable file
         */
        private Binary getContent(final Node file) throws RepositoryException
        {
            return file.getNode("jcr:content").getProperty("jcr:data").getBinary();
        }

        private String getMimeType(final Node file, final ResourceResolver resolver) throws RepositoryException
        {
            return Optional.ofNullable(resolver.getResource(file.getPath()))
                .map(resource -> resource.getResourceMetadata().getContentType())
                .filter(StringUtils::isNotBlank)
                .orElse("application/octet-stream");
        }
    }
}
