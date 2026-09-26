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
package io.uhndata.iap.schemas.models;

import org.jetbrains.annotations.NotNull;

/**
 * One answer a {@link Question} offers, as the form, the model and the matching all see it: the stored value,
 * the label a person reads, and an optional description of when it applies.
 *
 * <p>Declared {@link AnswerOption} children and items loaded from {@link Question#getOptionsFrom()} both become
 * this, so the three places that need the list cannot drift.</p>
 *
 * @param value what an answer picking this option stores, and what a condition compares against
 * @param label what a person reads
 * @param description when this option applies; empty when there is nothing more to say than the label
 * @version $Id$
 * @since 0.1.0
 */
public record OfferedOption(@NotNull String value, @NotNull String label, @NotNull String description)
{
}
