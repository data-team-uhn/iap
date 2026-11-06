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
package io.uhndata.iap.emailcatcher.internal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsException;
import io.uhndata.iap.metrics.api.MetricsManager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CaughtMailService}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CaughtMailServiceTest
{
    private static final String FROM = "sender@example.com";

    private static final String TO = "recipient@example.com";

    private final SlingContext context = new SlingContext();

    private final CaughtMailService service = new CaughtMailService();

    private final MetricsManager metrics = Mockito.mock(MetricsManager.class);

    /** RETURNS_SELF, so that the builder's calls chain. */
    private final MetricsManager.MetricBuilder builder =
        Mockito.mock(MetricsManager.MetricBuilder.class, Answers.RETURNS_SELF);

    private final Metric metric = Mockito.mock(Metric.class);

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(CaughtMailService.CAUGHT_MAIL_PATH,
            "sling:resourceType", "mail/CaughtMailHomepage");
        Mockito.when(this.metrics.createMetric(Mockito.anyString())).thenReturn(this.builder);
        Mockito.when(this.builder.create()).thenReturn(this.metric);
        inject(this.service, factory(this.context.resourceResolver()));
    }

    /** A builder with a sender and a recipient already set. */
    private CaughtMessageBuilder message() throws Exception
    {
        return (CaughtMessageBuilder) this.service.getMessageBuilder().from(FROM, "The Sender").to(TO);
    }

    /** The single message that was caught. */
    private ValueMap caught()
    {
        final Resource home =
            this.context.resourceResolver().getResource(CaughtMailService.CAUGHT_MAIL_PATH);
        final Resource message = home.getChildren().iterator().next();
        return message.getValueMap();
    }

    /** The catcher's setting, switched on or off. */
    private static CaughtMailService.Config switchedTo(final boolean on)
    {
        return new CaughtMailService.Config()
        {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType()
            {
                return CaughtMailService.Config.class;
            }

            @Override
            public boolean enabled()
            {
                return on;
            }
        };
    }

    @Test
    void publishesNothingUntilItIsSwitchedOn()
    {
        final BundleContext bundleContext = Mockito.mock(BundleContext.class);

        this.service.activate(bundleContext, switchedTo(false));

        Mockito.verify(bundleContext, Mockito.never())
            .registerService(Mockito.eq(MailService.class), Mockito.eq(this.service), Mockito.any());
    }

    @Test
    void registersAboveTheRealMailServiceOnceSwitchedOn()
    {
        final BundleContext bundleContext = Mockito.mock(BundleContext.class);

        this.service.activate(bundleContext, switchedTo(true));

        final Dictionary<String, Object> expected = new Hashtable<>();
        expected.put(Constants.SERVICE_RANKING, 1000);
        expected.put(CaughtMailService.CATCHER_PROPERTY, Boolean.TRUE);
        Mockito.verify(bundleContext).registerService(MailService.class, this.service, expected);
    }

    @Test
    void withdrawsTheRegistrationWhenSwitchedOff()
    {
        final BundleContext bundleContext = Mockito.mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        final ServiceRegistration<MailService> registration = Mockito.mock(ServiceRegistration.class);
        Mockito.when(bundleContext.registerService(Mockito.eq(MailService.class), Mockito.eq(this.service),
            Mockito.any())).thenReturn(registration);
        this.service.activate(bundleContext, switchedTo(true));

        this.service.deactivate();

        Mockito.verify(registration).unregister();
    }

    @Test
    void deactivatingWhileSwitchedOffWithdrawsNothing()
    {
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(false));

        this.service.deactivate();
    }

    @Test
    void countsEveryMessageItCatches() throws Exception
    {
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(true));

        this.service.sendMessage(this.message().subject("One").text("A body").build());
        this.service.sendMessage(this.message().subject("Two").text("A body").build());

        Mockito.verify(this.metric, Mockito.times(2)).increment();
    }

    @Test
    void definesTheCounterToRollOverNightly()
    {
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(true));

        Mockito.verify(this.metrics).createMetric(CaughtMailCounter.METRIC_NAME);
        Mockito.verify(this.builder).withRolloverSchedule(CaughtMailCounter.NIGHTLY_SCHEDULE);
        Mockito.verify(this.builder).withAccessLevel(Metric.AccessLevel.ADMIN);
        Mockito.verify(this.builder).create();
    }

    @Test
    void definesNoCounterWhileSwitchedOff()
    {
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(false));

        Mockito.verifyNoInteractions(this.metrics);
    }

    @Test
    void catchesMailEvenWhenTheCounterCannotBeDefined() throws Exception
    {
        Mockito.when(this.builder.create()).thenThrow(new MetricsException("no metrics today"));
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(true));

        assertFalse(this.service.sendMessage(this.message().subject("A subject").text("A body").build())
            .isCompletedExceptionally());

        assertEquals("A subject", this.caught().get("subject", String.class));
        Mockito.verifyNoInteractions(this.metric);
    }

    @Test
    void countsNothingWhenTheMessageCannotBeFiled() throws Exception
    {
        this.service.activate(Mockito.mock(BundleContext.class), switchedTo(true));
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(CaughtMailService.CAUGHT_MAIL_PATH));
        this.context.resourceResolver().commit();

        assertTrue(this.service.sendMessage(this.message().subject("A subject").text("A body").build())
            .isCompletedExceptionally());

        Mockito.verifyNoInteractions(this.metric);
    }

    @Test
    void offersItsOwnBuilder()
    {
        assertInstanceOf(CaughtMessageBuilder.class, this.service.getMessageBuilder());
    }

    @Test
    void filesAMessageInsteadOfSendingIt() throws Exception
    {
        final MimeMessage sent = this.message().subject("A subject").text("A body").build();

        assertFalse(this.service.sendMessage(sent).isCompletedExceptionally());

        final ValueMap message = this.caught();
        assertEquals("A subject", message.get("subject", String.class));
        assertEquals("A body", message.get("textBody", String.class));
        assertEquals(CaughtMailService.MESSAGE_TYPE, message.get("jcr:primaryType", String.class));
        assertNotNull(message.get("caughtAt", Calendar.class));
    }

    @Test
    void keepsTheAddressesAsTheyWereWritten() throws Exception
    {
        this.service.sendMessage(this.message()
            .cc("copied@example.com")
            .bcc("hidden@example.com")
            .replyTo("replies@example.com")
            .text("x")
            .build());

        final ValueMap message = this.caught();
        assertArrayEquals(new String[] { "The Sender <" + FROM + ">" }, message.get("from", String[].class));
        assertArrayEquals(new String[] { TO }, message.get("to", String[].class));
        assertArrayEquals(new String[] { "copied@example.com" }, message.get("cc", String[].class));
        assertArrayEquals(new String[] { "hidden@example.com" }, message.get("bcc", String[].class));
        assertArrayEquals(new String[] { "replies@example.com" }, message.get("replyTo", String[].class));
    }

    @Test
    void doesNotMergeBlindCopiesIntoTheVisibleRecipients() throws Exception
    {
        this.service.sendMessage(this.message().bcc("hidden@example.com").text("x").build());

        final ValueMap message = this.caught();
        assertArrayEquals(new String[] { TO }, message.get("to", String[].class));
        assertArrayEquals(new String[] { "hidden@example.com" }, message.get("bcc", String[].class));
    }

    @Test
    void readsAnHtmlOnlyBody() throws Exception
    {
        this.service.sendMessage(this.message().html("<p>Hello</p>").build());

        final ValueMap message = this.caught();
        assertEquals("<p>Hello</p>", message.get("htmlBody", String.class));
        assertFalse(message.containsKey("textBody"));
    }

    @Test
    void readsBothBodiesOutOfAMultipart() throws Exception
    {
        this.service.sendMessage(this.message().text("Plain").html("<p>Rich</p>").build());

        final ValueMap message = this.caught();
        assertEquals("Plain", message.get("textBody", String.class));
        assertEquals("<p>Rich</p>", message.get("htmlBody", String.class));
    }

    @Test
    void findsTheBodyInsideAMessageThatCarriesAttachments() throws Exception
    {
        this.service.sendMessage(this.message()
            .text("A body")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build());

        assertEquals("A body", this.caught().get("textBody", String.class));
    }

    @Test
    void doesNotReadAnAttachmentAsTheBody() throws Exception
    {
        this.service.sendMessage(this.message()
            .html("<p>Rich</p>")
            .attachment("attached text".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build());

        final ValueMap message = this.caught();
        assertEquals("<p>Rich</p>", message.get("htmlBody", String.class));
        assertFalse(message.containsKey("textBody"));
    }

    @Test
    void keepsTheHeadersThatHaveNoPropertyOfTheirOwn() throws Exception
    {
        this.service.sendMessage(this.message().header("X-Reason", "a reminder").text("x").build());

        final List<String> headers = List.of(this.caught().get("headers", new String[0]));
        assertTrue(headers.contains("X-Reason: a reminder"));
        assertTrue(headers.stream().noneMatch(header -> header.startsWith("Subject:")));
        assertTrue(headers.stream().noneMatch(header -> header.startsWith("To:")));
    }

    @Test
    void catchesAMessageWithAlmostNothingOnIt() throws Exception
    {
        final MimeMessage bare = new CaughtMessageBuilder().text("x").build();

        assertFalse(this.service.sendMessage(bare).isCompletedExceptionally());

        final ValueMap message = this.caught();
        assertFalse(message.containsKey("from"));
        assertFalse(message.containsKey("to"));
        assertFalse(message.containsKey("subject"));
    }

    @Test
    void stampsWhenItWasCaughtRatherThanWhenItWasSent() throws Exception
    {
        final Calendar longAgo = Calendar.getInstance();
        longAgo.add(Calendar.YEAR, -1);
        final MimeMessage old = this.message().text("x").build();
        old.setSentDate(longAgo.getTime());
        old.saveChanges();

        this.service.sendMessage(old);

        final Calendar caughtAt = this.caught().get("caughtAt", Calendar.class);
        assertNotNull(caughtAt);
        assertTrue(caughtAt.after(longAgo));
        assertTrue(List.of(this.caught().get("headers", new String[0])).stream()
            .anyMatch(header -> header.startsWith("Date:")));
    }

    @Test
    void reportsAFailureRatherThanLosingTheMessage() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(CaughtMailService.CAUGHT_MAIL_PATH));
        this.context.resourceResolver().commit();

        assertTrue(this.service.sendMessage(this.message().text("x").build()).isCompletedExceptionally());
    }

    @Test
    void carriesHeadersGivenInBulk() throws Exception
    {
        this.service.sendMessage(this.message()
            .headers(List.of(new Header("X-One", "1")))
            .text("x")
            .build());

        assertTrue(List.of(this.caught().get("headers", new String[0])).contains("X-One: 1"));
    }

    @Test
    void reportsAPartItCannotRead() throws Exception
    {
        final Part unreadable = Mockito.mock(Part.class);
        Mockito.when(unreadable.getContent()).thenThrow(new IOException("unreadable"));

        assertThrows(MessagingException.class, () -> new CaughtMailService.Bodies().read(unreadable));
    }

    /**
     * Sets the service's references by hand. The SCR metadata a mock OSGi runtime would need is generated only
     * when the bundle is packaged.
     *
     * @param target the service to inject into
     * @param resolvers the resolver factory to inject
     * @throws Exception if the field cannot be set
     */
    private void inject(final Object target, final ResourceResolverFactory resolvers) throws Exception
    {
        set(target, "resolverFactory", resolvers);
        set(target, "metricsManager", this.metrics);
    }

    /** Sets one {@code @Reference} field. */
    private static void set(final Object target, final String name, final Object value) throws Exception
    {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * A factory that hands out the test's own resolver. A mock service resolver does not see the content the
     * test creates.
     *
     * @param resolver the resolver the test wrote its content through
     * @return a factory serving it, ignoring close so the test context keeps ownership
     */
    private static ResourceResolverFactory factory(final ResourceResolver resolver)
    {
        final ResourceResolver shared = new ResourceResolverWrapper(resolver)
        {
            @Override
            public void close()
            {
                // The test context owns this resolver
            }
        };
        return new ResourceResolverFactory()
        {
            @Override
            public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo)
            {
                return shared;
            }

            @Deprecated
            @Override
            public ResourceResolver getAdministrativeResourceResolver(final Map<String, Object> authenticationInfo)
            {
                return shared;
            }

            @Override
            public ResourceResolver getServiceResourceResolver(final Map<String, Object> authenticationInfo)
            {
                return shared;
            }

            @Override
            public ResourceResolver getThreadResourceResolver()
            {
                return shared;
            }

            @Deprecated
            @Override
            public List<String> getSearchPath()
            {
                return List.of();
            }
        };
    }

}
