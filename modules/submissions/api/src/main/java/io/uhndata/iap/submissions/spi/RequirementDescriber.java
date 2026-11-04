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
package io.uhndata.iap.submissions.spi;

import jakarta.json.JsonObjectBuilder;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Submission;

/**
 * What one kind of requirement adds to a submission's form. Any bundle may register one, so a kind of requirement
 * can live outside the module that renders the form.
 *
 * <p>The form names every requirement by its resource type, so a reader can key on a kind the platform has never
 * heard of. A describer adds the fields its kind needs in order to be drawn. The servlet serving the form has no code
 * for any kind.</p>
 *
 * <p>The form writes the common fields first: the requirement's name, path, type, label and description. Every
 * describer that claims a requirement then adds its own keys, so a bundle can extend a kind it did not declare. A
 * describer may also replace a common field, such as a description its kind words differently. When two describers
 * write the same key, the one with the higher {@code service.ranking} is applied last, so its value stays. At equal
 * ranking, the one registered first stays.</p>
 *
 * <p>A describer is only asked about a requirement that applies to the submission. One that throws is skipped and
 * recorded, and the requirement keeps the fields written before it.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface RequirementDescriber
{
    /**
     * Whether this describer has anything to say about a requirement. Asked for every requirement that applies, on
     * every form read, so keep it to a type test.
     *
     * @param requirement a requirement of the submission's schema version that applies to it
     * @return {@code true} to be asked to describe it
     */
    boolean handles(@NotNull Requirement requirement);

    /**
     * Adds what this kind of requirement needs in order to be drawn.
     *
     * @param requirement a requirement {@link #handles} has claimed
     * @param submission the submission whose form is being served, which holds any answer to the requirement
     * @param json an empty object for this describer's keys, merged into the requirement's object once this returns
     */
    void describe(@NotNull Requirement requirement, @NotNull Submission submission, @NotNull JsonObjectBuilder json);
}
