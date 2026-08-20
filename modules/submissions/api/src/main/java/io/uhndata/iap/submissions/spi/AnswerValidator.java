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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.submissions.models.Submission;

/**
 * A rule about what a submission's answers may say, consulted while a save is being carried out. Any bundle may
 * register one; the save is refused as soon as any of them objects.
 *
 * <p>
 * A validator is handed the submission <em>as the save would leave it</em>: the answers have already been written,
 * in a session that has not been committed. That is what lets a rule read the resulting state directly instead of
 * working out what the payload would change, and refusing still leaves nothing behind — the whole save runs as one
 * commit, so an objection discards the answers along with everything else the run did.
 * </p>
 *
 * <p>
 * <strong>Judge only what is there.</strong> Answers are saved one at a time, as each is finished, so a submission
 * is half-answered for most of its life and being half-answered is not an error. A validator that cannot yet tell —
 * because the answers it depends on have not been given — should accept, and object only once it genuinely can.
 * Anything else would make saving as you go impossible.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface AnswerValidator
{
    /**
     * Decide whether the answers, as they now stand, may be saved.
     *
     * @param submission the submission being answered, already carrying the answers this save writes
     * @param actor the user whose save this is
     * @return a reason to refuse, shown to the submitter on the answer they just gave, or {@code null} to accept
     */
    @Nullable
    String validate(@NotNull Submission submission, @NotNull String actor);
}
