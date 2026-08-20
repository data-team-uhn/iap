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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EmailTemplate}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EmailTemplateTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    @Test
    void keepsWhatItWasBuiltWith()
    {
        final EmailTemplate template = EmailTemplate.builder()
            .withSenderAddress("noreply@example.invalid")
            .withSenderName("IAP")
            .withReplyToAddress("replies@example.invalid")
            .withReplyToName("Replies")
            .withSubject("Hello")
            .withHtmlTemplate("<p>html</p>")
            .withTextTemplate("text")
            .withProperty("name", "Alice")
            .withInlineAttachment("logo.png", "image/png", new byte[] { 1 })
            .build();

        assertEquals("noreply@example.invalid", template.getSenderAddress());
        assertEquals("IAP", template.getSenderName());
        assertEquals("replies@example.invalid", template.getReplyToAddress());
        assertEquals("Replies", template.getReplyToName());
        assertEquals("Hello", template.getSubject());
        assertEquals("<p>html</p>", template.getHtmlTemplate());
        assertEquals("text", template.getTextTemplate());
        assertEquals(Map.of("name", "Alice"), template.getExtraProperties());
        assertEquals(1, template.getInlineAttachments().size());
    }

    @Test
    void repliesGoToTheSenderUnlessToldOtherwise()
    {
        final EmailTemplate template = minimal().build();

        assertEquals("noreply@example.invalid", template.getReplyToAddress());
        assertEquals("IAP", template.getReplyToName());
    }

    @Test
    void aTemplateNeedsASenderAndASubject()
    {
        assertEquals("The email template has no sender address",
            assertThrows(IllegalStateException.class,
                () -> EmailTemplate.builder().withSubject("Hello").build()).getMessage());
        assertEquals("The email template has no subject",
            assertThrows(IllegalStateException.class,
                () -> EmailTemplate.builder().withSenderAddress("a@example.invalid").build()).getMessage());
    }

    @Test
    void anAttachmentAddedTwiceIsReplaced()
    {
        // This is how a template overrides one of the shared attachments with its own
        final EmailTemplate template = minimal()
            .withInlineAttachment("logo.png", "image/png", new byte[] { 1 })
            .withInlineAttachment("logo.png", "image/svg+xml", new byte[] { 2, 3 })
            .build();

        assertEquals(1, template.getInlineAttachments().size());
        assertEquals("image/svg+xml", template.getInlineAttachments().get(0).getMimeType());
    }

    @Test
    void fillsInTheSubjectAndBothBodies()
    {
        final Email email = minimal()
            .withSubject("Hello ${name}")
            .withHtmlTemplate("<p>Dear ${name}, you are ${role}</p>")
            .withTextTemplate("Dear ${name}, you are ${role}")
            .withProperty("role", "a reviewer")
            .build()
            .getEmailBuilder(Map.of("name", "Alice"))
            .withRecipient("alice@example.invalid", "Alice")
            .build();

        assertEquals("Hello Alice", email.getSubject());
        assertEquals("<p>Dear Alice, you are a reviewer</p>", email.getHtmlBody());
        assertEquals("Dear Alice, you are a reviewer", email.getTextBody());
    }

    @Test
    void whatTheCallerPassesInWinsOverTheTemplatesOwnProperties()
    {
        final Email email = minimal()
            .withTextTemplate("${role}")
            .withProperty("role", "a reviewer")
            .build()
            .getEmailBuilder(Map.of("role", "an approver"))
            .withRecipient("alice@example.invalid", null)
            .build();

        assertEquals("an approver", email.getTextBody());
    }

    @Test
    void fillingInWithNoVariablesAtAllIsAllowed()
    {
        final Email email = minimal()
            .withTextTemplate("nothing to substitute")
            .build()
            .getEmailBuilder(null)
            .withRecipient("alice@example.invalid", null)
            .build();

        assertEquals("nothing to substitute", email.getTextBody());
    }

    @Test
    void readsAStoredTemplate() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SENDER_NAME_PROPERTY, "IAP");
        template.setProperty(EmailTemplate.REPLY_TO_ADDRESS_PROPERTY, "replies@example.invalid");
        template.setProperty(EmailTemplate.REPLY_TO_NAME_PROPERTY, "Replies");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello ${name}");
        template.setProperty("role", "a reviewer");
        // Multivalued properties are not variables, and neither are the repository's own
        template.setProperty("tags", new String[] { "a", "b" });
        addFile(template, EmailTemplate.HTML_TEMPLATE_NODE, "<p>${role}</p>");
        addFile(template, EmailTemplate.TEXT_TEMPLATE_NODE, "${role}");
        addFile(template, "logo.png", "not really a png");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        assertEquals("noreply@example.invalid", read.getSenderAddress());
        assertEquals("replies@example.invalid", read.getReplyToAddress());
        assertEquals("Hello ${name}", read.getSubject());
        assertEquals(Map.of("role", "a reviewer"), read.getExtraProperties());
        assertEquals("<p>${role}</p>", read.getHtmlTemplate());
        assertEquals("${role}", read.getTextTemplate());
        assertEquals(List.of("logo.png"),
            read.getInlineAttachments().stream().map(InlineAttachment::getName).toList());
    }

    @Test
    void wrapsTheBodyInTheSharedHeaderAndFooter() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node common = createPath(session, EmailTemplate.COMMON_TEMPLATES_PATH);
        addFile(common, EmailTemplate.HTML_BODY_HEADER, "<header/>");
        addFile(common, EmailTemplate.HTML_BODY_FOOTER, "<footer/>");
        addFile(common, EmailTemplate.TEXT_BODY_HEADER, "== ");
        addFile(common, EmailTemplate.TEXT_BODY_FOOTER, " ==");
        final Node attachments = common.addNode("commonAttachments", "nt:unstructured");
        addFile(attachments, "shared.png", "shared");
        addFile(attachments, "unwanted.png", "unwanted");

        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello");
        template.setProperty(EmailTemplate.INCLUDE_ATTACHMENT_PREFIX + "shared.png", "true");
        addFile(template, EmailTemplate.HTML_TEMPLATE_NODE, "<body/>");
        addFile(template, EmailTemplate.TEXT_TEMPLATE_NODE, "body");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        assertEquals("<header/><body/><footer/>", read.getHtmlTemplate());
        assertEquals("== body ==", read.getTextTemplate());
        // Only the shared attachment the template asked for is included
        assertEquals(List.of("shared.png"),
            read.getInlineAttachments().stream().map(InlineAttachment::getName).toList());
    }

    @Test
    void aTemplateMayOverrideTheSharedHeaderAndFooter() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node common = createPath(session, EmailTemplate.COMMON_TEMPLATES_PATH);
        addFile(common, EmailTemplate.HTML_BODY_HEADER, "<shared/>");
        addFile(common, EmailTemplate.TEXT_BODY_HEADER, "shared ");

        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello");
        addFile(template, EmailTemplate.HTML_BODY_HEADER, "<own/>");
        addFile(template, EmailTemplate.HTML_BODY_FOOTER, "<ownFooter/>");
        addFile(template, EmailTemplate.TEXT_BODY_HEADER, "own ");
        addFile(template, EmailTemplate.TEXT_BODY_FOOTER, " ownFooter");
        addFile(template, EmailTemplate.HTML_TEMPLATE_NODE, "<body/>");
        addFile(template, EmailTemplate.TEXT_TEMPLATE_NODE, "body");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        assertEquals("<own/><body/><ownFooter/>", read.getHtmlTemplate());
        assertEquals("own body ownFooter", read.getTextTemplate());
    }

    /**
     * The reason a body part is {@code null} rather than an empty string when the template does not carry one: a
     * header and a footer are decoration around a body, and sending them with nothing between is exactly the "email
     * with no body at all" that {@link EmailUtils#sendTextEmail} promises to refuse.
     */
    @Test
    void aTemplateHasNoPartOfAKindItCarriesNoBodyFor() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node common = createPath(session, EmailTemplate.COMMON_TEMPLATES_PATH);
        addFile(common, EmailTemplate.TEXT_BODY_HEADER, "== ");
        addFile(common, EmailTemplate.TEXT_BODY_FOOTER, " ==");

        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello");
        addFile(template, EmailTemplate.HTML_TEMPLATE_NODE, "<body/>");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        assertEquals("<body/>", read.getHtmlTemplate());
        assertNull(read.getTextTemplate());

        // And it survives into the email, which is what makes EmailUtils' refusal reachable
        final Email email = read.getEmailBuilder(Map.of()).withRecipient("a@example.invalid", null).build();
        assertNull(email.getTextBody());
    }

    @Test
    void aTemplateWithNoSharedContentAtAllStillReads() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello");
        // A child that is not a file and not a body template is simply ignored
        template.addNode("someConfig", "nt:unstructured");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        // No body of either kind, so no part of either kind; the email built from it cannot be sent, and says so
        assertNull(read.getHtmlTemplate());
        assertNull(read.getTextTemplate());
        assertTrue(read.getInlineAttachments().isEmpty());
        assertEquals("The email has neither an HTML nor a plain text body",
            assertThrows(IllegalStateException.class,
                () -> read.getEmailBuilder(Map.of()).withRecipient("a@example.invalid", null).build())
                .getMessage());
    }

    @Test
    void anAttachmentOfUnknownTypeIsStillSent() throws Exception
    {
        final Session session = this.context.resourceResolver().adaptTo(Session.class);
        final Node template = session.getRootNode().addNode("template", "nt:unstructured");
        template.setProperty(EmailTemplate.SENDER_ADDRESS_PROPERTY, "noreply@example.invalid");
        template.setProperty(EmailTemplate.SUBJECT_PROPERTY, "Hello");
        addFile(template, "mystery.unknownextension", "content");
        session.save();

        final EmailTemplate read = EmailTemplate.builder(template, this.context.resourceResolver()).build();

        assertEquals("application/octet-stream", read.getInlineAttachments().get(0).getMimeType());
    }

    @Test
    void anEmailBuilderWithoutVariablesLeavesTheBodyToTheCaller()
    {
        final EmailTemplate template = minimal().withTextTemplate("${role}").build();

        // Nothing is substituted, and the subject is the template's own
        assertNull(template.getEmailBuilder().withBody(null, "set by hand")
            .withRecipient("a@example.invalid", null).build().getHtmlBody());
        assertEquals("Hello", template.getEmailBuilder().withBody(null, "x")
            .withRecipient("a@example.invalid", null).build().getSubject());
    }

    private EmailTemplate.Builder minimal()
    {
        return EmailTemplate.builder()
            .withSenderAddress("noreply@example.invalid")
            .withSenderName("IAP")
            .withSubject("Hello");
    }

    private Node createPath(final Session session, final String path) throws Exception
    {
        Node current = session.getRootNode();
        for (final String name : path.split("/")) {
            if (!name.isEmpty()) {
                current = current.hasNode(name) ? current.getNode(name) : current.addNode(name, "nt:unstructured");
            }
        }
        return current;
    }

    private void addFile(final Node parent, final String name, final String content) throws Exception
    {
        final Node file = parent.addNode(name, "nt:file");
        final Node data = file.addNode("jcr:content", "nt:resource");
        data.setProperty("jcr:data", parent.getSession().getValueFactory().createBinary(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }
}
