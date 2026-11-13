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
package io.uhndata.iap.workflows.internal;

import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.entities.models.Entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two saves of one submission at once, on a real repository. Each saver adds its own answer and records a
 * verdict about the whole submission, judged from a view that does not hold the other's answer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class EntityRevisionRaceTest
{
    private static final String ENTITY = "/Requests/one";

    private static final String VERDICT = "tags";

    private static final String[] INCOMPLETE = { "incomplete" };

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private ResourceResolver first;

    private ResourceResolver second;

    @BeforeEach
    void setUp() throws PersistenceException, LoginException
    {
        this.context.create().resource("/Requests");
        this.context.create().resource(ENTITY, Map.of("sling:resourceType", Entity.RESOURCE_TYPE));
        this.context.resourceResolver().commit();
        final ResourceResolverFactory factory = this.context.getService(ResourceResolverFactory.class);
        this.first = factory.getServiceResourceResolver(null);
        this.second = factory.getServiceResourceResolver(null);
    }

    @AfterEach
    void tearDown()
    {
        this.first.close();
        this.second.close();
    }

    @Test
    void withoutTheTokenTwoStaleVerdictsMergeSilently() throws PersistenceException
    {
        // Each judges the submission incomplete, missing the other's answer; both write the same verdict
        save(this.first, "days");
        save(this.second, "reason");

        this.first.commit();
        this.second.commit();

        // Both answers are there, and so is a verdict neither saver would give now
        assertNotNull(read(ENTITY + "/days"));
        assertNotNull(read(ENTITY + "/reason"));
        assertArrayEquals(INCOMPLETE, read(ENTITY).getValueMap().get(VERDICT, String[].class));
    }

    @Test
    void withTheTokenTheSecondSaveIsRefused() throws Exception
    {
        save(this.first, "days");
        EntityRevision.stamp(entity(this.first));
        save(this.second, "reason");
        EntityRevision.stamp(entity(this.second));
        this.first.commit();

        final PersistenceException refusal = assertThrows(PersistenceException.class, this.second::commit);
        assertTrue(RepositoryFailures.isConflict(refusal));
    }

    /**
     * One saver's work: its own answer, and a verdict on the whole submission as its session sees it.
     *
     * @param saver the saver's session
     * @param answer the name of the answer it adds
     * @throws PersistenceException if the answer cannot be added
     */
    private static void save(final ResourceResolver saver, final String answer) throws PersistenceException
    {
        final Resource entity = entity(saver);
        saver.create(entity, answer, Map.of("value", "x"));
        Objects.requireNonNull(entity.adaptTo(ModifiableValueMap.class)).put(VERDICT, INCOMPLETE);
    }

    private static Resource entity(final ResourceResolver resolver)
    {
        return Objects.requireNonNull(resolver.getResource(ENTITY));
    }

    private Resource read(final String path)
    {
        this.context.resourceResolver().refresh();
        return Objects.requireNonNull(this.context.resourceResolver().getResource(path), path);
    }
}
