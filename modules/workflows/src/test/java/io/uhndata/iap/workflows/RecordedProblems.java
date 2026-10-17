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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

/**
 * Collects what a parse reported, for as long as it is open. A parse survives everything it complains about, so
 * the complaint is the only evidence that a mis-authored diagram was noticed at all. That makes it worth asserting
 * on alongside the nodes the parse produced.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class RecordedProblems implements ErrorLoggerService, AutoCloseable
{
    private final List<Problem> recorded = new ArrayList<>();

    /**
     * One thing a parse complained about.
     *
     * @param problem what was wrong, as the reporting code phrased it
     * @param subject the path the report was about, or {@code null} when it named none
     * @param details everything the report named, keyed the way the reporter keyed it
     * @since 0.1.0
     */
    public record Problem(String problem, String subject, Map<String, String> details)
    {
    }

    /** Takes over the error logging facade, so that the parse's reports arrive here. */
    public RecordedProblems()
    {
        ErrorLogger.setService(this);
    }

    @Override
    public void close()
    {
        ErrorLogger.unsetService(this);
    }

    @Override
    public void logError(final Throwable error)
    {
        this.recorded.add(new Problem(String.valueOf(error), null, Map.of()));
    }

    @Override
    public void logError(final Throwable error, final ErrorContext context)
    {
        this.recorded.add(new Problem(String.valueOf(error), context.getSubject(), context.getDetails()));
    }

    @Override
    public void logProblem(final String problem, final ErrorContext context)
    {
        this.recorded.add(new Problem(problem, context.getSubject(), context.getDetails()));
    }

    @Override
    public long getDroppedCount()
    {
        return 0;
    }

    /**
     * Everything reported so far, in the order it was reported.
     *
     * @return the recorded problems
     */
    public List<Problem> all()
    {
        return this.recorded;
    }

    /**
     * What each problem reported so far was, in the order it was reported.
     *
     * @return the problems' descriptions
     */
    public List<String> names()
    {
        return this.recorded.stream().map(Problem::problem).toList();
    }

    /**
     * The problems reported, rendered for a failure message that says what actually happened.
     *
     * @return one line per recorded problem
     */
    public List<String> messages()
    {
        return this.recorded.stream().map(problem -> problem.problem() + " " + problem.details()).toList();
    }
}
