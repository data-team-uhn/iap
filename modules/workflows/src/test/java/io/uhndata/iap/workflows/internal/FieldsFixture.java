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

import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.mockito.Mockito;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.Activity;

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
    private final Session session;

    private final Node item;

    private final Node other;

    private final Activity activity = Mockito.mock(Activity.class);

    /**
     * Builds the content.
     *
     * @param context an Oak-backed context
     * @throws RepositoryException when the content cannot be written
     */
    FieldsFixture(final SlingContext context) throws RepositoryException
    {
        context.addModelsForClasses(Content.class);
        this.session = context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/fields-test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        final Node root = this.session.getRootNode();
        this.item = item(root, "item", "The item");
        this.other = item(root, "other", "Another item");
        final Node fields = root.addNode("update", "nt:unstructured").addNode("fields", "nt:unstructured");
        fields.addNode("title", "nt:unstructured").setProperty("label", "Title");
        final Node note = fields.addNode("note", "nt:unstructured");
        note.setProperty("label", "Note");
        note.setProperty("multiline", true);
        fields.addNode("link", "nt:unstructured").setProperty("referenceType", "test/Item");
        fields.addNode("weakLink", "nt:unstructured").setProperty("referenceRoot", "/other");
        fields.addNode("related", "nt:unstructured").setProperty("referenceRoot", "/");
        final Node shape = fields.addNode("shape", "nt:unstructured");
        shape.setProperty("help", "What it looks like.");
        final Node shapes = shape.addNode("choices", "nt:unstructured");
        shapes.addNode("round", "nt:unstructured").setProperty("label", "Round");
        shapes.addNode("squareChoice", "nt:unstructured").setProperty("value", "square");
        dependsOnShape(fields.addNode("count", "nt:unstructured"), "round", "square");
        dependsOnShape(fields.addNode("ratio", "nt:unstructured"), "square");
        fields.addNode("flag", "nt:unstructured");
        fields.addNode("keywords", "nt:unstructured");
        fields.addNode("sizes", "nt:unstructured");
        fields.addNode("due", "nt:unstructured");
        fields.addNode("extra", "nt:unstructured").setProperty("label", "Only allowed by a residual definition");
        this.session.save();
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

    private static void dependsOnShape(final Node field, final String... shapes) throws RepositoryException
    {
        final Node applicability = field.addNode("appliesWhen", "nt:unstructured");
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
