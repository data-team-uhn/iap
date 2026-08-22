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
package io.uhndata.iap.errortracking.api;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ErrorContext}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ErrorContextTest
{
    @Test
    void namesTheCodeThatFailedAndWhatItWasDoing()
    {
        final ErrorContext context = ErrorContext.of(ErrorContextTest.class, "doTheThing");

        assertEquals("io.uhndata.iap.errortracking.api.ErrorContextTest", context.getComponent());
        assertEquals("doTheThing", context.getOperation());
    }

    @Test
    void takesAComponentByNameToo()
    {
        assertEquals("some.Thing", ErrorContext.of("some.Thing", "doTheThing").getComponent());
    }

    @Test
    void knowsNothingWhenNothingWasSaid()
    {
        assertNull(ErrorContext.EMPTY.getComponent());
        assertNull(ErrorContext.EMPTY.getOperation());
        assertNull(ErrorContext.EMPTY.getSubject());
        assertNull(ErrorContext.EMPTY.getActor());
        assertTrue(ErrorContext.EMPTY.getDetails().isEmpty());
    }

    @Test
    void buildsUpByCopyingRatherThanChanging()
    {
        final ErrorContext original = ErrorContext.of(ErrorContextTest.class, "doTheThing");

        final ErrorContext extended = original.about("/Submissions/1").actingFor("alice").with("attempt", 3);

        assertNull(original.getSubject());
        assertEquals("/Submissions/1", extended.getSubject());
        assertEquals("alice", extended.getActor());
        assertEquals(Map.of("attempt", "3"), extended.getDetails());
    }

    @Test
    void namesTheContentAFailureWasAbout()
    {
        final Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getPath()).thenReturn("/Submissions/1");

        assertEquals("/Submissions/1", ErrorContext.EMPTY.about(resource).getSubject());
    }

    @Test
    void everythingIsOptional()
    {
        // Describing a failure must never need a null check at a site that is already failing
        final ErrorContext context = ErrorContext.of((Class<?>) null, null)
            .about((String) null).about((Resource) null).actingFor(null).with(null, "value").with("key", null);

        assertTrue(context.getDetails().isEmpty());
        assertNull(context.getComponent());
        assertNull(context.getSubject());
    }

    @Test
    void blankIsTheSameAsAbsent()
    {
        final ErrorContext context = ErrorContext.of("  ", " ").about("   ").actingFor("\t").with(" ", "value");

        assertNull(context.getComponent());
        assertNull(context.getOperation());
        assertNull(context.getSubject());
        assertNull(context.getActor());
        assertTrue(context.getDetails().isEmpty());
    }

    @Test
    void surroundingWhitespaceIsTrimmedAway()
    {
        assertEquals("doTheThing", ErrorContext.of(" x ", " doTheThing ").getOperation());
        assertEquals("x", ErrorContext.of(" x ", "y").getComponent());
        assertEquals("/Submissions/1", ErrorContext.EMPTY.about(" /Submissions/1 ").getSubject());
        assertEquals("alice", ErrorContext.EMPTY.actingFor(" alice ").getActor());
    }

    @Test
    void detailsKeepTheOrderTheyWereGivenIn()
    {
        final ErrorContext context = ErrorContext.EMPTY.with("first", 1).with("second", 2).with("third", 3);

        assertEquals(List.of("first", "second", "third"), List.copyOf(context.getDetails().keySet()));
    }

    @Test
    void aDetailGivenTwiceKeepsTheLatestValue()
    {
        assertEquals(Map.of("attempt", "2"), ErrorContext.EMPTY.with("attempt", 1).with("attempt", 2).getDetails());
    }

    @Test
    void detailsAreReadOnly()
    {
        final Map<String, String> details = ErrorContext.EMPTY.with("attempt", 1).getDetails();

        assertThrows(UnsupportedOperationException.class, () -> details.put("sneaky", "value"));
    }

    @Test
    void survivesADetailThatCannotDescribeItself()
    {
        // A half-built object at a failure site is exactly where toString() is likely to throw
        final Object unprintable = new Object()
        {
            @Override
            public String toString()
            {
                throw new IllegalStateException("not while I am broken");
            }
        };

        assertEquals("<toString failed: java.lang.IllegalStateException>",
            ErrorContext.EMPTY.with("thing", unprintable).getDetails().get("thing"));
    }

    @Test
    void aVeryLongDetailIsCutShort()
    {
        final String rendered = ErrorContext.EMPTY.with("essay", "x".repeat(1000)).getDetails().get("essay");

        assertEquals(501, rendered.length());
        assertTrue(rendered.endsWith("…"));
    }

    @Test
    void onlySoManyDetailsAreKept()
    {
        ErrorContext context = ErrorContext.EMPTY;
        for (int i = 0; i < 30; i++) {
            context = context.with("detail" + i, i);
        }

        assertEquals(20, context.getDetails().size());
        // A detail already known still takes its new value once the sample is full
        assertEquals("99", context.with("detail0", 99).getDetails().get("detail0"));
        assertEquals(20, context.with("detail99", 1).getDetails().size());
    }

    @Test
    void describesItselfForALogLine()
    {
        final ErrorContext context = ErrorContext.of("some.Thing", "doTheThing").about("/x").actingFor("alice");

        assertEquals("ErrorContext[component=some.Thing, operation=doTheThing, subject=/x, actor=alice, details={}]",
            context.toString());
    }
}
