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
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.copy.CopyParticipant;

/**
 * Points the answer operands in a copy at the copied questions. An operand names its question by UUID, or by a path
 * relative to what was copied; either way, once copied, it names the question's copy by UUID, which survives the
 * question being moved or renamed later. A question outside the copy is still named as it was.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = CopyParticipant.class)
public class AnswerOperandsCopyParticipant implements CopyParticipant
{
    private static final String SOURCE_PROPERTY = "source";

    private static final String VALUE_PROPERTY = "value";

    @Override
    public void afterCopy(final Node source, final Node copy, final Map<String, String> identifiers)
        throws RepositoryException
    {
        if (copy.isNodeType("cond:ConditionOperand") && copy.hasProperty(VALUE_PROPERTY)
            && AnswerOperandResolver.SOURCE.equals(copy.getProperty(SOURCE_PROPERTY).getString())) {
            final Value[] named = copy.getProperty(VALUE_PROPERTY).getValues();
            final String[] copied = new String[named.length];
            for (int i = 0; i < named.length; i++) {
                copied[i] = copyOf(named[i].getString(), source, identifiers);
            }
            copy.setProperty(VALUE_PROPERTY, copied);
        }
        final NodeIterator children = copy.getNodes();
        while (children.hasNext()) {
            afterCopy(source, children.nextNode(), identifiers);
        }
    }

    /**
     * The UUID of the copy of the question an operand names.
     *
     * @param question how the operand names its question: a UUID, or a path relative to what was copied
     * @param source what was copied
     * @param identifiers the copied nodes' identifiers, each original's mapped to its copy's
     * @return the copy's UUID, or the name unchanged when it names nothing copied
     * @throws RepositoryException when the source cannot be read
     */
    private static String copyOf(final String question, final Node source, final Map<String, String> identifiers)
        throws RepositoryException
    {
        if (identifiers.containsKey(question)) {
            return identifiers.get(question);
        }
        if (!question.startsWith("/") && source.hasNode(question)) {
            return identifiers.getOrDefault(source.getNode(question).getIdentifier(), question);
        }
        return question;
    }
}
