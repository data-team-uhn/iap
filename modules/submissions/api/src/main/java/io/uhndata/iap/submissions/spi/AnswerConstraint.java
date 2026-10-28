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

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.schemas.models.Question;

/**
 * A constraint a question may carry on the values its answer holds. Any bundle may register one, and a save is
 * refused with the first refusal any of them gives.
 *
 * <p>They are consulted in ascending order of their {@code service.ranking}, unranked ones counting as 0, so that a
 * submitter hears about the most basic fault first: a value that is not a number before one that is out of range.
 * The constraints shipped with submissions leave gaps between their rankings for others to fit in.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface AnswerConstraint
{
    /**
     * Judges the values one question's answer holds.
     *
     * @param question a question the submitter is shown
     * @param values the values its answer holds, possibly none
     * @return a reason to refuse, or empty when the question carries no such constraint or the values fit it
     */
    @NotNull
    Optional<String> refusal(@NotNull Question question, @NotNull List<String> values);
}
