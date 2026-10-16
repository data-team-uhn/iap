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
package io.uhndata.iap.conditions.spi;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Lists what an operand of one source can name, for a condition on a given piece of content: the questions of a
 * schema version, for instance. The module that knows those things provides it, which need not be the one evaluating
 * the source. The catalogue then tells the condition builder where to ask for them.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface CandidateProvider
{
    /**
     * The operand source this lists candidates for.
     *
     * @return a source name, e.g. {@code answer}
     */
    @NotNull
    String getSource();

    /**
     * What an operand of the source can name, for a condition on the given content.
     *
     * @param context the content the condition is on, e.g. a part of a schema version, read with the requester's
     *            own access
     * @return the candidates, in the order the builder offers them, possibly none
     */
    @NotNull
    List<Candidate> getCandidates(@NotNull Resource context);
}
