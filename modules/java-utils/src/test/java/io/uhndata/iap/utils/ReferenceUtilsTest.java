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
package io.uhndata.iap.utils;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReferenceUtils}: a reference is stored as one, on a real repository.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ReferenceUtilsTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private Session session;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        this.session.getRootNode().addNode("holder", "nt:unstructured");
        this.session.getRootNode().addNode("target", "nt:unstructured").addMixin("mix:referenceable");
        this.session.getRootNode().addNode("plain", "nt:unstructured");
        this.session.save();
    }

    @Test
    void pointsAPropertyAtAnotherNode() throws PersistenceException, RepositoryException
    {
        final ResourceResolver resolver = this.context.resourceResolver();

        ReferenceUtils.setReference(resolver.getResource("/holder"), "points", resolver.getResource("/target"));

        assertEquals(PropertyType.REFERENCE, this.session.getNode("/holder").getProperty("points").getType());
        assertEquals("/target", this.session.getNode("/holder").getProperty("points").getNode().getPath());
    }

    @Test
    void refusesWhatIsNoNode()
    {
        final Resource notNode = Mockito.mock(Resource.class);
        final Resource target = this.context.resourceResolver().getResource("/target");

        assertThrows(PersistenceException.class, () -> ReferenceUtils.setReference(notNode, "points", target));
        final Resource holder = this.context.resourceResolver().getResource("/holder");
        assertThrows(PersistenceException.class, () -> ReferenceUtils.setReference(holder, "points", notNode));
    }

    @Test
    void reportsAReferenceTheRepositoryRefuses()
    {
        // Only a referenceable node can be pointed at
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource holder = resolver.getResource("/holder");
        final Resource plain = resolver.getResource("/plain");

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> ReferenceUtils.setReference(holder, "points", plain));

        assertInstanceOf(RepositoryException.class, failure.getCause());
    }
}
