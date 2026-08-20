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
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.apache.sling.api.resource.ResourceResolver;
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
    private final SlingContext context = new SlingContext();

    private final MailService mailService = mock(MailService.class);

    private final MessageBuilder message = mock(MessageBuilder.class, RETURNS_SELF);

    private EmailTestEndpoint endpoint;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        when(this.mailService.getMessageBuilder()).thenReturn(this.message);
        this.endpoint = new EmailTestEndpoint();
        final Field field = EmailTestEndpoint.class.getDeclaredField("mailService");
        field.setAccessible(true);
        field.set(this.endpoint, this.mailService);
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
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(200, response.getStatus());
        verify(this.message).from("from@example.invalid", "From");
        verify(this.message).to("to@example.invalid", "To");
        verify(this.message).replyTo("from@example.invalid");
        verify(this.message, never()).html(anyString());
        verify(this.mailService).sendMessage(ArgumentMatchers.<MimeMessage>any());
    }

    @Test
    void sendsARichTextMessageWhenAsked() throws IOException, MessagingException
    {
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
    void aRefusedMessageIsReportedWithoutQuotingTheMailServer() throws IOException, MessagingException
    {
        when(this.message.build())
            .thenThrow(new MessagingException("relay smtp.internal:587 rejected user svc-iap: bad credentials"));
        final MockSlingJakartaHttpServletResponse response = response();

        this.endpoint.doGet(request("admin", parameters(null)), response);

        assertEquals(500, response.getStatus());
        final String body = response.getOutputAsString();
        assertTrue(body.contains("Could not send the message"));
        assertFalse(body.contains("smtp.internal"));
        assertFalse(body.contains("svc-iap"));
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

    private MockSlingJakartaHttpServletRequest request(final String user, final Map<String, Object> parameters)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext())
            {
                @Override
                public String getRemoteUser()
                {
                    return user;
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
