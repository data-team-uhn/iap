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
package io.uhndata.iap.submissions.models;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A part of a submission that a model's runs are saved under, such as an {@link Answer} or a {@link Validation}.
 * Only the newest run counts; older ones are kept but not shown.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface Extractable
{
    /**
     * Every run, in the order they ran.
     *
     * @return the runs; empty if the model was never asked
     */
    @NotNull
    List<Extraction> getExtractions();

    /**
     * Returns the newest run, the one that counts.
     *
     * @return the newest run, or {@code null} if the model was never asked
     */
    @Nullable
    default Extraction getLatestExtraction()
    {
        final List<Extraction> extractions = getExtractions();
        return extractions.isEmpty() ? null : extractions.get(extractions.size() - 1);
    }
}
