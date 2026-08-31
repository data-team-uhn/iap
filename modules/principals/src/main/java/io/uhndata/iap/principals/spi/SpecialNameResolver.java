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
package io.uhndata.iap.principals.spi;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.principals.api.PrincipalContext;

/**
 * Answers one special name. Any bundle may register one, which is how the vocabulary grows: a comments module can
 * teach the platform {@code @commentAuthor} without anything else learning what a comment is.
 *
 * <p>
 * A resolver answers <em>who a name stands for in a situation</em>, nothing more: no group expansion, no
 * membership checks, no judgement about whether the answer should be allowed anything. It may answer with several
 * principals, and it answers with nobody when the situation does not identify anyone — a {@code @creator} of a
 * resource nothing raised, a {@code @me} with nobody acting. Nobody is a normal answer, not a failure.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SpecialNameResolver
{
    /**
     * The special name this resolver answers, including the {@code @}, e.g. {@code @creator}. Registering a
     * second resolver for the same name is a deployment mistake; which one answers is not defined.
     *
     * @return the special name
     */
    @NotNull
    String getName();

    /**
     * Who the name stands for in the given situation.
     *
     * @param context what the name is being asked about
     * @return the principals it stands for there, empty when it stands for nobody
     */
    @NotNull
    List<String> resolve(@NotNull PrincipalContext context);
}
