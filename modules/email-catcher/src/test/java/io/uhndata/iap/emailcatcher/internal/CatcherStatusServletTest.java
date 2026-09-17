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
import java.io.StringReader;
import java.lang.reflect.Field;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import org.apache.sling.commons.messaging.mail.MailService;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public half of the catcher's status: one boolean, readable by anyone.
 *
 * <p>
 * What it must <em>not</em> answer is as much the point as what it does. This is the one endpoint an
 * unauthenticated visitor reaches, so a count, a path or a subject leaking through it would undo the
 * narrowing that made {@code /CaughtMail} administrators-only.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CatcherStatusServletTest
{
    private static final String NODE = "/libs/iap/mail-catcher";

    private final SlingContext context = new SlingContext();

    private final CatcherStatusServlet servlet = new CatcherStatusServlet();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(NODE, "sling:resourceType", "mail/CatcherStatus");
    }

    /** Puts a catcher in place, the way the service registry would while it is switched on. */
    private void catcherIsRegistered() throws ReflectiveOperationException
    {
        final Field field = CatcherStatusServlet.class.getDeclaredField("catcher");
        field.setAccessible(true);
        field.set(this.servlet, Mockito.mock(MailService.class));
    }

    private JsonObject answer() throws IOException
    {
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(),
                this.context.bundleContext());
        request.setResource(this.context.resourceResolver().getResource(NODE));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request, response);

        return Json.createReader(new StringReader(response.getOutputAsString())).readObject();
    }

    @Test
    void saysNothingIsBeingCaughtWhenNoCatcherIsRegistered() throws IOException
    {
        assertFalse(answer().getBoolean("catching"));
    }

    @Test
    void saysMailIsBeingCaughtWhileOneIs() throws Exception
    {
        catcherIsRegistered();

        assertTrue(answer().getBoolean("catching"));
    }

    @Test
    void answersTheBooleanAndNothingElse() throws Exception
    {
        // Anyone at all can read this, signed in or not. A count would say how much traffic an
        // instance has had; a path or a subject would say what was in it.
        catcherIsRegistered();

        assertEquals(1, answer().size());
        assertTrue(answer().containsKey("catching"));
    }
}
