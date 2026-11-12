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
package io.uhndata.iap.status.api;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.status.spi.StatusReport;

/**
 * Gathers the reports on the state of the instance, at most one from each registered
 * {@link io.uhndata.iap.status.spi.StatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface StatusReportManager
{
    /**
     * Asks every reporter for its report, and keeps those at {@code INFO} level or above.
     *
     * @param unprivileged {@code true} to ask for the reports without confidential details; {@code false} for the
     *            privileged reports
     * @return the reports; empty if no reporter has anything to say at {@code INFO} level or above
     */
    @NotNull
    default List<StatusReport> getReports(final boolean unprivileged)
    {
        return getReports(unprivileged, StatusReport.Status.INFO, null);
    }

    /**
     * Asks the reporters that have one of the given tags for their reports, and keeps those at the given level or
     * above.
     *
     * @param unprivileged {@code true} to ask each reporter to leave confidential details out, for a reader who must
     *            not see them; {@code false} for a privileged reader
     * @param level the lowest level to keep, such as {@code WARNING} for the warnings and the errors
     * @param tags the tags of the reporters to ask, such as {@code problems}; {@code null} or empty to ask every
     *            reporter
     * @return the reports; empty if no reporter asked has anything to say at that level or above
     */
    @NotNull
    List<StatusReport> getReports(boolean unprivileged, @NotNull StatusReport.Status level,
        @Nullable Set<String> tags);
}
