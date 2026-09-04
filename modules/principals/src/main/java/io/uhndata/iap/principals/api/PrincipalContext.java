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
 * What a special name is being asked about: the resource the question concerns, and whoever is asking.
 *
 * <p>
 * A special name is only answerable in a situation. {@code @creator} means whoever raised <em>this</em> resource,
 * {@code @me} means whoever is acting <em>now</em>, and a name added later may need either or both. Both halves
 * are optional, because a caller does not always have both: a scheduled job acts for nobody, and a query about
 * the current user is about nothing in particular. A resolver missing the half it needs answers with nobody
 * rather than failing, since a definition naming {@code @creator} on a resource nothing raised is a fact about
 * that resource, not an error in the definition.
 * </p>
 *
 * @param subject the resource the names are about, or {@code null} when the question is not about one
 * @param actingUser who is acting, as their repository user id, or {@code null} when nobody is — a timer, say
 * @version $Id$
 * @since 0.1.0
 */
public record PrincipalContext(@Nullable Resource subject, @Nullable String actingUser)
{
    /**
     * A context about a resource, with nobody acting.
     *
     * @param subject the resource the names are about
     * @return a context
     */
    @NotNull
    public static PrincipalContext about(@Nullable final Resource subject)
    {
        return new PrincipalContext(subject, null);
    }

    /**
     * A context about nothing in particular, with somebody acting.
     *
     * @param actingUser who is acting, as their repository user id
     * @return a context
     */
    @NotNull
    public static PrincipalContext actedBy(@Nullable final String actingUser)
    {
        return new PrincipalContext(null, actingUser);
    }
}
