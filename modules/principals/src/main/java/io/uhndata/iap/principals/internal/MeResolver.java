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
package io.uhndata.iap.principals.internal;

import java.util.List;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.spi.SpecialNameResolver;

/**
 * Answers {@code @me}: whoever is acting right now.
 *
 * <p>The context carries the actor as a user id so that this works wherever the question is asked: in a
 * request, where the session says who is asking, and inside the engine, where the work runs privileged for
 * somebody the session no longer names. With nobody acting the name stands for nobody, rather than for the
 * service user the machinery happens to run as.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SpecialNameResolver.class)
public class MeResolver implements SpecialNameResolver
{
    @Override
    public String getName()
    {
        return PrincipalService.ME;
    }

    @Override
    public List<String> resolve(final PrincipalContext context)
    {
        final String actor = context.actingUser();
        return actor == null || actor.isBlank() ? List.of() : List.of(actor);
    }
}
