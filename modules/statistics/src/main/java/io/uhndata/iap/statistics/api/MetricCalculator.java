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
package io.uhndata.iap.statistics.api;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.statistics.models.Metric;

/**
 * Works out what the metrics currently say.
 *
 * <p>
 * <strong>Nothing is counted as it happens.</strong> Every number here is worked out from the record of
 * what happened, which is the property that makes a metric nobody thought of last year answerable about
 * last year — and makes a number for a period that has closed the same number whenever it is asked for.
 * A counter kept as things happen can do neither.
 * </p>
 *
 * <p>
 * This is the expensive half, and deliberately not the half anybody waits on: it reads the whole history,
 * so it is called on a schedule and the answer is kept in the repository for readers. Calling it is
 * therefore never wrong, only slow.
 * </p>
 *
 * <p>
 * The reading is done as a service user, because a metric is deliberately an aggregate over records its
 * reader may not see one at a time: publishing "the median was 32 days" is not the same as publishing the
 * list it came from. Who may see which number is decided elsewhere, by each metric's access level; this
 * service works out every one of them.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface MetricCalculator
{
    /**
     * Works out what one metric says.
     *
     * @param metric the definition to compute
     * @return what it says, or {@code null} if the definition does not say enough to be computed
     */
    @Nullable
    MetricValue compute(@NotNull Metric metric);

    /**
     * Works out what every defined metric says, in the order they are meant to be shown: by category,
     * then by their declared order, then by name.
     *
     * @return what they say, skipping any definition that cannot be computed
     */
    @NotNull
    List<MetricValue> computeAll();
}
