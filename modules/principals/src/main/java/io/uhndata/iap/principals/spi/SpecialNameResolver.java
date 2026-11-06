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
 * Answers one special name, such as {@code @creator}. A bundle adds a special name by registering a resolver as an
 * OSGi service.
 *
 * <p>A resolver says who the name stands for in a situation. It does not expand groups or check membership. It may
 * answer with several principals, or with none when the situation identifies nobody. None is a normal answer, not
 * a failure.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface SpecialNameResolver
{
    /**
     * The special name this resolver answers. When two resolvers answer the same name, which one is asked is not
     * defined.
     *
     * @return the name, starting with {@code @}, such as {@code @creator}
     */
    @NotNull
    String getName();

    /**
     * Who the name stands for in the given situation.
     *
     * @param context what the name is asked about
     * @return the principal names; empty if the name stands for nobody there
     */
    @NotNull
    List<String> resolve(@NotNull PrincipalContext context);
}
