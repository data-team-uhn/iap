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
package io.uhndata.iap.workflows;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Where one parse of a diagram reports what it complains about. A parse survives every mistake it finds, so the
 * report is the only sign that the version runs differently from how it is drawn.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ParseProblems
{
    private final String path;

    /**
     * Starts a parse's reports.
     *
     * @param path the path of the version being parsed, which every complaint is about
     */
    ParseProblems(final String path)
    {
        this.path = path;
    }

    /**
     * The version being parsed.
     *
     * @return its path, for messages that are not complaints
     */
    String path()
    {
        return this.path;
    }

    /**
     * Where a complaint about this parse starts: filed under the translation, about the version being parsed.
     *
     * @return a fresh error context, for the caller to add its own details to
     */
    ErrorContext context()
    {
        return ErrorContext.of(WorkflowDefinitionUtils.class, WorkflowDefinitionUtils.TRANSLATION_OPERATION)
            .about(this.path);
    }

    /**
     * Logs a complaint.
     *
     * @param problem what is wrong
     * @param details where it is wrong, as built from {@link #context()}
     */
    void report(final String problem, final ErrorContext details)
    {
        ErrorLogger.logProblem(problem, details);
    }
}
