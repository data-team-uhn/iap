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

import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import io.uhndata.iap.conditions.api.ConditionDependencies;

/**
 * Renames what an operand names, for the participants that keep conditions working when content is copied or moved.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class OperandValues
{
    /**
     * What a name becomes.
     *
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Renaming
    {
        /**
         * What a name becomes.
         *
         * @param name one of an operand's values
         * @return its new value, the same when it stays
         * @throws RepositoryException when what it names cannot be read
         */
        String rename(String name) throws RepositoryException;
    }

    private OperandValues()
    {
        // Utility class
    }

    /**
     * Renames every value of an operand.
     *
     * @param operand an operand
     * @param renaming what each value becomes
     * @throws RepositoryException when the operand cannot be read or written
     */
    static void rename(final Node operand, final Renaming renaming) throws RepositoryException
    {
        final List<String> names = ConditionDependencies.names(operand);
        final String[] renamed = new String[names.size()];
        for (int i = 0; i < renamed.length; i++) {
            renamed[i] = renaming.rename(names.get(i));
        }
        operand.setProperty("value", renamed);
    }
}
