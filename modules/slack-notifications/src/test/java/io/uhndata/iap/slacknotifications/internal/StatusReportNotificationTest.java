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
package io.uhndata.iap.slacknotifications.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.uhndata.iap.status.api.StatusReportManager;
import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StatusReportNotification}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class StatusReportNotificationTest
{
    /** Answers with fixed reports, and remembers what it was asked for. */
    private static final class Reports implements StatusReportManager
    {
        private final List<StatusReport> answer = new ArrayList<>();

        private boolean unprivileged;

        private StatusReport.Status level;

        private Set<String> tags;

        @Override
        public List<StatusReport> getReports(final boolean askedUnprivileged, final StatusReport.Status askedLevel,
            final Set<String> askedTags)
        {
            this.unprivileged = askedUnprivileged;
            this.level = askedLevel;
            this.tags = askedTags;
            return this.answer;
        }
    }

    private final Reports reports = new Reports();

    private StatusReportNotification notification;

    @BeforeEach
    void setUp() throws ReflectiveOperationException
    {
        this.notification = new StatusReportNotification();
        final Field manager = StatusReportNotification.class.getDeclaredField("statusReportManager");
        manager.setAccessible(true);
        manager.set(this.notification, this.reports);
    }

    @Test
    void isNamedAfterWhatItReports()
    {
        assertEquals("status", this.notification.getName());
    }

    @Test
    void rendersEachReportAsAnAttachment()
    {
        this.reports.answer.add(new StatusReport("Logged errors", StatusReport.Status.ERROR, "boom"));

        final List<JsonObject> messages = this.notification.prepareMessages(Map.of());

        assertEquals(1, messages.size());
        assertEquals("Logged errors", messages.get(0).getString("title"));
        assertEquals("boom", messages.get(0).getString("text"));
        assertEquals("900", messages.get(0).getString("color"));
    }

    @Test
    void aReportWithNothingMoreToSayThanItsStatusStillRenders()
    {
        // StatusReport.getText() is allowed to be null, and a null would break the JSON builder
        this.reports.answer.add(new StatusReport("Uptime", StatusReport.Status.INFO, null));

        assertEquals("", this.notification.prepareMessages(Map.of()).get(0).getString("text"));
    }

    @Test
    void everyStatusLevelGetsAColor()
    {
        this.reports.answer.add(new StatusReport("a", StatusReport.Status.SUCCESS, ""));
        this.reports.answer.add(new StatusReport("b", StatusReport.Status.WARNING, ""));
        this.reports.answer.add(new StatusReport("c", StatusReport.Status.ERROR, ""));
        this.reports.answer.add(new StatusReport("d", StatusReport.Status.INFO, ""));
        this.reports.answer.add(new StatusReport("e", StatusReport.Status.DEBUG, ""));

        final List<JsonObject> messages = this.notification.prepareMessages(Map.of());

        assertEquals(List.of("393", "BA0", "900", "999", "999"),
            messages.stream().map(message -> message.getString("color")).toList());
    }

    @Test
    void nothingToReportIsAnEmptyList()
    {
        assertTrue(this.notification.prepareMessages(Map.of()).isEmpty());
    }

    @Test
    void asksForEverythingFromInfoUpByDefault()
    {
        this.notification.prepareMessages(Map.of());

        assertEquals(StatusReport.Status.INFO, this.reports.level);
        assertEquals(Set.of(), this.reports.tags);
        assertEquals(false, this.reports.unprivileged);
    }

    @Test
    void honoursTheConfiguredLevelTagsAndAudience()
    {
        this.notification.prepareMessages(Map.of(
            StatusReportNotification.TARGET_LEVEL, "warning",
            // The blank entry in the middle is what a hand-edited configuration looks like
            StatusReportNotification.INCLUDE_TAGS, " problems , , errors ",
            StatusReportNotification.UNPRIVILEGED, "true"));

        assertEquals(StatusReport.Status.WARNING, this.reports.level);
        assertEquals(Set.of("problems", "errors"), this.reports.tags);
        assertEquals(true, this.reports.unprivileged);
    }

    @Test
    void anUnknownLevelFallsBackInsteadOfCostingTheWholeNotification()
    {
        this.notification.prepareMessages(Map.of(StatusReportNotification.TARGET_LEVEL, "CATASTROPHIC"));

        assertEquals(StatusReport.Status.INFO, this.reports.level);
    }
}
