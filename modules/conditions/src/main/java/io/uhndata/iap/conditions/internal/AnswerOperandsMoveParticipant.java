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

import io.uhndata.iap.conditions.api.ConditionDependencies;
import io.uhndata.iap.utils.move.MoveParticipant;

/**
 * Keeps conditions working when what they depend on moves. An {@code answer} operand may name a question by its path
 * within the entity holding the operand, which moving the question would break; before anything moves, every operand
 * naming it, or anything under it, names it by identifier instead, which survives any move (see
 * {@link ConditionDependencies}). A move into another entity also has the conditions going along name what they
 * leave behind by identifier, since their paths would otherwise be read against the new entity.
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
        final Map<String, String> identifiers = ConditionDependencies.namesOf(node);
        for (final Node operand : ConditionDependencies.operandsNaming(node, identifiers.keySet(), true)) {
            OperandValues.rename(operand, named -> identifiers.getOrDefault(named, named));
        }
        final Node entity = ConditionDependencies.entityOf(node);
        if (entity != null && !entity.isSame(node) && leaves(entity, newPath, node.getSession())) {
            // Paths in the conditions going along are read against where they go: name what stays by identifier
            for (final Node operand : ConditionDependencies.operandsIn(node)) {
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
        final Node destination = ConditionDependencies.entityOf(session.getNode(parentPath));
        return destination == null || !destination.isSame(entity);
    }

    /**
     * What a name becomes once read against another entity: the identifier of what it names in this one, when it is
     * a path there to something referenceable.
     *
     * @param entity the entity the name is read against now
     * @param named the name
     * @return the identifier, or the name as it was
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
