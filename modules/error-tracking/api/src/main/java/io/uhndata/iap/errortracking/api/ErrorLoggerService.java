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
 * Records the errors a running instance could not deal with on its own, under {@value #LOGGED_ERRORS_PATH}. A system
 * administrator finds them there after the log file has rotated away.
 *
 * <p>
 * Most callers go through the static {@link ErrorLogger} facade, which does nothing while this service is not
 * running. A mandatory reference to the service would stop the caller from starting without it.
 * </p>
 *
 * <p>
 * No method throws, since the caller is already handling a failure. Recording is asynchronous: an implementation adds
 * the failure to a tally in memory, returns, and writes to the repository later. Commit hooks record errors, and on
 * Oak's segment store, a commit started from inside a commit hook waits forever for a lock its own thread holds.
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
     * Records one error, with no context.
     *
     * @param error the throwable to record; ignored if {@code null}
     */
    void logError(@Nullable Throwable error);

    /**
     * Records one error with what the caller knows about it.
     *
     * @param error the throwable to record; ignored if {@code null}
     * @param context which code was running, what it was doing, and to what; {@code null} is the same as
     *            {@link ErrorContext#NO_CONTEXT}
     */
    void logError(@Nullable Throwable error, @Nullable ErrorContext context);

    /**
     * Records something the instance found wrong without anything being thrown, most often a mis-authored
     * definition, such as a condition naming a comparator that does not exist. No stack trace is kept. The context's
     * component and operation say where the problem was noticed, and its subject what it was noticed on.
     *
     * @param problem a short phrase chosen in code, such as {@code unknown comparator}; ignored if {@code null} or
     *            blank. It decides whether two problems are the same fault, so it should not come from content. A
     *            phrase that quotes a value, such as {@code unknown comparator: 'sameDay'}, is recorded under its
     *            leading words, and kept whole as a message
     * @param context which code noticed, what it was doing, and what it was looking at; {@code null} is the same as
     *            {@link ErrorContext#NO_CONTEXT}
     */
    void logProblem(@Nullable String problem, @Nullable ErrorContext context);

    /**
     * How many recordings this service dropped since it started, because too many distinct faults were waiting to be
     * written.
     *
     * @return a count; zero unless the service fell behind
     */
    long getDroppedCount();
}
