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
package io.uhndata.iap.deletion.internal;

import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Session;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.utils.DateUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ArchiveRetentionVeto}.
 *
 * @version $Id$
 */
class ArchiveRetentionVetoTest
{
    private static final int RETENTION_DAYS = 7;

    private final ArchiveRetentionVeto veto = new ArchiveRetentionVeto();

    // This guard looks only at the node's age, so the requester it is handed never matters
    private final Session requester = mock(Session.class);

    @Test
    void hasAStableName()
    {
        assertEquals("archive-retention", this.veto.getName());
    }

    @Test
    void allowsEverythingWhenNoRetentionPeriodIsConfigured() throws Exception
    {
        this.configure(0);
        final Node node = mock(Node.class);

        assertNull(this.veto.veto(node, DeletionMode.PURGE, this.requester));
        // A disabled policy reads nothing off the node
        verifyNoInteractions(node);
    }

    @Test
    void allowsEverythingWhenTheRetentionPeriodIsNegative() throws Exception
    {
        this.configure(-1);

        assertNull(this.veto.veto(mock(Node.class), DeletionMode.PURGE, this.requester));
    }

    @Test
    void ignoresEverythingThatIsNotAnArchiveEntry() throws Exception
    {
        this.configure(RETENTION_DAYS);
        final Node node = mock(Node.class);
        when(node.isNodeType(DeletionService.ENTRY_NODETYPE)).thenReturn(false);

        assertNull(this.veto.veto(node, DeletionMode.PURGE, this.requester));
        verify(node, never()).hasProperty(anyString());
    }

    @Test
    void vetoesAnEntryYoungerThanTheRetentionPeriod() throws Exception
    {
        this.configure(RETENTION_DAYS);
        final Calendar archivedAt = daysAgo(1);
        final Node entry = this.entryArchivedAt(archivedAt);

        final String reason = this.veto.veto(entry, DeletionMode.PURGE, this.requester);

        // The message names the instant, in DateUtils' format
        assertEquals("This archive entry cannot be destroyed before "
            + DateUtils.toString(archivedAt.toInstant().atZone(archivedAt.getTimeZone().toZoneId())
                .plusDays(RETENTION_DAYS)),
            reason);
    }

    @Test
    void allowsAnEntryOlderThanTheRetentionPeriod() throws Exception
    {
        this.configure(RETENTION_DAYS);

        assertNull(this.veto.veto(this.entryArchivedAt(daysAgo(RETENTION_DAYS + 1)), DeletionMode.PURGE,
            this.requester));
    }

    @Test
    void allowsAnEntryThatHasJustReachedTheRetentionPeriod() throws Exception
    {
        this.configure(RETENTION_DAYS);

        assertNull(this.veto.veto(this.entryArchivedAt(daysAgo(RETENTION_DAYS)), DeletionMode.PURGE, this.requester));
    }

    @Test
    void vetoesAnEntryOfUnknownAge() throws Exception
    {
        this.configure(RETENTION_DAYS);
        final Node entry = mock(Node.class);
        when(entry.isNodeType(DeletionService.ENTRY_NODETYPE)).thenReturn(true);
        when(entry.hasProperty(ArchiveRetentionVeto.ARCHIVED_AT_PROPERTY)).thenReturn(false);

        assertNotNull(this.veto.veto(entry, DeletionMode.PURGE, this.requester));
    }

    // The rule follows the node type, not the mode
    @Test
    void protectsAnEntryWhicheverModeIsPresented() throws Exception
    {
        this.configure(RETENTION_DAYS);
        final Node entry = this.entryArchivedAt(daysAgo(1));

        assertNotNull(this.veto.veto(entry, DeletionMode.PURGE, this.requester));
        assertNotNull(this.veto.veto(entry, DeletionMode.PERMANENT, this.requester));
        assertNotNull(this.veto.veto(entry, DeletionMode.ARCHIVE, this.requester));
    }

    private static Calendar daysAgo(final int days)
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        return calendar;
    }

    private void configure(final int days)
    {
        final ArchiveRetentionConfiguration config = mock(ArchiveRetentionConfiguration.class);
        when(config.minimumRetentionDays()).thenReturn(days);
        this.veto.activate(config);
    }

    private Node entryArchivedAt(final Calendar archivedAt) throws Exception
    {
        final Property created = mock(Property.class);
        when(created.getDate()).thenReturn(archivedAt);
        final Node entry = mock(Node.class);
        when(entry.isNodeType(DeletionService.ENTRY_NODETYPE)).thenReturn(true);
        when(entry.hasProperty(ArchiveRetentionVeto.ARCHIVED_AT_PROPERTY)).thenReturn(true);
        when(entry.getProperty(ArchiveRetentionVeto.ARCHIVED_AT_PROPERTY)).thenReturn(created);
        return entry;
    }
}
