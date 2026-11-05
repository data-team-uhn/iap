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
import io.uhndata.iap.utils.PrefixTree;

/**
 * One archive entry as JSON: when it was archived, who deleted what, and where each archived item came from.
 *
 * <p>
 * It carries two addresses. {@code path} is where the entry is stored, inside the prefix tree. {@code shortPath} is
 * {@code /Archive/by-id/<name>}, the address to show and link to.
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
     * @param entry the {@code del:ArchiveEntry} resource to describe
     * @return a builder holding {@code path}, {@code shortPath}, {@code requestedPath}, {@code deletedBy},
     *         {@code created} if the entry has a {@code jcr:created}, {@code originalPaths} and {@code itemCount}
     */
    static JsonObjectBuilder of(final Resource entry)
    {
        final ValueMap properties = entry.getValueMap();
        final JsonObjectBuilder row = Json.createObjectBuilder()
            .add("path", entry.getPath())
            // Built from PrefixTree.ADDRESS_SEGMENT, where the provider mounted beside the archive answers
            .add("shortPath", DeletionService.ARCHIVE_PATH + "/" + PrefixTree.ADDRESS_SEGMENT
                + "/" + entry.getName())
            .add(DeletionService.REQUESTED_PATH_PROPERTY, properties.get(DeletionService.REQUESTED_PATH_PROPERTY, ""))
            .add(DeletionService.DELETED_BY_PROPERTY, properties.get(DeletionService.DELETED_BY_PROPERTY, ""));
        final Calendar created = properties.get("jcr:created", Calendar.class);
        if (created != null) {
            row.add("created", DateUtils.toString(created));
        }
        // Only the children that record an original path are archived items
        final List<String> originalPaths = StreamSupport.stream(entry.getChildren().spliterator(), false)
            .map(item -> item.getValueMap().get(DeletionService.ORIGINAL_PATH_PROPERTY, String.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        final JsonArrayBuilder paths = Json.createArrayBuilder();
        originalPaths.forEach(paths::add);
        row.add("originalPaths", paths);
        row.add("itemCount", originalPaths.size());
        return row;
    }
}
