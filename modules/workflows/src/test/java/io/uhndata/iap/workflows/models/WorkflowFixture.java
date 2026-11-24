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
package io.uhndata.iap.workflows.models;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.models.ConditionGroup;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.models.SingleCondition;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityHomepage;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.tags.testing.PropertyTagging;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.testing.WorkflowResourceTypes;

/**
 * Shared setup for the workflow model tests: the models under test, and the {@code /libs/wf} resource type
 * registry they dispatch through.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowFixture
{
    /** The {@code sling:resourceType} property name, spelled out often enough to be worth a constant. */
    public static final String TYPE = "sling:resourceType";

    /** The {@code sling:resourceSuperType} property name. */
    public static final String SUPER_TYPE = "sling:resourceSuperType";

    /** The property a {@code wf:WorkflowVersion} carries its lifecycle tag in, among any others. */
    public static final String TAGS = "tags";

    /**
     * The lifecycle tag of a version new instances start from. Read from the model, so renaming it fails these tests
     * to compile instead of quietly leaving them building versions the engine will skip.
     */
    public static final String ACTIVE = WorkflowVersion.ACTIVE_TAG;

    private static final String LIFECYCLE = "lifecycle";

    private static final String REVIEW = "review";

    private WorkflowFixture()
    {
    }

    /**
     * Tags, the way a node stores them.
     *
     * @param names the tags' names
     * @return the value of a {@link #TAGS} property holding them
     */
    public static String[] tags(final String... names)
    {
        return names;
    }

    /**
     * A mock activity that still decides what it acts on as the model does, so that a handler asking
     * {@link io.uhndata.iap.workflows.spi.ExecutionHost} under test gets the target, or what was created.
     *
     * @return the mock, ready for further stubbing
     */
    public static Activity activity()
    {
        final Activity activity = Mockito.mock(Activity.class);
        try {
            Mockito.doCallRealMethod().when(activity).actsOn(Mockito.any());
        } catch (final WorkflowDefinitionException e) {
            throw new AssertionError("Stubbing a method never runs it", e);
        }
        return activity;
    }

    /**
     * Registers the workflow models along with the base models they extend, and installs the
     * {@link WorkflowResourceTypes resource types} this module ships.
     *
     * @param context the Sling context to set up
     */
    public static void setUp(final SlingContext context)
    {
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, EntityHomepage.class,
            SingleCondition.class, ConditionGroup.class, ConditionOperand.class);
        context.addModelsForPackage("io.uhndata.iap.workflows.models");

        WorkflowResourceTypes.installIn(context);
    }

    /**
     * Makes a JCR reference to the given path resolvable, the way a real repository would. Additive: a second call
     * teaches the session already in place about another identifier rather than replacing it, so a test needing two
     * resolvable references does not silently lose the first.
     *
     * @param context the Sling context to set up
     * @param identifier the identifier a reference property holds
     * @param path the path the identifier stands for
     * @throws RepositoryException never, only declared by the mocked JCR API
     */
    public static void resolveReference(final SlingContext context, final String identifier, final String path)
        throws RepositoryException
    {
        final Node target = Mockito.mock(Node.class);
        Mockito.when(target.getPath()).thenReturn(path);
        Session session = context.resourceResolver().adaptTo(Session.class);
        if (session == null) {
            session = Mockito.mock(Session.class);
            context.registerAdapter(ResourceResolver.class, Session.class, session);
        }
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(target);
    }

    /**
     * Registers the {@code Taggable} view, so a host adapts to it the way it does with the tags bundle
     * installed. Without it every run that reaches an {@code addTag} task fails on the absent tags service.
     *
     * <p>The lifecycle tags the engine tests place get the categories their shipped definitions give them, so a
     * test can read back what a workflow placed and what it retired.</p>
     *
     * @param context the mock context whose resources become taggable
     */
    public static void enableTagging(final SlingContext context)
    {
        PropertyTagging.builder()
            .inCategories("draft", LIFECYCLE)
            .inCategories("approved", LIFECYCLE, REVIEW)
            .inCategories("rejected", LIFECYCLE, REVIEW)
            .installIn(context);
    }

    /**
     * The tags placed on a resource.
     *
     * @param resource a resource
     * @return its own tags, in order
     */
    public static Set<String> tags(final Resource resource)
    {
        return new LinkedHashSet<>(List.of(resource.getValueMap().get("tags", new String[0])));
    }
}
