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
package io.uhndata.iap.schemas.editing.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.conditions.api.ConditionDependencies;
import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;

/**
 * Keeps a question that conditions depend on: removing it would leave them comparing an answer that no longer
 * exists. It refuses to delete anything holding a question an {@code answer} operand names, unless the condition goes
 * with it, and names the parts whose conditions depend on it, so they can be changed first. Which conditions depend
 * on what is the conditions module's to say (see {@link ConditionDependencies}); what goes is judged as a whole, so
 * a condition leaving with what it depends on is no reason to refuse.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DeletionVeto.class)
public class ConditionDependencyVeto implements DeletionVeto
{
    @Override
    public String getName()
    {
        return "Conditions depending on it";
    }

    @Override
    public boolean judgesWholeOperation()
    {
        return true;
    }

    @Override
    public String veto(final Node node, final DeletionMode mode, final Session requester) throws RepositoryException
    {
        if (mode == DeletionMode.PURGE) {
            // What is purged is an archive entry, which no condition can depend on
            return null;
        }
        final List<Node> operands =
            ConditionDependencies.operandsNaming(node, ConditionDependencies.namesOf(node).keySet());
        if (operands.isEmpty()) {
            return null;
        }
        final Node version = ConditionDependencies.entityOf(node);
        // By path, which lists them as the version orders them
        final Map<String, String> dependents = new TreeMap<>();
        for (final Node operand : operands) {
            final Node part = ConditionDependencies.conditionedBy(operand);
            dependents.put(part.getPath(), nameOf(part, version));
        }
        return "The conditions of " + String.join(", ", dependents.values())
            + " depend on it. Change those conditions first.";
    }

    /**
     * How the refusal names a part, as a publishing problem would.
     *
     * @param part a part
     * @param version the version it is in
     * @return its name
     * @throws RepositoryException when it cannot be read
     */
    private static String nameOf(final Node part, final Node version) throws RepositoryException
    {
        final Map<String, String> names = new HashMap<>();
        for (final String property : PartNames.NAMED_BY) {
            if (part.hasProperty(property)) {
                names.put(property, part.getProperty(property).getString());
            }
        }
        return PartNames.of(names::get, PartNames.pathIn(part.getPath(), version.getPath()));
    }
}
