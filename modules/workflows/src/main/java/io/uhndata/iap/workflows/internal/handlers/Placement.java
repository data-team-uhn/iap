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
package io.uhndata.iap.workflows.internal.handlers;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Where content goes among its new siblings: before the sibling the event names as {@code before}, or else last. An
 * activity may also have content numbered by its place, in the property it names as {@code orderProperty}. This is
 * for readers that order content by a property rather than as it is stored.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class Placement
{
    /** The payload entry naming the sibling content goes before. */
    static final String BEFORE_PARAMETER = "before";

    /** The activity property naming the property that numbers content by its place. */
    static final String ORDER_PROPERTY = "orderProperty";

    /** How far apart places are numbered, which leaves room for content placed between them by hand. */
    private static final long STEP = 10;

    private Placement()
    {
        // Utility class
    }

    /**
     * The sibling the event places content before, if it names one.
     *
     * @param context the executing task's context
     * @param parent where the content goes
     * @return the sibling's name, or {@code null} to place it last
     * @throws InvalidPayloadException when that is not a child of the parent, or the parent keeps no order
     * @throws RepositoryException when the parent cannot be read
     */
    static String before(final WorkflowTaskContext context, final Node parent)
        throws InvalidPayloadException, RepositoryException
    {
        final Object before = context.getEvent().get(BEFORE_PARAMETER);
        if (before == null) {
            return null;
        }
        if (!(before instanceof String) || !parent.hasNode((String) before)) {
            throw new InvalidPayloadException("There is nothing called " + before + " to go before");
        }
        if (!parent.getPrimaryNodeType().hasOrderableChildNodes()) {
            throw new InvalidPayloadException(parent.getName() + " keeps no order to place content in");
        }
        return (String) before;
    }

    /**
     * Numbers a node's children of one type by their places, {@value #STEP} apart. Ordered by that property, they then
     * come in the order the node keeps them.
     *
     * @param parent the node
     * @param type the primary type of the children numbered
     * @param property the property numbering them, or {@code null} to number nothing
     * @throws RepositoryException when the children cannot be read or numbered
     */
    static void number(final Node parent, final String type, final String property) throws RepositoryException
    {
        if (property == null) {
            return;
        }
        long place = 0;
        for (final NodeIterator children = parent.getNodes(); children.hasNext();) {
            final Node child = children.nextNode();
            if (child.getPrimaryNodeType().getName().equals(type)) {
                place += STEP;
                if (!child.hasProperty(property) || child.getProperty(property).getLong() != place) {
                    child.setProperty(property, place);
                }
            }
        }
    }
}
