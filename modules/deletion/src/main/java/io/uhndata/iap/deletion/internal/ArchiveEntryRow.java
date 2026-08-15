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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.utils.DateUtils;

/**
 * One archive entry, as the archive viewer's table displays it: when it was archived, who asked, what they asked to
 * delete, and where everything the operation dragged along came from.
 *
 * <p>
 * Two addresses are included. {@code path} is where the entry is stored and what the restore and purge endpoints
 * act on; {@code shortPath} is the same entry without the prefix tree, which is what a reader should be shown and
 * linked to. A listing is the only place a client can learn either, since no client can construct a bucket path.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ArchiveEntryRow
{
    private ArchiveEntryRow()
    {
        // Utility class
    }

    /**
     * Describes one archive entry.
     *
     * @param entry the {@code iap:ArchiveEntry} resource to describe
     * @return the entry as a JSON object
     */
    static JsonObjectBuilder of(final Resource entry)
    {
        final ValueMap properties = entry.getValueMap();
        final JsonObjectBuilder row = Json.createObjectBuilder()
            .add("path", entry.getPath())
            // Where a reader can address it, which is the same entry without the storage layout in the way.
            // Emitted rather than left to the client so that the prefix tree stays a server-side concern.
            .add("shortPath", DeletionService.ARCHIVE_PATH + "/" + entry.getName())
            .add("requestedPath", properties.get("requestedPath", ""))
            .add("deletedBy", properties.get("deletedBy", ""));
        final Calendar created = properties.get("jcr:created", Calendar.class);
        if (created != null) {
            row.add("created", DateUtils.toString(created));
        }
        // Only the wrappers that record where they came from: a child without one says nothing a reader could act
        // on, and the entry's node type allows other children for extensibility.
        final List<String> originalPaths = StreamSupport.stream(entry.getChildren().spliterator(), false)
            .map(item -> item.getValueMap().get("originalPath", String.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        final JsonArrayBuilder paths = Json.createArrayBuilder();
        originalPaths.forEach(paths::add);
        row.add("originalPaths", paths);
        row.add("itemCount", originalPaths.size());
        return row;
    }
}
