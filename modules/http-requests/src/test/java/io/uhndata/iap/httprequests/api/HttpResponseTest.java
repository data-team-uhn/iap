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
package io.uhndata.iap.httprequests.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpResponse}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class HttpResponseTest
{
    @Test
    void keepsWhatTheServiceAnswered()
    {
        final HttpResponse response = new HttpResponse(201, "created");

        assertEquals(201, response.getStatusCode());
        assertEquals("created", response.getBody());
    }

    @Test
    void aMissingBodyReadsAsAnEmptyOne()
    {
        // Callers should never have to null-check a body they were handed
        assertEquals("", new HttpResponse(204, null).getBody());
    }

    @Test
    void onlyTwoHundredsAreSuccessful()
    {
        assertTrue(new HttpResponse(200, "").isSuccessful());
        assertTrue(new HttpResponse(299, "").isSuccessful());
        assertFalse(new HttpResponse(199, "").isSuccessful());
        assertFalse(new HttpResponse(300, "").isSuccessful());
        assertFalse(new HttpResponse(500, "").isSuccessful());
    }

    @Test
    void describesItselfForLogging()
    {
        assertEquals("404 not found", new HttpResponse(404, "not found").toString());
    }
}
