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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ErrorLogger}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ErrorLoggerTest
{
    /** Records what it was asked to log, standing in for the real service. */
    private static final class RecordingService implements ErrorLoggerService
    {
        private final List<Throwable> recorded = new ArrayList<>();

        private final List<String> problems = new ArrayList<>();

        private final List<ErrorContext> contexts = new ArrayList<>();

        @Override
        public void logError(final Throwable error)
        {
            logError(error, ErrorContext.EMPTY);
        }

        @Override
        public void logError(final Throwable error, final ErrorContext context)
        {
            this.recorded.add(error);
            this.contexts.add(context);
        }

        @Override
        public void logProblem(final String problem, final ErrorContext context)
        {
            this.problems.add(problem);
            this.contexts.add(context);
        }
    }

    private final RecordingService service = new RecordingService();

    @AfterEach
    void clearTheFacade()
    {
        ErrorLogger.setService(null);
    }

    @Test
    void recordsThroughThePublishedService()
    {
        final Throwable error = new IllegalStateException("boom");
        ErrorLogger.setService(this.service);

        ErrorLogger.logError(error);

        assertEquals(List.of(error), this.service.recorded);
    }

    @Test
    void withoutAServiceRecordingIsSilentlySkipped()
    {
        // Before the module starts there is nothing to record through, and the caller — already handling a
        // failure — must not be given a second one
        assertDoesNotThrow(() -> ErrorLogger.logError(new IllegalStateException("boom")));
    }

    @Test
    void aStoppedServiceStopsRecording()
    {
        ErrorLogger.setService(this.service);
        ErrorLogger.unsetService(this.service);

        ErrorLogger.logError(new IllegalStateException("boom"));

        assertTrue(this.service.recorded.isEmpty());
    }

    @Test
    void aServiceThatWasAlreadyReplacedWithdrawsNothing()
    {
        final RecordingService replacement = new RecordingService();
        ErrorLogger.setService(this.service);
        ErrorLogger.setService(replacement);
        // The old component stopping after a new one took over must not switch recording off
        ErrorLogger.unsetService(this.service);

        ErrorLogger.logError(new IllegalStateException("boom"));

        assertEquals(1, replacement.recorded.size());
        assertTrue(this.service.recorded.isEmpty());
    }

    @Test
    void recordsWhatTheCallerKnowsAboutTheCircumstances()
    {
        final ErrorContext context = ErrorContext.of(ErrorLoggerTest.class, "doTheThing").about("/Submissions/1");
        ErrorLogger.setService(this.service);

        ErrorLogger.logError(new IllegalStateException("boom"), context);

        assertEquals(List.of(context), this.service.contexts);
    }

    @Test
    void recordsAProblemNothingWasThrownFor()
    {
        ErrorLogger.setService(this.service);

        ErrorLogger.logProblem("unknown comparator", ErrorContext.EMPTY);

        assertEquals(List.of("unknown comparator"), this.service.problems);
    }

    @Test
    void withoutAServiceRecordingAProblemIsSilentlySkipped()
    {
        assertDoesNotThrow(() -> ErrorLogger.logProblem("unknown comparator", ErrorContext.EMPTY));
    }

    @Test
    void recordingWithoutAContextSaysNothingAboutTheCircumstances()
    {
        ErrorLogger.setService(this.service);

        ErrorLogger.logError(new IllegalStateException("boom"));

        assertEquals(List.of(ErrorContext.EMPTY), this.service.contexts);
    }

    @Test
    void isAUtilityClass() throws ReflectiveOperationException
    {
        final Constructor<ErrorLogger> constructor = ErrorLogger.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertNotNull(constructor.newInstance());
    }
}
