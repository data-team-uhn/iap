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
 * What one kind of requirement needs said about it on a submission's form. Any bundle may register one, which is
 * what lets a kind of requirement live outside the module that renders the form.
 *
 * <p>
 * The form already names every requirement by its own resource type, so a reader can key on a kind the platform has
 * never heard of. This is the other half of that: the projection can carry what such a kind needs in order to be
 * drawn, without the form having to learn the type.
 * </p>
 *
 * <p>
 * <strong>Add only your own keys.</strong> The common fields — the requirement's name, type, label and description —
 * are written before any describer is consulted, and every describer that claims a requirement contributes to the
 * same object. That is deliberate, so that one kind's projection can be extended by a bundle that did not declare
 * it; the cost is that a key two describers both write is decided by registration order, which is nobody's
 * intention. Add keys you own.
 * </p>
 *
 * <p>
 * Whether a requirement applies to this submission at all is settled before a describer is asked, so what arrives
 * here is always something the submitter is genuinely being asked for.
 * </p>
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
     * @param requirement the requirement being projected
     * @return {@code true} to be asked to describe it
     */
    boolean handles(@NotNull Requirement requirement);

    /**
     * Add what this kind of requirement needs in order to be drawn.
     *
     * @param requirement the requirement being described, which {@link #handles} has already claimed
     * @param submission the submission it is being resolved against, and where any answer to it already lives
     * @param json the requirement's JSON, added to in place
     */
    void describe(@NotNull Requirement requirement, @NotNull Submission submission, @NotNull JsonObjectBuilder json);
}
