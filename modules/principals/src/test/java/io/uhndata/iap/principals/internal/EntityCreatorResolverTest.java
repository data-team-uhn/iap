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
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.principals.api.PrincipalContext;
import io.uhndata.iap.principals.api.PrincipalService;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link EntityCreatorResolver}: the creator of the entity a resource belongs to, and nobody outside one.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityCreatorResolverTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String CREATED_BY = "createdBy";

    private static final String SUBMISSION = "/Submissions/leave";

    private final SlingContext context = new SlingContext();

    private final EntityCreatorResolver resolver = new EntityCreatorResolver();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class);
        this.context.create().resource("/Submissions", TYPE, "sub/SubmissionsHomepage", CREATED_BY, "admin");
        this.context.create().resource(SUBMISSION, TYPE, Entity.RESOURCE_TYPE, CREATED_BY, "alice");
    }

    @Test
    void answersForItsName()
    {
        assertEquals(PrincipalService.ENTITY_CREATOR_SPECIAL_NAME, this.resolver.getName());
    }

    @Test
    void answersWithWhoeverRaisedTheEntityAPartBelongsTo()
    {
        this.context.create().resource(SUBMISSION + "/reviews", TYPE, EntityPart.RESOURCE_TYPE, CREATED_BY, "carol");
        assertEquals(List.of("alice"), resolve(this.context.create().resource(SUBMISSION + "/reviews/first",
            TYPE, EntityPart.RESOURCE_TYPE, CREATED_BY, "bob")));
    }

    @Test
    void answersWithWhoeverRaisedAnEntityItself()
    {
        assertEquals(List.of("alice"), resolve(this.context.resourceResolver().getResource(SUBMISSION)));
    }

    @Test
    void answersNobodyForAnEntityNobodyRaised()
    {
        assertEquals(List.of(), resolve(this.context.create().resource("/Submissions/imported",
            TYPE, Entity.RESOURCE_TYPE)));
        assertEquals(List.of(), resolve(this.context.create().resource("/Submissions/blank",
            TYPE, Entity.RESOURCE_TYPE, CREATED_BY, " ")));
    }

    @Test
    void answersNobodyForAPartOutsideAnyEntity()
    {
        assertEquals(List.of(), resolve(this.context.create().resource("/Submissions/stray",
            TYPE, EntityPart.RESOURCE_TYPE, CREATED_BY, "bob")));
    }

    @Test
    void answersNobodyForWhatIsNeitherAnEntityNorAPart()
    {
        assertEquals(List.of(), resolve(this.context.resourceResolver().getResource("/Submissions")));
    }

    @Test
    void answersNobodyWithoutASubject()
    {
        assertEquals(List.of(), this.resolver.resolve(PrincipalContext.actedBy("alice")));
    }

    private List<String> resolve(final Resource subject)
    {
        return this.resolver.resolve(PrincipalContext.about(subject));
    }
}
