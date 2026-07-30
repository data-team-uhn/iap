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
package io.uhndata.iap.errortracking.internal;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ErrorLoggerImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ErrorLoggerImplTest
{
    private final SlingContext context = new SlingContext();

    private ErrorLoggerImpl logger;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.context.create().resource(ErrorLoggerService.LOGGED_ERRORS_PATH,
            "sling:resourceType", "err/LoggedErrorsHomepage");
        this.logger = new ErrorLoggerImpl();
        TestResolvers.inject(this.logger, this.context.resourceResolver());
    }

    @AfterEach
    void clearTheFacade()
    {
        ErrorLogger.setService(null);
    }

    @Test
    void recordsWhatWasThrownAndTheStackTrace()
    {
        this.logger.logError(new IllegalStateException("boom"));

        final ValueMap recorded = onlyRecordedError().getValueMap();
        assertEquals("err:LoggedError", recorded.get("jcr:primaryType", String.class));
        assertEquals("java.lang.IllegalStateException", recorded.get("type", String.class));
        assertEquals("boom", recorded.get("message", String.class));
        assertTrue(recorded.get("stackTrace", "").startsWith("java.lang.IllegalStateException: boom"));
        assertTrue(recorded.get("stackTrace", "").contains(getClass().getName()));
    }

    @Test
    void recordsTheCausesToo()
    {
        this.logger.logError(new IllegalStateException("outer", new IllegalArgumentException("inner")));

        final String trace = onlyRecordedError().getValueMap().get("stackTrace", "");
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException: inner"));
    }

    @Test
    void recordsAnErrorThrownWithoutAMessage()
    {
        this.logger.logError(new IllegalStateException());

        final ValueMap recorded = onlyRecordedError().getValueMap();
        assertEquals("java.lang.IllegalStateException", recorded.get("type", String.class));
        assertNull(recorded.get("message", String.class));
    }

    @Test
    void recordsWhenAnErrorWasFirstAndLastSeen()
    {
        this.logger.logError(new IllegalStateException("boom"));

        final ValueMap recorded = onlyRecordedError().getValueMap();
        assertEquals(1L, recorded.get("occurrences", Long.class));
        assertNotNull(recorded.get("lastOccurrence", Calendar.class));
    }

    @Test
    void differentErrorsAreRecordedSeparately()
    {
        this.logger.logError(new IllegalStateException("first"));
        this.logger.logError(new IllegalStateException("second"));

        assertEquals(2, recordedErrors().size());
    }

    @Test
    void theSameErrorFromTheSamePlaceIsCountedNotCopied()
    {
        // A failure repeating in a loop must not fill the container with copies of itself
        final Throwable repeated = new IllegalStateException("boom");
        this.logger.logError(repeated);
        this.logger.logError(repeated);
        this.logger.logError(repeated);

        final ValueMap recorded = onlyRecordedError().getValueMap();
        assertEquals(3L, recorded.get("occurrences", Long.class));
    }

    @Test
    void theSameFailureThrownAgainFromTheSameLineIsCounted()
    {
        for (int i = 0; i < 4; i++) {
            this.logger.logError(new IllegalStateException("boom"));
        }

        // Distinct throwables, but identical stack traces: the same failure in the same place
        assertEquals(4L, onlyRecordedError().getValueMap().get("occurrences", Long.class));
    }

    @Test
    void anErrorNamingWhatItFailedOnIsRecordedForEachOfThem()
    {
        // Two errors thrown from the same line, but about different things, are two errors
        for (final String subject : List.of("/first", "/second")) {
            this.logger.logError(new IllegalStateException("Cannot read " + subject));
        }

        assertEquals(2, recordedErrors().size());
    }

    @Test
    void countingAnotherOccurrenceMovesTheLastSeenDateOn() throws Exception
    {
        final Throwable repeated = new IllegalStateException("boom");
        this.logger.logError(repeated);
        final Calendar firstSeen = onlyRecordedError().getValueMap().get("lastOccurrence", Calendar.class);

        Thread.sleep(2);
        this.logger.logError(repeated);

        final ValueMap recorded = onlyRecordedError().getValueMap();
        assertTrue(recorded.get("lastOccurrence", Calendar.class).after(firstSeen));
        // The first-seen date, which mix:created owns, is not touched by a later occurrence
        assertEquals(2L, recorded.get("occurrences", Long.class));
    }

    @Test
    void ignoresNothingToRecord()
    {
        this.logger.logError(null);

        assertTrue(recordedErrors().isEmpty());
    }

    @Test
    void survivesAMissingHomepage() throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        resolver.delete(resolver.getResource(ErrorLoggerService.LOGGED_ERRORS_PATH));
        resolver.commit();

        // A deployment whose repoinit did not run must not turn one failure into two
        assertDoesNotThrow(() -> this.logger.logError(new IllegalStateException("boom")));
    }

    @Test
    void survivesARecordedErrorItCannotUpdate() throws ReflectiveOperationException
    {
        final Throwable repeated = new IllegalStateException("boom");
        this.logger.logError(repeated);
        TestResolvers.inject(this.logger, readOnlyErrors(this.context.resourceResolver()));

        // Losing the count of a repeat is a nuisance; raising a second failure at the caller is not acceptable
        assertDoesNotThrow(() -> this.logger.logError(repeated));
        assertEquals(1L, onlyRecordedError().getValueMap().get("occurrences", Long.class));
    }

    @Test
    void survivesAnUnreachableRepository() throws ReflectiveOperationException
    {
        TestResolvers.inject(this.logger, null);

        assertDoesNotThrow(() -> this.logger.logError(new IllegalStateException("boom")));
        assertTrue(recordedErrors().isEmpty());
    }

    @Test
    void publishesItselfToTheFacadeWhileItRuns()
    {
        this.logger.activate();
        ErrorLogger.logError(new IllegalStateException("boom"));
        assertEquals(1, recordedErrors().size());

        this.logger.deactivate();
        ErrorLogger.logError(new IllegalStateException("boom again"));

        // Once stopped, the component's service references are gone, so the facade must stop calling it
        assertEquals(1, recordedErrors().size());
    }

    /**
     * A view of the repository in which the already recorded errors cannot be modified, e.g. because the recording
     * session lost the right to write them.
     *
     * @param resolver the resolver to wrap
     * @return a resolver handing out unmodifiable recorded errors
     */
    private ResourceResolver readOnlyErrors(final ResourceResolver resolver)
    {
        return new ResourceResolverWrapper(resolver)
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource home = super.getResource(path);
                return home == null ? null : new ResourceWrapper(home)
                {
                    @Override
                    public Resource getChild(final String name)
                    {
                        final Resource child = super.getChild(name);
                        return child == null ? null : new ResourceWrapper(child)
                        {
                            @Override
                            public <T> T adaptTo(final Class<T> type)
                            {
                                return type == ModifiableValueMap.class ? null : super.adaptTo(type);
                            }
                        };
                    }
                };
            }
        };
    }

    private Resource onlyRecordedError()
    {
        final List<Resource> errors = recordedErrors();
        assertEquals(1, errors.size());
        return errors.get(0);
    }

    private List<Resource> recordedErrors()
    {
        final Resource home = this.context.resourceResolver().getResource(ErrorLoggerService.LOGGED_ERRORS_PATH);
        assertFalse(home == null, "The recorded errors homepage is missing");
        final List<Resource> errors = new ArrayList<>();
        home.getChildren().forEach(errors::add);
        return errors;
    }
}
