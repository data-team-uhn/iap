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

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.spi.SpecialNameResolver;

/**
 * Answers {@code @creator}: whoever raised the resource in question.
 *
 * <p>Asked of the content model, not of {@code jcr:createdBy}. That property names the engine's service user
 * for everything the engine writes, so {@link Content#getCreatedBy()} prefers the human the engine recorded and
 * falls back to it. A resource that is not content may still carry an explicit record, so its own
 * {@code createdBy} is the last resort.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SpecialNameResolver.class)
public class CreatorResolver implements SpecialNameResolver
{
    @Override
    public String getName()
    {
        return PrincipalService.CREATOR;
    }

    @Override
    public List<String> resolve(final PrincipalContext context)
    {
        final Resource subject = context.subject();
        if (subject == null) {
            return List.of();
        }
        final Content content = subject.adaptTo(Content.class);
        String creator = content == null ? null : content.getCreatedBy();
        if (creator == null) {
            // Adaptation is not a type check, so a model with no answer falls through rather than ending it
            creator = subject.getValueMap().get("createdBy", String.class);
        }
        // A resource nothing raised, a homepage say, is nobody's
        return creator == null || creator.isBlank() ? List.of() : List.of(creator);
    }
}
