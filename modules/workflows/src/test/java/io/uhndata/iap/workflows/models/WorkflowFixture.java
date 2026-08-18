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

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

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

    private WorkflowFixture()
    {
    }

    /**
     * Registers the workflow models along with the base models they extend, and recreates the {@code /libs/wf}
     * resource type registry shipped by this module.
     *
     * <p>The registry is the part that is easy to overlook: a resource only carries the one supertype its node type
     * autocreates, so following {@code wf/StartEvent} all the way up to {@code wf/FlowNode} — which is what
     * {@code getChildren(FlowNode.RESOURCE_TYPE, ...)} needs — only works because each type has a node under
     * {@code /libs} naming its own parent.</p>
     *
     * @param context the Sling context to set up
     */
    public static void setUp(final SlingContext context)
    {
        context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, EntityHomepage.class,
            SingleCondition.class, ConditionGroup.class, ConditionOperand.class);
        context.addModelsForPackage("io.uhndata.iap.workflows.models");

        registerType(context, "WorkflowsHomepage", "iap/EntityHomepage");
        registerType(context, "WorkflowTypesHomepage", "iap/EntityHomepage");
        registerType(context, "SystemWorkflowsHomepage", "iap/EntityHomepage");
        registerType(context, "FlowNodeType", "iap/Entity");
        registerType(context, "CatchingEventType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "ThrowingEventType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "ActivityType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "GatewayType", FlowNodeType.RESOURCE_TYPE);
        registerType(context, "WorkflowDefinition", "iap/Entity");
        registerType(context, "WorkflowVersion", "iap/Entity");
        registerType(context, "FlowNode", "iap/EntityPart");
        registerType(context, "Event", FlowNode.RESOURCE_TYPE);
        registerType(context, "StartEvent", Event.RESOURCE_TYPE);
        registerType(context, "EndEvent", Event.RESOURCE_TYPE);
        registerType(context, "IntermediateEvent", Event.RESOURCE_TYPE);
        registerType(context, "IntermediateCatchingEvent", IntermediateEvent.RESOURCE_TYPE);
        registerType(context, "IntermediateThrowingEvent", IntermediateEvent.RESOURCE_TYPE);
        registerType(context, "Activity", FlowNode.RESOURCE_TYPE);
        registerType(context, "Gateway", FlowNode.RESOURCE_TYPE);
        registerType(context, "ExclusiveGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "ParallelGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "InclusiveGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "EventBasedGateway", Gateway.RESOURCE_TYPE);
        registerType(context, "SequenceFlow", "iap/EntityPart");
        registerType(context, "WorkflowInstances", "iap/Content");
        registerType(context, "WorkflowInstance", "iap/Entity");
        registerType(context, "WorkflowToken", "iap/EntityPart");
        registerType(context, "TaskInstance", "iap/Entity");
        registerType(context, "Variable", "iap/EntityPart");
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

    private static void registerType(final SlingContext context, final String name, final String superType)
    {
        context.create().resource("/libs/wf/" + name, Map.of(SUPER_TYPE, superType));
    }
}
