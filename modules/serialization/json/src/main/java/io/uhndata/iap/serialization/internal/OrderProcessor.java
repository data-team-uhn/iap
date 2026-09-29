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

import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Adds {@code @order} to nodes that keep their children in order: the names of their children, in that order. A
 * JSON object's keys carry no order a reader can count on, and a JavaScript object lists keys that look like whole
 * numbers first, in numeric order, whatever order they were written in; so where the order of children means
 * something, such as the questions of a form or the options of a question, a reader takes it from here. The name
 * of this processor is {@code order}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class OrderProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderProcessor.class);

    @Override
    public String getName()
    {
        return "order";
    }

    @Override
    public int getPriority()
    {
        return 10;
    }

    @Override
    public void leave(final Node node, final JsonObjectBuilder json,
        final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (!node.getPrimaryNodeType().hasOrderableChildNodes()) {
                return;
            }
            final JsonArrayBuilder names = Json.createArrayBuilder();
            for (final NodeIterator children = node.getNodes(); children.hasNext();) {
                names.add(children.nextNode().getName());
            }
            json.add("@order", names);
        } catch (final RepositoryException e) {
            // Not fatal to the serialization, but readers fall back to the order of the keys, which may be wrong
            LOGGER.warn("Could not list the children of {} in order: {}", node, e.getMessage(), e);
        }
    }
}
