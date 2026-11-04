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
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;
import io.uhndata.iap.principals.spi.SpecialNameResolver;

/**
 * Answers {@code @entityCreator}: whoever raised the entity the resource in question belongs to. A notification
 * about a review can then tell the author of the submission it was written on, not the reviewer.
 *
 * <p>An entity answers for itself, and a part for the entity it belongs to. Anything else, such as a homepage, is
 * nobody's.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = SpecialNameResolver.class)
public class EntityCreatorResolver implements SpecialNameResolver
{
    @Override
    public String getName()
    {
        return PrincipalService.ENTITY_CREATOR_SPECIAL_NAME;
    }

    @Override
    public List<String> resolve(final PrincipalContext context)
    {
        final Entity entity = entityOf(context.subject());
        final String creator = entity == null ? null : entity.getCreatedBy();
        return creator == null || creator.isBlank() ? List.of() : List.of(creator);
    }

    private static Entity entityOf(final Resource subject)
    {
        if (subject == null) {
            return null;
        }
        if (subject.isResourceType(Entity.RESOURCE_TYPE)) {
            return subject.adaptTo(Entity.class);
        }
        if (subject.isResourceType(EntityPart.RESOURCE_TYPE)) {
            return Objects.requireNonNull(subject.adaptTo(EntityPart.class), "A part always reads as one")
                .getEntity();
        }
        return null;
    }
}
