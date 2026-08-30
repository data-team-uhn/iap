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
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.commons.messaging.mail.MessageBuilder;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailTestEndpoint}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EmailTestEndpointTest
{
    /** What a mail server says, which must never reach the caller. */
    private static final String SERVER_TALK = "relay smtp.internal:587 rejected user svc-iap: bad credentials";

    private final SlingContext context = new SlingContext();

    private final MailService mailService = mock(MailService.class);

    private final MessageBuilder message = mock(MessageBuilder.class, RETURNS_SELF);

    private CompletableFuture<Void> sending;

    private EmailTestEndpoint endpoint;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        when(this.mailService.getMessageBuilder()).thenReturn(this.message);
        // Sending is asynchronous, so the service hands back a future rather than a verdict. An unstubbed mock
        // would return null here and the endpoint would have nothing to wait on.
        this.sending = new CompletableFuture<>();
        when(this.mailService.sendMessage(ArgumentMatchers.<MimeMessage>any())).thenReturn(this.sending);
        this.endpoint = new EmailTestEndpoint();
        set("mailService", this.mailService);
    }

    @Test
    void onlyAdministratorsMaySendATestMessage() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("someone", new java.util.HashMap<>()), response);

        // The endpoint mails an arbitrary address, which is not something to leave open
        assertEquals(403, response.getStatus());
        verify(this.mailService, never()).sendMessage(ArgumentMatchers.<MimeMessage>any());
    }

    /**
     * A login resolves case-insensitively, so an administrator may well have typed "Admin" and the request reports
     * that spelling. Only the repository knows the account it resolved to, and refusing them on the typed form is
     * how this endpoint used to lock out its own audience.
     */
    @Test
    void admitsAnAdministratorWhoCapitalisedTheirNameAtLogin() throws IOException
    {
        this.sending.complete(null);
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", "Admin", parameters(null)), response);

        assertEquals(200, response.getStatus());
    }

    @Test
    void anAnonymousRequestIsRefusedToo() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request(null, new java.util.HashMap<>()), response);

        assertEquals(403, response.getStatus());
    }

    @Test
    void everyAddressIsRequired() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(
            request("admin", new java.util.HashMap<>(Map.of("fromEmail", "a@example.invalid"))), response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getOutputAsString().contains("Missing required URL parameters"));
    }

    @Test
    void sendsAPlainTextMessage() throws IOException, MessagingException
    {
        this.sending.complete(null);
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getOutputAsString().contains("The message was sent."));
        verify(this.message).from("from@example.invalid", "From");
        verify(this.message).to("to@example.invalid", "To");
        verify(this.message).replyTo("from@example.invalid");
        verify(this.message, never()).html(anyString());
        verify(this.mailService).sendMessage(ArgumentMatchers.<MimeMessage>any());
    }

    @Test
    void sendsARichTextMessageWhenAsked() throws IOException, MessagingException
    {
        this.sending.complete(null);
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters("true")), response);

        assertEquals(200, response.getStatus());
        verify(this.message).html(anyString());
    }

    /**
     * The caller is told that it failed and where to look, but not what the mail server said: that text names the
     * relay, its port and the account the instance authenticates with.
     */
    @Test
    void aMessageThatCannotBeAssembledIsReportedWithoutQuotingTheMailServer() throws IOException, MessagingException
    {
        when(this.message.build()).thenThrow(new MessagingException(SERVER_TALK));
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(500, response.getStatus());
        assertRefusedWithoutQuotingTheMailServer(response);
    }

    /**
     * The point of the whole exercise. Everything after the message is assembled happens on a thread pool, so a
     * send that is refused used to reach neither the response nor the log, and this endpoint answered that it had
     * worked.
     */
    @Test
    void aSendRefusedAfterTheMessageWasAssembledIsReported() throws IOException
    {
        this.sending.completeExceptionally(new MessagingException(SERVER_TALK));
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(500, response.getStatus());
        assertRefusedWithoutQuotingTheMailServer(response);
    }

    /**
     * A relay that never answers must not park the request thread. The send carries on, and says so through the
     * log rather than through a response nobody is waiting for any more.
     */
    @Test
    void aSendThatOutlivesTheBudgetIsReportedAsStillGoing() throws IOException, ReflectiveOperationException
    {
        set("sendTimeoutMillis", 1L);
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(202, response.getStatus());
        assertTrue(response.getOutputAsString().contains("still being sent"));

        // The answer has been written, and the send is still attached to something that will record how it ends
        this.sending.completeExceptionally(new MessagingException(SERVER_TALK));
    }

    /**
     * The container is entitled to see that it asked this thread to stop, so the flag is put back rather than
     * swallowed by the wait.
     */
    @Test
    void anInterruptedWaitIsReportedAsStillGoingAndPutsTheFlagBack() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();
        Thread.currentThread().interrupt();
        try {
            this.endpoint.doGet(request("admin", parameters(null)), response);

            assertEquals(202, response.getStatus());
            assertTrue(response.getOutputAsString().contains("still being sent"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Never leave it set: it would make every later test's wait throw
            Thread.interrupted();
        }
    }

    private void assertRefusedWithoutQuotingTheMailServer(final MockSlingJakartaHttpServletResponse response)
    {
        final String body = response.getOutputAsString();
        assertTrue(body.contains("Could not send the message"));
        assertTrue(body.contains("instance log"));
        assertFalse(body.contains("smtp.internal"));
        assertFalse(body.contains("svc-iap"));
    }

    private void set(final String field, final Object value) throws ReflectiveOperationException
    {
        final Field declared = EmailTestEndpoint.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(this.endpoint, value);
    }

    private Map<String, Object> parameters(final String isHtml)
    {
        final Map<String, Object> parameters = new java.util.HashMap<>(Map.of(
            "fromEmail", "from@example.invalid",
            "fromName", "From",
            "toEmail", "to@example.invalid",
            "toName", "To"));
        if (isHtml != null) {
            parameters.put("isHtml", isHtml);
        }
        return parameters;
    }

    /**
     * A request from a named caller. The id goes on the session, which is where the endpoint reads it, and the
     * request is deliberately made to report a differently capitalised spelling — that is what a case-insensitively
     * resolved login looks like, and a fixture where the two agree could not tell the two reads apart.
     */
    private MockSlingJakartaHttpServletRequest request(final String user, final Map<String, Object> parameters)
    {
        return request(user, user == null ? null : user.toUpperCase(Locale.ROOT), parameters);
    }

    private MockSlingJakartaHttpServletRequest request(final String canonical, final String typedAtLogin,
        final Map<String, Object> parameters)
    {
        final ResourceResolver resolver = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public String getUserID()
            {
                return canonical;
            }
        };
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext())
            {
                @Override
                public String getRemoteUser()
                {
                    return typedAtLogin;
                }
            };
        request.setParameterMap(parameters);
        return request;
    }

    private MockSlingJakartaHttpServletResponse response()
    {
        return new MockSlingJakartaHttpServletResponse();
    }
}
