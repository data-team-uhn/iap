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
 * The normal way to record an error: a static facade over the {@link ErrorLoggerService}, safe to call from anywhere
 * at any time.
 *
 * <p>
 * Before the service is available, and after it goes away, the call is silently ignored rather than failing the
 * caller — which is, by definition, already handling a failure. That tolerance is the reason to prefer this over an
 * OSGi reference rather than a mere convenience for code that cannot hold one: a component whose failures are worth
 * recording must not refuse to start because the thing that records them is missing, and expressing that as an
 * optional dynamic reference in every such component is the same null check written many times over.
 * </p>
 *
 * <p>
 * It is also the only way in for code the service registry cannot inject into at all: a commit hook constructed anew
 * for every node of every commit, or a component that must record a failure from inside its own activation, before
 * its references are set.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ErrorLogger
{
    /** Written by the service component as it starts and stops, read from arbitrary threads. */
    private static volatile ErrorLoggerService service;

    /** Only static methods, no instances. */
    private ErrorLogger()
    {
        // Utility class
    }

    /**
     * Records one error, under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}, with nothing said about the
     * circumstances. Does nothing when the error tracking service is not available.
     *
     * @param error the throwable to record, ignored when {@code null}
     */
    public static void logError(@Nullable final Throwable error)
    {
        logError(error, ErrorContext.EMPTY);
    }

    /**
     * Records one error along with what the caller knows about the circumstances. Does nothing when the error
     * tracking service is not available.
     *
     * @param error the throwable to record, ignored when {@code null}
     * @param context which code was running, what it was doing, and what it was doing it to
     */
    public static void logError(@Nullable final Throwable error, @Nullable final ErrorContext context)
    {
        final ErrorLoggerService current = service;
        if (current != null) {
            current.logError(error, context);
        }
    }

    /**
     * Records something the instance found wrong but did not throw over. Does nothing when the error tracking
     * service is not available.
     *
     * @param problem what is wrong, a short phrase chosen in code; ignored when {@code null} or blank
     * @param context which code noticed, what it was doing, and what it was looking at
     */
    public static void logProblem(@Nullable final String problem, @Nullable final ErrorContext context)
    {
        final ErrorLoggerService current = service;
        if (current != null) {
            current.logProblem(problem, context);
        }
    }

    /**
     * Publishes the service backing this facade. Reserved for the error tracking component itself, which calls this
     * as it starts.
     *
     * @param newService the service to record errors through, {@code null} to record nothing
     */
    public static void setService(final ErrorLoggerService newService)
    {
        service = newService;
    }

    /**
     * Withdraws the service backing this facade. Reserved for the error tracking component itself, which calls this
     * as it stops. A component that has already been replaced by a newer one withdraws nothing, so that stopping the
     * old one does not silently switch error recording off.
     *
     * @param oldService the service that is going away
     */
    public static void unsetService(final ErrorLoggerService oldService)
    {
        if (service == oldService) {
            service = null;
        }
    }
}
