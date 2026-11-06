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
package io.uhndata.iap.principals.api;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a special name is asked about: the resource the question concerns, and the user who is acting.
 *
 * <p>{@code @creator} needs the resource, and {@code @me} needs the user. Either half may be missing. A scheduled
 * job acts for nobody, and a question about the current user concerns no resource. A resolver missing the half it
 * needs answers with nobody.</p>
 *
 * @param subject the resource the names are about; {@code null} if they are about none
 * @param actingUser the user id of whoever is acting; {@code null} if nobody is
 * @version $Id$
 * @since 0.1.0
 */
public record PrincipalContext(@Nullable Resource subject, @Nullable String actingUser)
{
    /**
     * A context about a resource, with nobody acting.
     *
     * @param subject the resource the names are about; {@code null} if they are about none
     * @return a context with no acting user
     */
    @NotNull
    public static PrincipalContext about(@Nullable final Resource subject)
    {
        return new PrincipalContext(subject, null);
    }

    /**
     * A context about no resource, with somebody acting.
     *
     * @param actingUser the user id of whoever is acting; {@code null} if nobody is
     * @return a context with no subject
     */
    @NotNull
    public static PrincipalContext actedBy(@Nullable final String actingUser)
    {
        return new PrincipalContext(null, actingUser);
    }
}
