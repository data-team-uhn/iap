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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * Where one parse of a diagram reports what it complains about. A parse survives every mistake it finds, so the
 * report is the only sign that the version runs differently from how it is drawn. Each complaint is collected as
 * one line, stored on the version, and any complaint keeps the version from being activated.
 *
 * <p>A complaint about the diagram is the author's to fix, and the stored line is how they learn of it. A complaint
 * about the vocabulary is a defect only an administrator can fix, so it is also recorded in the error log, and its
 * line tells the author to contact one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ParseProblems
{
    private static final String CONTACT_ADMINISTRATOR_MESSAGE = "Please contact an administrator";

    private static final String UNREADABLE_PROBLEM = "the process could not be read";

    private static final String EXCEPTION_DETAIL = "exception";

    private final String path;

    private final List<String> lines = new ArrayList<>();

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
     * The problems of a parse that threw before it finished: one line saying the process could not be read, and
     * naming the failure.
     *
     * @param path the path of the version being parsed
     * @param failure what the parse threw
     * @return the one complaint
     */
    @NotNull
    public static ParseProblems ofFailure(@NotNull final String path, @NotNull final Exception failure)
    {
        final ParseProblems result = new ParseProblems(path);
        result.report(UNREADABLE_PROBLEM, result.context().with(EXCEPTION_DETAIL, failure.getClass().getSimpleName()));
        return result;
    }

    /**
     * Collects a complaint about the diagram, which its author can fix.
     *
     * @param problem what is wrong
     * @param details where it is wrong, as built from {@link #context()}
     */
    void report(final String problem, final ErrorContext details)
    {
        this.lines.add(line(problem, details));
    }

    /**
     * Records and collects a defect in the vocabulary, which only an administrator can fix. The line tells the
     * author to contact one.
     *
     * @param problem what is wrong
     * @param details where it is wrong, as built from {@link #context()}
     */
    void reportDefect(final String problem, final ErrorContext details)
    {
        ErrorLogger.logProblem(problem, details);
        this.lines.add(line(problem, details) + ". " + CONTACT_ADMINISTRATOR_MESSAGE);
    }

    /**
     * What the parse complained about.
     *
     * @return one line per complaint, in report order: the problem, then its details in parentheses with the element
     *         id first, then the advice to contact an administrator for a defect in the vocabulary
     */
    @NotNull
    public List<String> lines()
    {
        return List.copyOf(this.lines);
    }

    private static String line(final String problem, final ErrorContext details)
    {
        final Map<String, String> named = details.getDetails();
        final List<String> parts = Stream.concat(
            Stream.ofNullable(named.get(WorkflowDefinitionUtils.ELEMENT_DETAIL)),
            named.entrySet().stream()
                .filter(detail -> !WorkflowDefinitionUtils.ELEMENT_DETAIL.equals(detail.getKey()))
                .map(detail -> detail.getKey() + "=" + detail.getValue()))
            .collect(Collectors.toList());
        return parts.isEmpty() ? problem : problem + " (" + String.join(", ", parts) + ")";
    }
}
