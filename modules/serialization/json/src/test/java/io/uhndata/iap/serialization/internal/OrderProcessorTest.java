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
package io.uhndata.iap.serialization.internal;

import java.util.Arrays;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link OrderProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class OrderProcessorTest
{
    private final OrderProcessor processor = new OrderProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("order", this.processor.getName());
        Assertions.assertEquals(10, this.processor.getPriority());
        Assertions.assertFalse(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testChildrenAreListedInTheirOrder()
        throws Exception
    {
        final Node node = parent(true, "b", "10", "2", "a");
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.leave(node, json, null);

        Assertions.assertEquals(List.of("b", "10", "2", "a"), json.build().getJsonArray("@order").stream()
            .map(name -> ((JsonString) name).getString()).toList());
    }

    @Test
    public void testNodesKeepingNoOrderSayNone()
        throws Exception
    {
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.leave(parent(false, "a"), json, null);

        Assertions.assertTrue(json.build().isEmpty());
    }

    @Test
    public void testInaccessibleNodeIsIgnored()
        throws Exception
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPrimaryNodeType()).thenThrow(new RepositoryException());
        final JsonObjectBuilder json = Json.createObjectBuilder();

        this.processor.leave(node, json, null);

        Assertions.assertTrue(json.build().isEmpty());
    }

    private static Node parent(final boolean orderable, final String... names)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        final NodeType type = Mockito.mock(NodeType.class);
        Mockito.when(type.hasOrderableChildNodes()).thenReturn(orderable);
        Mockito.when(node.getPrimaryNodeType()).thenReturn(type);
        final NodeIterator children = Mockito.mock(NodeIterator.class);
        final Boolean[] more = new Boolean[names.length];
        Arrays.fill(more, Boolean.TRUE);
        more[names.length - 1] = Boolean.FALSE;
        Mockito.when(children.hasNext()).thenReturn(true, more);
        final Node[] nodes = new Node[names.length];
        for (int i = 0; i < names.length; i++) {
            nodes[i] = Mockito.mock(Node.class);
            Mockito.when(nodes[i].getName()).thenReturn(names[i]);
        }
        Mockito.when(children.nextNode()).thenReturn(nodes[0], Arrays.copyOfRange(nodes, 1, nodes.length));
        Mockito.when(node.getNodes()).thenReturn(children);
        return node;
    }
}
