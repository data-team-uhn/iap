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
package io.uhndata.iap.conditions.internal;

import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.AnswerDependencies;
import io.uhndata.iap.utils.move.MoveParticipant;

/**
 * Keeps conditions working when the content they depend on moves. An {@code answer} operand may name a question by its
 * path within the entity holding the operand, and moving the question breaks that path. So before a move, every operand
 * naming the moved node, or anything under it, is changed to name it by identifier instead (see
 * {@link AnswerDependencies}). An identifier does not depend on where its node is. When content moves into another
 * entity, the conditions moving with it would read their paths against the new entity. So they also switch to
 * identifiers for what they name outside it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = MoveParticipant.class)
public class AnswerOperandsMoveParticipant implements MoveParticipant
{
    @Override
    public void beforeMove(final Node node, final String newPath) throws RepositoryException
    {
        final Map<String, String> identifiers = AnswerDependencies.namesOf(node);
        for (final Node operand : AnswerDependencies.operandsNaming(node, identifiers.keySet(), true)) {
            OperandValues.rename(operand, named -> identifiers.getOrDefault(named, named));
        }
        final Node entity = AnswerDependencies.entityOf(node);
        if (entity != null && !entity.isSame(node) && leaves(entity, newPath, node.getSession())) {
            // The conditions moving along will read their paths against the new entity, so name what stays by
            // identifier
            for (final Node operand : AnswerDependencies.operandsIn(node)) {
                OperandValues.rename(operand, named -> identifierIn(entity, named));
            }
        }
    }

    /**
     * Whether a move takes content out of its entity.
     *
     * @param entity the entity it is in
     * @param newPath where it goes
     * @param session the session it moves in
     * @return whether its new parent is in another entity, or in none
     * @throws RepositoryException when the new parent cannot be read
     */
    private static boolean leaves(final Node entity, final String newPath, final Session session)
        throws RepositoryException
    {
        final String parentPath = newPath.substring(0, Math.max(1, newPath.lastIndexOf('/')));
        final Node destination = AnswerDependencies.entityOf(session.getNode(parentPath));
        return destination == null || !destination.isSame(entity);
    }

    /**
     * Turns a path to something referenceable in an entity into that thing's identifier. Any other name is returned as
     * it was.
     *
     * @param entity the entity the path is read against
     * @param named a name, perhaps a path
     * @return the identifier, or the name unchanged
     * @throws RepositoryException when the entity cannot be read
     */
    private static String identifierIn(final Node entity, final String named) throws RepositoryException
    {
        if (!named.startsWith("/") && entity.hasNode(named)) {
            final Node node = entity.getNode(named);
            if (node.isNodeType("mix:referenceable")) {
                return node.getIdentifier();
            }
        }
        return named;
    }
}
