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
package io.uhndata.iap.utils.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.utils.move.ContentMover;
import io.uhndata.iap.utils.move.MoveParticipant;

/**
 * Moves content through the session of the nodes given, letting every {@link MoveParticipant} prepare first when a
 * path changes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ContentMover.class)
public class ContentMoverImpl implements ContentMover
{
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY, fieldOption = FieldOption.UPDATE)
    private final List<MoveParticipant> participants = new CopyOnWriteArrayList<>();

    @Override
    public String move(final Node node, final Node parent, final String name, final String before)
        throws RepositoryException
    {
        if (!node.getParent().isSame(parent) || !node.getName().equals(name)) {
            final String newPath = (parent.getDepth() == 0 ? "" : parent.getPath()) + "/" + name;
            for (final MoveParticipant participant : this.participants) {
                participant.beforeMove(node, newPath);
            }
            node.getSession().move(node.getPath(), newPath);
        }
        if (parent.getPrimaryNodeType().hasOrderableChildNodes() && !name.equals(before)) {
            parent.orderBefore(name, before);
        }
        return parent.getNode(name).getPath();
    }
}
