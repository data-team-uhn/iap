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
package io.uhndata.iap.status.internal;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports when the instance started, as an {@code INFO} report. The text is an ISO 8601 timestamp with the server's
 * offset, taken when the bundle starts and loads this class. Every reader gets the same report.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class UptimeStatusReporter implements StatusReporter
{
    private static final String REPORT_TITLE = "System Started";

    private static final ZonedDateTime STARTED_AT = ZonedDateTime.now();

    @Override
    public String getName()
    {
        return REPORT_TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        return new StatusReport(REPORT_TITLE, StatusReport.Status.INFO,
            STARTED_AT.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("status", "systemStarted");
    }
}
