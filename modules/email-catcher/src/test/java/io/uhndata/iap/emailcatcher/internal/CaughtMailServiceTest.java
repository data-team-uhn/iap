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
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CaughtMailService}: that a message handed over is readable afterwards, and says the same
 * things it would have said to a recipient.
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

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.create().resource(CaughtMailService.CAUGHT_MAIL_PATH,
            "sling:resourceType", "mail/CaughtMailHomepage");
        inject(this.service, factory(this.context.resourceResolver()));
    }

    /** The one message every test starts from, before whatever it is actually about. */
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

    // Addresses as a recipient's client would have shown them, display names and all
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

    // Whether an address was visible to the others is part of what a reader is checking, so a bcc must not
    // arrive looking like a to
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

    // A message carrying both is a multipart, and both halves are worth reading back
    @Test
    void readsBothBodiesOutOfAMultipart() throws Exception
    {
        this.service.sendMessage(this.message().text("Plain").html("<p>Rich</p>").build());

        final ValueMap message = this.caught();
        assertEquals("Plain", message.get("textBody", String.class));
        assertEquals("<p>Rich</p>", message.get("htmlBody", String.class));
    }

    // The body is still the body when the message also carries parts, which means descending past the wrapper
    @Test
    void findsTheBodyInsideAMessageThatCarriesAttachments() throws Exception
    {
        this.service.sendMessage(this.message()
            .text("A body")
            .attachment("data".getBytes(StandardCharsets.UTF_8), "text/plain", "note.txt")
            .build());

        assertEquals("A body", this.caught().get("textBody", String.class));
    }

    // An attachment's bytes are not what anybody reads a caught message to check, and a text attachment must
    // not be mistaken for the message's own body
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
        // The ones read into properties are not repeated here
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

    // When it was caught, not when it was built: a message carrying an old date is still caught now, which is
    // what a reader sorting by this property means by it
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
        // And the message's own date is still readable, as the header it always was
        assertTrue(List.of(this.caught().get("headers", new String[0])).stream()
            .anyMatch(header -> header.startsWith("Date:")));
    }

    // Nothing consumes the future this is reported in, so the failure has to be visible some other way; what is
    // asserted here is that it is a failure at all rather than a silently dropped message
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

    // A part whose bytes cannot be read is a message that cannot be described, and saying so is better than
    // filing one whose body silently went missing
    @Test
    void reportsAPartItCannotRead() throws Exception
    {
        final Part unreadable = Mockito.mock(Part.class);
        Mockito.when(unreadable.getContent()).thenThrow(new IOException("unreadable"));

        assertThrows(MessagingException.class, () -> new CaughtMailService.Bodies().read(unreadable));
    }

    /**
     * Sets the service's resolver factory, which OSGi would inject. Done by hand because the SCR metadata that
     * would let a mock runtime do it is only generated when the bundle is packaged.
     *
     * @param target the service to inject into
     * @param resolvers what to inject
     * @throws Exception if the field cannot be set
     */
    private static void inject(final Object target, final ResourceResolverFactory resolvers) throws Exception
    {
        final Field field = target.getClass().getDeclaredField("resolverFactory");
        field.setAccessible(true);
        field.set(target, resolvers);
    }

    /**
     * A factory handing out the test's own resolver. The mock service resolvers do not share the repository the
     * test populates, so a service asking for one would find an empty one.
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
