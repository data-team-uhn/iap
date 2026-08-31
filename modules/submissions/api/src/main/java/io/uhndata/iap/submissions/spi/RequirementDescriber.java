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
 * heard of. A describer adds what that kind needs in order to be drawn, and the form never learns the type.</p>
 *
 * <p><strong>Add only your own keys.</strong> The form writes the common fields first: the requirement's name, path,
 * type, label and description. Every describer that claims a requirement then adds to the same object, so a bundle
 * can extend a kind it did not declare. When two describers write the same key, registration order decides.</p>
 *
 * <p>A describer is only asked about a requirement that applies to the submission.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface RequirementDescriber
{
    /**
     * Whether this describer has anything to say about a requirement. Asked for every requirement on every form, so
     * it should be a type test and nothing more.
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
     * @param json the requirement's object in the form's JSON, already holding the common fields; added to in place
     */
    void describe(@NotNull Requirement requirement, @NotNull Submission submission, @NotNull JsonObjectBuilder json);
}
