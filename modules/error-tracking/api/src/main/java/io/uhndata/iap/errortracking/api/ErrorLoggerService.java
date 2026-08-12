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
 * Records errors that a running instance could not deal with on its own, so that a system administrator can be told
 * about them long after the log file they were written to has rotated away.
 *
 * <p>
 * Code holding an OSGi reference can use this service directly, but most callers should go through the static
 * {@link ErrorLogger} facade instead: it tolerates the service being absent, which is exactly what a diagnostic sink
 * should do, whereas a mandatory OSGi reference would stop a working component from starting because the thing that
 * records its failures is missing.
 * </p>
 *
 * <p>
 * Recording is asynchronous. An implementation folds the failure into an in-memory tally and returns; the repository
 * catches up shortly afterwards. That is not an optimization but a requirement: the most valuable callers are commit
 * hooks, and a repository write made from inside a commit cannot complete.
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
     * Records one error, under {@value #LOGGED_ERRORS_PATH}, with nothing said about the circumstances. Recording an
     * error is a best-effort diagnostic: an implementation must never throw, since the caller is by definition
     * already dealing with a failure, and must never be the reason a second one is raised.
     *
     * @param error the throwable to record, ignored when {@code null}
     */
    void logError(@Nullable Throwable error);

    /**
     * Records one error along with what the caller knows about the circumstances.
     *
     * @param error the throwable to record, ignored when {@code null}
     * @param context which code was running, what it was doing, and what it was doing it to; {@code null} is the
     *            same as {@link ErrorContext#EMPTY}
     */
    void logError(@Nullable Throwable error, @Nullable ErrorContext context);

    /**
     * Records something the instance found wrong but did not throw over — most often a mis-authored definition, such
     * as a condition naming a comparator that does not exist. There is no stack trace worth keeping for these: where
     * the problem was noticed is the context's component and operation, and what it was noticed on is its subject.
     *
     * @param problem what is wrong, a short phrase chosen in code such as {@code unknown comparator}; should not be
     *            derived from content, since it takes part in deciding whether two problems are the same one. A
     *            phrase that quotes something is recorded all the same, under its stable leading part, with the whole
     *            phrase kept the way a throwable's message is. Ignored only when {@code null} or blank
     * @param context which code noticed, what it was doing, and what it was looking at; {@code null} is the same as
     *            {@link ErrorContext#EMPTY}
     */
    void logProblem(@Nullable String problem, @Nullable ErrorContext context);

    /**
     * How many recordings had to be dropped because too many distinct faults were waiting to be written at once. Part
     * of the service rather than an internal counter because being unable to keep up must not be one more silent
     * failure: what reports the recorded errors reports this alongside them.
     *
     * @return a count, zero in every healthy instance
     */
    long getDroppedCount();
}
