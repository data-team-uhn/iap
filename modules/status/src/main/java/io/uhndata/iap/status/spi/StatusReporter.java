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
package io.uhndata.iap.status.spi;

import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Contributes one report on the state of the instance whenever somebody reads the status. Register an
 * implementation as an OSGi service.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface StatusReporter
{
    /**
     * The name of this reporter. If {@link #report} throws an exception, the error report in its place has this name.
     *
     * @return a short title, such as {@code Health Check}
     */
    @NotNull
    String getName();

    /**
     * The tags a reader selects this reporter by. {@code problems} marks a reporter about something that needs
     * attention.
     *
     * @return the tags, such as {@code problems} and {@code healthcheck}; empty if the reporter only runs when the
     *         reader selects no tags
     */
    @NotNull
    Set<String> getTags();

    /**
     * Describes the current state of what this reporter covers.
     *
     * @param unprivileged {@code true} if a reader who must not see confidential details may read the report, so it
     *            must leave out whatever quotes content, such as paths, user names, messages and stack traces;
     *            {@code false} if only a privileged reader will
     * @return the report; {@code null} if there is nothing to report
     */
    @Nullable
    StatusReport report(boolean unprivileged);
}
