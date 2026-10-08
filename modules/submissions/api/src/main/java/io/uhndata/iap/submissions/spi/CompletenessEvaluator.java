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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Judges one kind of part of a submission: whether what its author is asked for has been supplied.
 *
 * <p>Every item the author is asked for has a part of its own, empty until they fill it in, so an item nobody has
 * started still has a node to mark. An evaluator therefore also keeps its parts in line with what is asked now. It
 * creates the part for an item that has started to apply, and removes an empty one for an item that has stopped
 * applying.</p>
 *
 * <p>Implementations are OSGi services. The {@code markCompleteness} service task asks every one of them after each
 * change to a submission, and tags each part they report as {@code incomplete}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface CompletenessEvaluator
{
    /**
     * Brings this evaluator's parts of a submission in line with what the submission is asked now, and names the ones
     * still incomplete.
     *
     * @param submission the submission, through the session that is writing it
     * @return the parts lacking something their author has to supply, empty when nothing is missing
     * @throws PersistenceException when a part cannot be created or removed
     */
    @NotNull
    List<Resource> evaluate(@NotNull Resource submission) throws PersistenceException;
}
