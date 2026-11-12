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

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;
import io.uhndata.iap.utils.DateUtils;

/**
 * Refuses to purge an archive entry younger than the configured number of days. A deletion stays reversible for at
 * least that long. Only the entry is judged, by its {@code jcr:created}: the content archived in it keeps its own,
 * older {@code jcr:created}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
@Designate(ocd = ArchiveRetentionConfiguration.class)
public class ArchiveRetentionVeto implements DeletionVeto
{
    /** The property holding when an archive entry was created, which is when its contents were archived. */
    static final String ARCHIVED_AT_PROPERTY = "jcr:created";

    private volatile int minimumRetentionDays;

    @Activate
    protected void activate(final ArchiveRetentionConfiguration config)
    {
        this.minimumRetentionDays = config.minimumRetentionDays();
    }

    @Override
    public String getName()
    {
        return "archive-retention";
    }

    @Override
    public String veto(final Node node, final DeletionMode mode, final Session requester)
        throws RepositoryException
    {
        if (this.minimumRetentionDays <= 0 || !node.isNodeType(DeletionService.ENTRY_NODETYPE)) {
            return null;
        }
        if (!node.hasProperty(ARCHIVED_AT_PROPERTY)) {
            // mix:created autocreates it, so this entry was not made by the archive. Its age is unknown.
            return "The age of this archive entry cannot be determined";
        }
        final ZonedDateTime destroyableFrom = this.destroyableFrom(node.getProperty(ARCHIVED_AT_PROPERTY).getDate());
        if (destroyableFrom.toInstant().isAfter(Instant.now())) {
            // An instant rather than a count of days left, which would need a plural form
            return "This archive entry cannot be destroyed before " + DateUtils.toString(destroyableFrom);
        }
        return null;
    }

    /**
     * The instant from which an entry archived at the given time may be purged, in the zone the entry was recorded in.
     */
    private ZonedDateTime destroyableFrom(final Calendar deletedAt)
    {
        return deletedAt.toInstant()
            .atZone(deletedAt.getTimeZone().toZoneId())
            .plusDays(this.minimumRetentionDays);
    }
}
