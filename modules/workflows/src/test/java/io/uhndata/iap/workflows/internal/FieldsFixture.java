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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Content for the update tests, in an Oak-backed repository: an item of a type declaring a mandatory title, an
 * optional note and a link, another item to link to, and an update activity listing those fields and one the type
 * does not declare.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class FieldsFixture
{
    private static final String UNSTRUCTURED = "nt:unstructured";

    private final Session session;

    private final ResourceResolver resolver;

    private final Node item;

    private final Node other;

    private final Activity activity = Mockito.mock(Activity.class);

    private final Activity creating = Mockito.mock(Activity.class);

    /**
     * Builds the content.
     *
     * @param context an Oak-backed context
     * @throws RepositoryException when the content cannot be written
     */
    FieldsFixture(final SlingContext context) throws RepositoryException
    {
        context.addModelsForClasses(Content.class);
        this.resolver = context.resourceResolver();
        this.session = this.resolver.adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/fields-test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        final Node root = this.session.getRootNode();
        this.item = item(root, "item", "The item");
        this.other = item(root, "other", "Another item");
        final Node fields = root.addNode("update", UNSTRUCTURED).addNode("fields", UNSTRUCTURED);
        fields.addNode("title", UNSTRUCTURED).setProperty("label", "Title");
        final Node note = fields.addNode("note", UNSTRUCTURED);
        note.setProperty("label", "Note");
        note.setProperty("multiline", true);
        fields.addNode("link", UNSTRUCTURED).setProperty("referenceType", "test/Item");
        fields.addNode("weakLink", UNSTRUCTURED).setProperty("referenceRoot", "/other");
        fields.addNode("related", UNSTRUCTURED).setProperty("referenceRoot", "/");
        final Node shape = fields.addNode("shape", UNSTRUCTURED);
        shape.setProperty("help", "What it looks like.");
        final Node shapes = shape.addNode("choices", UNSTRUCTURED);
        shapes.addNode("round", UNSTRUCTURED).setProperty("label", "Round");
        shapes.addNode("squareChoice", UNSTRUCTURED).setProperty("value", "square");
        dependsOnShape(fields.addNode("count", UNSTRUCTURED), "round", "square");
        dependsOnShape(fields.addNode("ratio", UNSTRUCTURED), "square");
        fields.addNode("flag", UNSTRUCTURED);
        fields.addNode("keywords", UNSTRUCTURED);
        fields.addNode("sizes", UNSTRUCTURED);
        fields.addNode("due", UNSTRUCTURED);
        fields.addNode("level", UNSTRUCTURED);
        fields.addNode("weight", UNSTRUCTURED);
        fields.addNode("visible", UNSTRUCTURED);
        fields.addNode("colours", UNSTRUCTURED);
        fields.addNode("extra", UNSTRUCTURED).setProperty("label", "Only allowed by a residual definition");
        root.addNode("box", "test:Box");
        root.addNode("shelf", "test:Shelf");
        final Node types = root.addNode("create", UNSTRUCTURED).addNode("types", UNSTRUCTURED);
        final Node listedItem = types.addNode("item", UNSTRUCTURED);
        listedItem.setProperty("nodeType", "test:Item");
        listedItem.setProperty("label", "Item");
        types.addNode("box", UNSTRUCTURED).setProperty("nodeType", "test:Box");
        types.addNode("unnamed", UNSTRUCTURED).setProperty("label", "Names no type");
        types.addNode("abstract", UNSTRUCTURED).setProperty("nodeType", "data:Content");
        types.addNode("ghost", UNSTRUCTURED).setProperty("nodeType", "test:Ghost");
        this.session.save();
        final Resource create = context.resourceResolver().getResource("/create");
        Mockito.when(this.creating.getPath()).thenReturn("/create");
        Mockito.when(this.creating.getChild("types", Content.class))
            .thenAnswer(invocation -> create.getChild("types").adaptTo(Content.class));
        Mockito.when(this.creating.get("nameFrom", String[].class)).thenReturn(new String[] { "note", "title" });
        Mockito.when(this.creating.getHandler()).thenReturn(CreateContentHandler.HANDLER_NAME);
        final Resource update = context.resourceResolver().getResource("/update");
        Mockito.when(this.activity.getPath()).thenReturn("/update");
        Mockito.when(this.activity.getChild("fields", Content.class))
            .thenAnswer(invocation -> update.getChild("fields").adaptTo(Content.class));
        Mockito.when(this.activity.getHandler()).thenReturn(UpdateContentHandler.HANDLER_NAME);
    }

    Session session()
    {
        return this.session;
    }

    Node item()
    {
        return this.item;
    }

    Node other()
    {
        return this.other;
    }

    Activity activity()
    {
        return this.activity;
    }

    Activity creating()
    {
        return this.creating;
    }

    /**
     * Makes a mocked engine hand a workflow version to whatever inspects the workflow that would take an event.
     *
     * @param engine the mocked engine
     * @param event the event's name
     * @param version the version it hands over
     * @throws WorkflowException never, the engine being a mock
     */
    static void inspecting(final WorkflowEngine engine, final String event, final WorkflowVersion version)
        throws WorkflowException
    {
        Mockito.when(engine.inspectWorkflow(Mockito.any(), Mockito.eq(event), Mockito.any()))
            .thenAnswer(invocation -> invocation.<Function<WorkflowVersion, Object>>getArgument(2).apply(version));
    }

    /**
     * The context of a task running an activity for an event, with the variables it sets recorded.
     *
     * @param event the event's name
     * @param activity the activity
     * @param payload the event's payload
     * @param variables where the variables the task sets go
     * @return the context; its target is left for the test to set
     */
    WorkflowTaskContext task(final String event, final Activity activity, final Map<String, Object> payload,
        final Map<String, Object> variables)
    {
        final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);
        Mockito.when(task.getEvent()).thenReturn(new WorkflowEvent(event, payload));
        Mockito.when(task.getActivity()).thenReturn(activity);
        Mockito.when(task.getResourceResolver()).thenReturn(this.resolver);
        Mockito.doAnswer(invocation -> variables.put(invocation.getArgument(0), invocation.getArgument(1)))
            .when(task).setVariable(Mockito.anyString(), Mockito.any());
        return task;
    }

    /**
     * The names of a node's children, in order.
     *
     * @param path the node's path
     * @return the names
     * @throws RepositoryException when the node cannot be read
     */
    List<String> children(final String path) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        for (final NodeIterator nodes = this.session.getNode(path).getNodes(); nodes.hasNext();) {
            names.add(nodes.nextNode().getName());
        }
        return names;
    }

    private static void dependsOnShape(final Node field, final String... shapes) throws RepositoryException
    {
        final Node applicability = field.addNode("appliesWhen", UNSTRUCTURED);
        applicability.setProperty("property", "shape");
        applicability.setProperty("values", shapes);
    }

    private static Node item(final Node parent, final String name, final String title) throws RepositoryException
    {
        final Node item = parent.addNode(name, "test:Item");
        item.setProperty("title", title);
        return item;
    }
}
