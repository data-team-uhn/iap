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
 * A rule about what a submission's answers may say, consulted while a save is carried out. Any bundle may register
 * one, and the save is refused as soon as one objects.
 *
 * <p>A validator is handed the submission as the save would leave it: the answers are already written, in a session
 * not yet committed. The whole save is one commit, so a refusal discards them.</p>
 *
 * <p><strong>Judge only what is there.</strong> Answers are saved one at a time, so a submission is half-answered for
 * most of its life, and that is not an error. A validator that cannot tell yet, because the answers it depends on
 * are missing, accepts. Anything else would make saving as you go impossible.</p>
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
