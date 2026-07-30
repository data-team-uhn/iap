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
 * Records errors that a running instance could not deal with on its own, so that a system administrator can be told
 * about them long after the log file they were written to has rotated away.
 *
 * <p>
 * Code holding an OSGi reference should use this service directly; code that cannot, e.g. a commit hook constructed
 * per node, can reach the same recording through the static {@link ErrorLogger} facade.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ErrorLoggerService
{
    /** The path of the {@code err:LoggedErrorsHomepage} node holding the recorded errors. */
    String LOGGED_ERRORS_PATH = "/LoggedErrors";

    /**
     * Records one error, storing its stack trace under {@value #LOGGED_ERRORS_PATH}. Recording an error is a
     * best-effort diagnostic: an implementation must never throw, since the caller is by definition already dealing
     * with a failure, and must never be the reason a second one is raised.
     *
     * @param error the throwable to record, ignored when {@code null}
     */
    void logError(Throwable error);
}
