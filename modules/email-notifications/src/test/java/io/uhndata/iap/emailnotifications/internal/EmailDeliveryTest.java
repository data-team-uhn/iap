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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.mail.internet.MimeMessage;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.notifications.api.NotificationContext;
import io.uhndata.iap.notifications.api.Recipient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EmailDelivery}.
 *
 * <p>
 * These assert on <strong>what was actually assembled</strong> rather than on the delivery having returned true.
 * That distinction is the whole point: this path has form for reporting success where nothing was built — an
 * endpoint answering 200 for a message that was never assembled — so the subject line, the recipient and the
 * rendered body are captured off the builder and checked, interpolation and all.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EmailDeliveryTest
{
    private static final String TEMPLATE = "/libs/iap/mailTemplates/approved";

    private static final Recipient REQUESTER =
        new Recipient("the-requester", "The Requester", "requester@example.com");

    // JCR-backed: a template is read through the JCR API, from nt:file children
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final EmailDelivery delivery = new EmailDelivery();

    private MailService mailService;

    private MessageBuilder builder;

    private Resource submission;

    @BeforeEach
    void setUp() throws Exception
    {
        this.submission = this.context.create().resource("/Submissions/one", "title", "A long weekend");
        this.mailService = Mockito.mock(MailService.class);
        // RETURNS_SELF so the fluent chain works; what each call was given is what the assertions read back
        this.builder = Mockito.mock(MessageBuilder.class, Mockito.RETURNS_SELF);
        Mockito.when(this.builder.build()).thenReturn(Mockito.mock(MimeMessage.class));
        Mockito.when(this.mailService.getMessageBuilder()).thenReturn(this.builder);
        Mockito.when(this.mailService.sendMessage(Mockito.any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        final Field field = EmailDelivery.class.getDeclaredField("mailService");
        field.setAccessible(true);
        field.set(this.delivery, this.mailService);
    }

    /** Installs a template folder with both bodies. */
    private void template()
    {
        this.context.create().resource(TEMPLATE, Map.of(
            "jcr:primaryType", "sling:Folder",
            "senderAddress", "platform@example.com",
            "senderName", "The Platform",
            "subject", "Your request ${subjectTitle} was ${event}"));
        this.file(TEMPLATE + "/bodyTemplate.txt", "text/plain",
            "Your request ${subjectTitle} was ${event}. It is at ${subjectPath}.");
        this.file(TEMPLATE + "/bodyTemplate.html", "text/html",
            "<p>Your request ${subjectTitle} was ${event}.</p>");
    }

    private void file(final String path, final String mimeType, final String body)
    {
        this.context.create().resource(path, Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(path + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource", "jcr:mimeType", mimeType, "jcr:data", body));
    }

    private NotificationContext notification(final String urgency, final String templatePath)
    {
        return NotificationContext.about(this.submission)
            .becauseOf("approved")
            .urgency(urgency)
            .using(templatePath)
            .build();
    }

    @Test
    void buildsAndSendsTheMessageTheTemplateDescribes() throws Exception
    {
        this.template();

        assertTrue(this.delivery.deliver(
            this.notification(NotificationContext.IMMEDIATE, TEMPLATE), REQUESTER));

        // Interpolated from what the notification carries, not from anything the caller passed
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.builder).subject(subject.capture());
        assertEquals("Your request A long weekend was approved", subject.getValue());

        Mockito.verify(this.builder).to("requester@example.com", "The Requester");
        Mockito.verify(this.builder).from("platform@example.com", "The Platform");
        Mockito.verify(this.mailService).sendMessage(Mockito.any());
    }

    // Every message can say these without the workflow having to pass them
    @Test
    void alwaysOffersTheSubjectsOwnDetailsToTheTemplate() throws Exception
    {
        this.template();

        this.delivery.deliver(this.notification(NotificationContext.IMMEDIATE, TEMPLATE), REQUESTER);

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.builder).text(text.capture());
        assertTrue(text.getValue().contains("/Submissions/one"), text.getValue());
        assertTrue(text.getValue().contains("A long weekend"), text.getValue());
        assertTrue(text.getValue().contains("approved"), text.getValue());
    }

    // Anything the workflow could not read off the subject travels on the notification and reaches the wording
    @Test
    void interpolatesWhateverTheNotificationCarries() throws Exception
    {
        this.context.create().resource(TEMPLATE, Map.of(
            "jcr:primaryType", "sling:Folder",
            "senderAddress", "platform@example.com",
            "subject", "Approved for ${days} days"));
        this.file(TEMPLATE + "/bodyTemplate.txt", "text/plain", "That is ${days} days off.");

        this.delivery.deliver(NotificationContext.about(this.submission)
            .becauseOf("approved")
            .using(TEMPLATE)
            .with("days", 3)
            .build(), REQUESTER);

        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        Mockito.verify(this.builder).subject(subject.capture());
        assertEquals("Approved for 3 days", subject.getValue());
    }

    // What makes urgency mean something today rather than only in principle: a batched notification is
    // delivered by nothing yet, visibly, instead of being quietly emailed anyway
    @Test
    void declinesAnythingThatIsNotImmediate() throws Exception
    {
        this.template();

        assertFalse(this.delivery.deliver(
            this.notification(NotificationContext.BATCHED, TEMPLATE), REQUESTER));

        Mockito.verify(this.mailService, Mockito.never()).sendMessage(Mockito.any());
    }

    // A fact about that person's account, not an error
    @Test
    void declinesSomebodyWithNoAddress()
    {
        this.template();

        assertFalse(this.delivery.deliver(this.notification(NotificationContext.IMMEDIATE, TEMPLATE),
            new Recipient("nobody", "Nobody", null)));
        assertFalse(this.delivery.deliver(this.notification(NotificationContext.IMMEDIATE, TEMPLATE),
            new Recipient("nobody", "Nobody", "  ")));
    }

    // This delivery has no wording of its own to fall back on
    @Test
    void declinesANotificationWithNoTemplate()
    {
        assertFalse(this.delivery.deliver(this.notification(NotificationContext.IMMEDIATE, null), REQUESTER));
    }

    @Test
    void declinesATemplateThatIsNotThere()
    {
        assertFalse(this.delivery.deliver(
            this.notification(NotificationContext.IMMEDIATE, "/libs/iap/mailTemplates/gone"), REQUESTER));
    }

    // A template missing what it needs is the schema's problem, and saying so beats sending a broken message
    @Test
    void reportsATemplateItCannotRead()
    {
        this.context.create().resource(TEMPLATE, Map.of("jcr:primaryType", "sling:Folder"));

        assertFalse(this.delivery.deliver(
            this.notification(NotificationContext.IMMEDIATE, TEMPLATE), REQUESTER));
    }
}
