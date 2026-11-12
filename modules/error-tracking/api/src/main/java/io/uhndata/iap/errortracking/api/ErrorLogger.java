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

import org.jetbrains.annotations.Nullable;

/**
 * Records errors through the {@link ErrorLoggerService}, from any code and any thread. This static facade is the
 * normal way to record an error.
 *
 * <p>
 * While the service is not running, a call does nothing. A component that records its failures through this facade
 * needs no reference to the service, and starts whether or not error tracking is running.
 * </p>
 *
 * <p>
 * It also serves code that OSGi does not construct, such as the Oak editors that run inside a commit.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ErrorLogger
{
    /** The service to record through, {@code null} while none is running. Read from any thread. */
    private static volatile ErrorLoggerService service;

    /** Only static methods, no instances. */
    private ErrorLogger()
    {
        // Utility class
    }

    /**
     * Records one error under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}, with no context. Does nothing while the
     * service is not running.
     *
     * @param error the throwable to record; ignored if {@code null}
     */
    public static void logError(@Nullable final Throwable error)
    {
        logError(error, ErrorContext.NO_CONTEXT);
    }

    /**
     * Records one error with what the caller knows about it. Does nothing while the service is not running.
     *
     * @param error the throwable to record; ignored if {@code null}
     * @param context which code was running, what it was doing, and to what; {@code null} is the same as
     *            {@link ErrorContext#NO_CONTEXT}
     */
    public static void logError(@Nullable final Throwable error, @Nullable final ErrorContext context)
    {
        final ErrorLoggerService current = service;
        if (current != null) {
            current.logError(error, context);
        }
    }

    /**
     * Records something the instance found wrong without anything being thrown. Does nothing while the service is
     * not running.
     *
     * @param problem a short phrase chosen in code, such as {@code unknown comparator}; ignored if {@code null} or
     *            blank. {@link ErrorLoggerService#logProblem} says how a phrase that quotes a value is recorded
     * @param context which code noticed, what it was doing, and what it was looking at; {@code null} is the same as
     *            {@link ErrorContext#NO_CONTEXT}
     */
    public static void logProblem(@Nullable final String problem, @Nullable final ErrorContext context)
    {
        final ErrorLoggerService current = service;
        if (current != null) {
            current.logProblem(problem, context);
        }
    }

    /**
     * Sets the service this facade records through. Only that service should call this, as it starts.
     *
     * @param newService the service to record errors through, or {@code null} to record nothing
     */
    public static void setService(final ErrorLoggerService newService)
    {
        service = newService;
    }

    /**
     * Clears the service this facade records through. If a newer service has replaced it since, the facade keeps the
     * newer one. Only that service should call this, as it stops.
     *
     * @param oldService the service that is stopping
     */
    public static void unsetService(final ErrorLoggerService oldService)
    {
        if (service == oldService) {
            service = null;
        }
    }
}
