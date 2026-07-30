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

/**
 * A static way to reach the {@link ErrorLoggerService}, for code that cannot hold an OSGi reference to it: a commit
 * hook constructed anew for every node of every commit, or any other short-lived object created outside the service
 * registry. Code that can simply reference the service should do that instead.
 *
 * <p>
 * Recording an error through this facade is always safe: before the service is available, and after it goes away,
 * the call is silently ignored rather than failing the caller — which is, by definition, already handling a failure.
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
     * Records one error, storing its stack trace under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}. Does nothing
     * when the error tracking service is not available.
     *
     * @param error the throwable to record, ignored when {@code null}
     */
    public static void logError(final Throwable error)
    {
        final ErrorLoggerService current = service;
        if (current != null) {
            current.logError(error);
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
