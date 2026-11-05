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
package io.uhndata.iap.submissions.internal.transfer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.Binary;

import jakarta.json.JsonObject;

/**
 * A submission ready to travel: its manifest, and the binaries the manifest names, by part name. The binaries are
 * read from the repository as they are sent, so they are only good while the session that exported them is open.
 *
 * @param manifest the submission's nodes and properties, as {@link TransferFormat} describes
 * @param binaries the binaries, in the order the manifest first names them
 * @version $Id$
 * @since 0.1.0
 */
public record SubmissionExport(JsonObject manifest, Map<String, Binary> binaries)
{
    /**
     * Keeps its own copy of the binaries, in their order.
     *
     * @param manifest the submission's nodes and properties
     * @param binaries the binaries, by part name
     */
    public SubmissionExport
    {
        binaries = Collections.unmodifiableMap(new LinkedHashMap<>(binaries));
    }
}
