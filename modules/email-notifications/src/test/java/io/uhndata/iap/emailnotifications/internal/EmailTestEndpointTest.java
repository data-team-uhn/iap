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
import java.util.HashMap;
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
        // Left pending, for each test to complete as it needs
        this.sending = new CompletableFuture<>();
        when(this.mailService.sendMessage(ArgumentMatchers.<MimeMessage>any())).thenReturn(this.sending);
        this.endpoint = new EmailTestEndpoint();
        set("mailService", this.mailService);
    }

    @Test
    void onlyAdministratorsMaySendATestMessage() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("someone", new HashMap<>()), response);

        assertEquals(403, response.getStatus());
        verify(this.mailService, never()).sendMessage(ArgumentMatchers.<MimeMessage>any());
    }

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

        this.endpoint.doGet(request(null, new HashMap<>()), response);

        assertEquals(403, response.getStatus());
    }

    @Test
    void everyAddressIsRequired() throws IOException
    {
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(
            request("admin", new HashMap<>(Map.of("fromEmail", "a@example.invalid"))), response);

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

    @Test
    void aMessageThatCannotBeAssembledIsReportedWithoutQuotingTheMailServer() throws IOException, MessagingException
    {
        when(this.message.build()).thenThrow(new MessagingException(SERVER_TALK));
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(500, response.getStatus());
        assertRefusedWithoutQuotingTheMailServer(response);
    }

    @Test
    void aSendRefusedAfterTheMessageWasAssembledIsReported() throws IOException
    {
        this.sending.completeExceptionally(new MessagingException(SERVER_TALK));
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(500, response.getStatus());
        assertRefusedWithoutQuotingTheMailServer(response);
    }

    @Test
    void aSendThatOutlivesTheBudgetIsReportedAsStillGoing() throws IOException, ReflectiveOperationException
    {
        set("sendTimeoutMillis", 1L);
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(202, response.getStatus());
        assertTrue(response.getOutputAsString().contains("still being sent"));

        // The send fails after the answer, and its outcome is still logged
        this.sending.completeExceptionally(new MessagingException(SERVER_TALK));
    }

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
            // Cleared, or every later wait on this thread would throw
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
        final Map<String, Object> parameters = new HashMap<>(Map.of(
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
     * A request from a user. The resource resolver reports the canonical user id, and the request reports the name
     * as typed at login, here upper-cased. A fixture where the two agree could not tell which one the endpoint reads.
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
