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
package io.uhndata.iap.emailcatcher.internal;

import java.lang.reflect.Field;
import java.util.Set;

import org.apache.sling.commons.messaging.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.uhndata.iap.status.spi.StatusReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CaughtMailStatusReporter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class CaughtMailStatusReporterTest
{
    private CaughtMailStatusReporter reporter;

    @BeforeEach
    void setUp()
    {
        this.reporter = new CaughtMailStatusReporter();
    }

    /** Binds the catcher's mail service, which is what "the catcher is on" means to this reporter. */
    private void switchedOn() throws Exception
    {
        final Field reference = CaughtMailStatusReporter.class.getDeclaredField("catcher");
        reference.setAccessible(true);
        reference.set(this.reporter, Mockito.mock(MailService.class));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals("Email Catcher", this.reporter.getName());
    }

    @Test
    void isTaggedAsSomethingToLookAt()
    {
        assertEquals(Set.of("problems", "email"), this.reporter.getTags());
    }

    /**
     * The whole point: the catcher is on deliberately, but nothing the platform sends is arriving, and that is
     * not something an administrator should have to infer from an empty inbox.
     */
    @Test
    void warnsWhileMailIsBeingCaught() throws Exception
    {
        this.switchedOn();

        final StatusReport report = this.reporter.report(false);

        assertNotNull(report);
        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        assertTrue(report.getText().contains("not delivered"));
        assertTrue(report.getText().contains(CaughtMailService.CAUGHT_MAIL_PATH));
    }

    /** Where the messages went is an internal path, and an unprivileged report has no business naming it. */
    @Test
    void keepsTheFolderOutOfAnUnprivilegedWarning() throws Exception
    {
        this.switchedOn();

        final StatusReport report = this.reporter.report(true);

        assertNotNull(report);
        assertEquals(StatusReport.Status.WARNING, report.getStatus());
        // Still says that mail is not being delivered, which is the part that matters either way
        assertTrue(report.getText().contains("not delivered"));
        assertFalse(report.getText().contains(CaughtMailService.CAUGHT_MAIL_PATH));
    }

    /**
     * Debug rather than nothing: an ordinary report is not padded with a line about a development facility being
     * off, but somebody asking where the mail went gets an answer instead of a silence that reads the same as the
     * bundle not being installed.
     */
    @Test
    void saysOnlyInDebugThatTheCatcherIsOff()
    {
        final StatusReport report = this.reporter.report(false);

        assertNotNull(report);
        assertEquals(StatusReport.Status.DEBUG, report.getStatus());
        assertTrue(report.getText().contains("delivered normally"));
    }

    @Test
    void saysTheSameWhenOffAndUnprivileged()
    {
        assertEquals(StatusReport.Status.DEBUG, this.reporter.report(true).getStatus());
    }
}
