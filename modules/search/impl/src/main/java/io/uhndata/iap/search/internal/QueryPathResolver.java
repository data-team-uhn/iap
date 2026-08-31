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
package io.uhndata.iap.search.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets a JCR-SQL2 statement name a node by its path where a UUID is expected.
 *
 * <p>
 * A reference property stores the UUID of the node it points to, and a UUID is generated when the node is created, so
 * it differs between instances. A statement kept in the sources therefore cannot spell one out, and has to name the
 * node by its path instead, as in
 * {@code select * from [sub:Answer] as a where a.question = '/Schemas/Consent/1.0/hasCapacity'}. This turns such a
 * path into the UUID that the property actually holds.
 * </p>
 *
 * <p>
 * Only a literal compared against a property that its node type declares as a {@code REFERENCE} or
 * {@code WEAKREFERENCE} is translated. A property that holds a path as its own value is left alone, as is a path
 * passed to a function such as {@code isdescendantnode(n, '/Submissions')} — only comparisons are looked at, which is
 * what draws that line.
 * </p>
 *
 * <p>
 * Everything it cannot make sense of it leaves as it was, so a statement it does not understand runs exactly as it
 * was sent: an unknown node type, an ambiguous unqualified property, a path with no node at it, a target that is not
 * referenceable. The last three are worth a log line, because the statement will then match nothing and the reason is
 * not visible in the response.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class QueryPathResolver
{
    /**
     * Matches a selector the statement declares, capturing its node type and, if present, its alias. The keywords
     * are matched wherever they appear, so a statement with {@code from} inside a string literal contributes a
     * selector that does not exist; that costs nothing, since a node type that is not registered is not translated
     * against.
     */
    private static final Pattern QUERY_SELECTOR = Pattern.compile(
        "\\b(?:from|join)\\s++\\[([^\\]]++)\\]\\s*+(?:\\bas\\s++(\\[[^\\]]++\\]|[\\w:]++))?", Pattern.CASE_INSENSITIVE);

    /**
     * Matches a property compared to a string literal holding a path, capturing the selector, the property and the
     * literal. Only a comparison is matched, which is what keeps a path passed to a function from being translated.
     */
    private static final Pattern PROPERTY_COMPARISON = Pattern.compile(
        "(?<!\\[)(?:(\\[[^\\]]++\\]|[\\w:]++)\\.)?(\\[[^\\]]++\\]|[\\w:]++)\\s*(?:=|<>|!=)\\s*+'(/(?:[^']|'')*+)'");

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPathResolver.class);

    private QueryPathResolver()
    {
        // This is a utility class, it should not be instantiated
    }

    /**
     * Replaces the node paths a statement compares reference properties to with the UUID of the node at each path.
     *
     * @param session the session to look the node types and the referenced nodes up with, never {@code null}
     * @param statement a JCR-SQL2 statement, which may compare reference properties to paths
     * @return the same statement, with those paths replaced by the UUID of the node they point to; a path that
     *         cannot be resolved is left as it is
     * @throws RepositoryException if the node types cannot be read
     */
    static String resolveReferencePaths(final Session session, final String statement) throws RepositoryException
    {
        final Matcher comparisons = PROPERTY_COMPARISON.matcher(statement);
        if (!comparisons.find()) {
            return statement;
        }
        final Map<String, String> selectorTypes = getSelectorTypes(statement);
        final NodeTypeManager nodeTypes = session.getWorkspace().getNodeTypeManager();

        final StringBuilder result = new StringBuilder();
        int copiedUpTo = 0;
        do {
            if (!isReferenceProperty(nodeTypes, selectorTypes, comparisons.group(1), comparisons.group(2))) {
                continue;
            }
            // A literal escapes an apostrophe by doubling it; undo that before treating the value as a path
            final String uuid = getUuid(session, comparisons.group(3).replace("''", "'"));
            if (uuid == null) {
                continue;
            }
            result.append(statement, copiedUpTo, comparisons.start(3)).append(uuid);
            copiedUpTo = comparisons.end(3);
        } while (comparisons.find());
        return result.append(statement, copiedUpTo, statement.length()).toString();
    }

    /**
     * Collects the selectors a statement declares, so that a property can be looked for in the right node type.
     *
     * @param statement a JCR-SQL2 statement
     * @return the node type each selector selects, keyed by the selector's name, which is its alias when it has one
     *         and its node type otherwise
     */
    private static Map<String, String> getSelectorTypes(final String statement)
    {
        final Map<String, String> selectorTypes = new HashMap<>();
        final Matcher selectors = QUERY_SELECTOR.matcher(statement);
        while (selectors.find()) {
            final String nodeType = selectors.group(1);
            final String alias = selectors.group(2);
            selectorTypes.put(unquoteName(alias == null ? nodeType : alias), nodeType);
        }
        return selectorTypes;
    }

    /**
     * Checks whether a property a statement compares is a reference to another node.
     *
     * @param nodeTypes the node type manager to look the definition up in
     * @param selectorTypes the node type selected by each of the statement's selectors
     * @param selector the selector the property was qualified with, may be {@code null} for an unqualified property
     * @param property the name of the compared property
     * @return {@code true} if the node type declares this property as a reference
     * @throws RepositoryException if the node types cannot be read
     */
    private static boolean isReferenceProperty(final NodeTypeManager nodeTypes,
        final Map<String, String> selectorTypes, final String selector, final String property)
        throws RepositoryException
    {
        // An unqualified property only says which node type it belongs to when the statement declares one selector
        final String nodeType = selector != null ? selectorTypes.get(unquoteName(selector))
            : selectorTypes.size() == 1 ? selectorTypes.values().iterator().next() : null;
        if (nodeType == null || !nodeTypes.hasNodeType(nodeType)) {
            return false;
        }
        final String propertyName = unquoteName(property);
        return Arrays.stream(nodeTypes.getNodeType(nodeType).getPropertyDefinitions())
            .filter(definition -> propertyName.equals(definition.getName()))
            .anyMatch(definition -> definition.getRequiredType() == PropertyType.REFERENCE
                || definition.getRequiredType() == PropertyType.WEAKREFERENCE);
    }

    /**
     * Looks up the UUID of the node at a path.
     *
     * <p>
     * A path that leads nowhere, and a node that cannot be referenced at all, are both left for the statement to run
     * into on its own: it then matches nothing, which is the same answer it would have given for a UUID that is not
     * in use. Neither is visible in the response, so both are logged.
     * </p>
     *
     * @param session the session to look the node up with
     * @param path the path of the referenced node
     * @return the UUID of that node, or {@code null} if there is nothing usable at that path
     */
    private static String getUuid(final Session session, final String path)
    {
        try {
            if (!session.nodeExists(path)) {
                LOGGER.warn("A search refers to [{}], where there is no node; leaving the path as it is",
                    QueryPlanChecker.forLog(path));
                return null;
            }
            final Node node = session.getNode(path);
            if (!node.isNodeType("mix:referenceable")) {
                // Oak answers getIdentifier() for such a node with its path, which no reference property holds
                LOGGER.warn("A search refers to [{}], which is not referenceable; leaving the path as it is",
                    QueryPlanChecker.forLog(path));
                return null;
            }
            return node.getIdentifier();
        } catch (final RepositoryException | RuntimeException e) {
            // Unchecked as much as checked: a malformed path is the client's to send, and the repository is entitled
            // to reject one without wrapping its complaint
            LOGGER.warn("Failed to read the identifier of [{}]: {}", QueryPlanChecker.forLog(path), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Strips the square brackets a statement may quote a selector or property name with.
     *
     * @param name a name as it appears in the statement
     * @return the bare name
     */
    private static String unquoteName(final String name)
    {
        return name.startsWith("[") ? name.substring(1, name.length() - 1) : name;
    }
}
