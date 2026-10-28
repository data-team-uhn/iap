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
package io.uhndata.iap.documents.generation.spi;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Supplies one named value that document generation templates can use. Register one to make what a module knows about
 * a resource available to every template. For example, the submissions module supplies {@code submission}, so a
 * template can then include it as {@code {{ submission.title }}}.
 *
 * <p>The value may be a {@link java.util.Map} with {@code String} keys, an {@link Iterable} or array, a
 * {@link CharSequence}, a {@link Number}, a {@link Boolean}, an {@link Enum} (its name), or a
 * {@link java.util.Calendar}, {@link java.util.Date} or {@link java.time.temporal.TemporalAccessor} (as ISO-8601
 * text), nested as deeply as needed. Anything else fails the generation, as this does not use {@code toString()}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TemplateContextProvider
{
    /**
     * The name templates use for this provider's value.
     *
     * <p>A second provider with an overlapping name is ignored, with a warning.</p>
     *
     * @return a name such as {@code submission}, a valid Jinja identifier
     */
    @NotNull
    String getName();

    /**
     * The value templates see under {@link #getName()} for this resource.
     *
     * @param target what the document is about
     * @return the value, or {@code null} when this provider has nothing for this kind of resource; the name is then
     *         left out, and a template using it fails with the name it was missing
     */
    @Nullable
    Object getValue(@NotNull Resource target);
}
