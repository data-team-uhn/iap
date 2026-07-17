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

package io.uhndata.iap.scripting;

import javax.script.Bindings;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link StatusCodeSetter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class StatusCodeSetterTest
{
    private SlingJakartaHttpServletResponse response;

    private StatusCodeSetter statusCodeSetter;

    @BeforeEach
    public void setup()
    {
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        final Bindings bindings = Mockito.mock(Bindings.class);
        Mockito.when(bindings.get("jakartaResponse")).thenReturn(this.response);
        this.statusCodeSetter = new StatusCodeSetter();
        this.statusCodeSetter.init(bindings);
    }

    @Test
    public void testOk()
    {
        this.statusCodeSetter.ok();
        Mockito.verify(this.response).setStatus(200);
    }

    @Test
    public void testCreated()
    {
        this.statusCodeSetter.created();
        Mockito.verify(this.response).setStatus(201);
    }

    @Test
    public void testAccepted()
    {
        this.statusCodeSetter.accepted();
        Mockito.verify(this.response).setStatus(202);
    }

    @Test
    public void testNoContent()
    {
        this.statusCodeSetter.noContent();
        Mockito.verify(this.response).setStatus(204);
    }

    @Test
    public void testBadRequest()
    {
        this.statusCodeSetter.badRequest();
        Mockito.verify(this.response).setStatus(400);
    }

    @Test
    public void testUnauthorized()
    {
        this.statusCodeSetter.unauthorized();
        Mockito.verify(this.response).setStatus(401);
    }

    @Test
    public void testForbidden()
    {
        this.statusCodeSetter.forbidden();
        Mockito.verify(this.response).setStatus(403);
    }

    @Test
    public void testNotFound()
    {
        this.statusCodeSetter.notFound();
        Mockito.verify(this.response).setStatus(404);
    }

    @Test
    public void testMethodNotAllowed()
    {
        this.statusCodeSetter.methodNotAllowed();
        Mockito.verify(this.response).setStatus(405);
    }

    @Test
    public void testNotAcceptable()
    {
        this.statusCodeSetter.notAcceptable();
        Mockito.verify(this.response).setStatus(406);
    }

    @Test
    public void testConflict()
    {
        this.statusCodeSetter.conflict();
        Mockito.verify(this.response).setStatus(409);
    }

    @Test
    public void testLocked()
    {
        this.statusCodeSetter.locked();
        Mockito.verify(this.response).setStatus(423);
    }

    @Test
    public void testInternalServerError()
    {
        this.statusCodeSetter.internalServerError();
        Mockito.verify(this.response).setStatus(500);
    }

    @Test
    public void testNotImplemented()
    {
        this.statusCodeSetter.notImplemented();
        Mockito.verify(this.response).setStatus(501);
    }
}
