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

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.PropertyDefinition;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link QueryPathResolver}.
 *
 * <p>
 * The repository behaviour the class is built on was measured against Oak 2.4.0: a node that is not
 * {@code mix:referenceable} answers {@code getIdentifier()} with its own path, which is not something a reference
 * property can ever hold, so such a target is no more usable than a missing one.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class QueryPathResolverTest
{
    private static final String ANSWER = "sub:Answer";

    private static final String QUESTION = "question";

    private static final String QUESTION_PATH = "/Schemas/Consent/1.0/hasCapacity";

    private static final String UUID = "d1f5a0e2-4b0a-4a3a-9f6b-0c2d1e3f4a5b";

    private static final String REFERENCEABLE = "mix:referenceable";

    private static final String BY_PATH =
        "select * from [sub:Answer] as a where a.question = '" + QUESTION_PATH + "'";

    private static final String BY_UUID = "select * from [sub:Answer] as a where a.question = '" + UUID + "'";

    private Session session;

    private NodeTypeManager nodeTypes;

    @BeforeEach
    public void setup() throws Exception
    {
        this.session = Mockito.mock(Session.class);
        this.nodeTypes = Mockito.mock(NodeTypeManager.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        Mockito.when(this.session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getNodeTypeManager()).thenReturn(this.nodeTypes);
    }

    @Test
    public void aPathComparedToAReferenceBecomesTheUuidItHolds() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals(BY_UUID, resolve(BY_PATH));
    }

    @Test
    public void aWeakReferenceIsResolvedAsWell() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.WEAKREFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals(BY_UUID, resolve(BY_PATH));
    }

    @Test
    public void anInequalityIsResolvedAsWell() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals("select * from [sub:Answer] as a where a.question <> '" + UUID + "'",
            resolve("select * from [sub:Answer] as a where a.question <> '" + QUESTION_PATH + "'"));
    }

    @Test
    public void aStatementWithNoPathInItIsReturnedAsItIs() throws Exception
    {
        final String statement = "select * from [sub:Submission] as s where s.title = 'diabetes'";
        Assertions.assertSame(statement, resolve(statement));
    }

    @Test
    public void aPathComparedToAnOrdinaryPropertyIsLeftAlone() throws Exception
    {
        // The value of such a property really is a path; turning it into a UUID would break the statement
        withType(ANSWER, "value", PropertyType.STRING);
        withNode(QUESTION_PATH, UUID);
        final String statement = "select * from [sub:Answer] as a where a.value = '" + QUESTION_PATH + "'";
        Assertions.assertEquals(statement, resolve(statement));
    }

    @Test
    public void aPropertyTheNodeTypeDoesNotDeclareIsLeftAlone() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        final String statement = "select * from [sub:Answer] as a where a.somethingElse = '" + QUESTION_PATH + "'";
        Assertions.assertEquals(statement, resolve(statement));
    }

    @Test
    public void aPathGivenToAFunctionIsLeftAlone() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode("/Submissions", UUID);
        // Only a comparison is translated, which is what keeps a scoping path out of it
        final String statement = "select * from [sub:Answer] as a where isdescendantnode(a, '/Submissions')";
        Assertions.assertEquals(statement, resolve(statement));
    }

    @Test
    public void anUnknownNodeTypeIsLeftAlone() throws Exception
    {
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals(BY_PATH, resolve(BY_PATH));
    }

    @Test
    public void anUnqualifiedPropertyIsResolvedAgainstTheOnlySelector() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals("select * from [sub:Answer] where question = '" + UUID + "'",
            resolve("select * from [sub:Answer] where question = '" + QUESTION_PATH + "'"));
    }

    @Test
    public void anUnqualifiedPropertyIsLeftAloneWhenTheStatementJoins() throws Exception
    {
        // With two selectors in play there is no telling which node type the property belongs to
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        final String statement = "select * from [sub:Answer] as a inner join [sub:Submission] as s"
            + " on isdescendantnode(a, s) where question = '" + QUESTION_PATH + "'";
        Assertions.assertEquals(statement, resolve(statement));
    }

    @Test
    public void aQualifiedPropertyIsResolvedEvenWhenTheStatementJoins() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals("select * from [sub:Answer] as a inner join [sub:Submission] as s"
            + " on isdescendantnode(a, s) where a.question = '" + UUID + "'",
            resolve("select * from [sub:Answer] as a inner join [sub:Submission] as s"
                + " on isdescendantnode(a, s) where a.question = '" + QUESTION_PATH + "'"));
    }

    @Test
    public void aBracketedSelectorAndPropertyAreUnderstood() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals("select * from [sub:Answer] as [a] where [a].[question] = '" + UUID + "'",
            resolve("select * from [sub:Answer] as [a] where [a].[question] = '" + QUESTION_PATH + "'"));
    }

    @Test
    public void everyComparisonInAStatementIsResolved() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        final String other = "/Schemas/Consent/1.0/isAdult";
        final String otherUuid = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d";
        withNode(other, otherUuid);
        Assertions.assertEquals("select * from [sub:Answer] as a where a.question = '" + UUID
            + "' or a.question = '" + otherUuid + "'",
            resolve("select * from [sub:Answer] as a where a.question = '" + QUESTION_PATH
                + "' or a.question = '" + other + "'"));
    }

    @Test
    public void aResolvedComparisonFollowingAnUntouchedOneLandsInTheRightPlace() throws Exception
    {
        // The untouched one has to be copied over verbatim, brackets, quotes and all
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, UUID);
        Assertions.assertEquals("select * from [sub:Answer] as a where a.value = '/kept/as/it/is'"
            + " and a.question = '" + UUID + "'",
            resolve("select * from [sub:Answer] as a where a.value = '/kept/as/it/is'"
                + " and a.question = '" + QUESTION_PATH + "'"));
    }

    @Test
    public void anApostropheInAPathIsUndoubledBeforeTheLookup() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode("/Schemas/Nurse's assessment", UUID);
        Assertions.assertEquals("select * from [sub:Answer] as a where a.question = '" + UUID + "'",
            resolve("select * from [sub:Answer] as a where a.question = '/Schemas/Nurse''s assessment'"));
    }

    @Test
    public void aPathWithNoNodeAtItIsLeftAlone() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        Assertions.assertEquals(BY_PATH, resolve(BY_PATH));
    }

    @Test
    public void aTargetThatCannotBeReferencedIsLeftAlone() throws Exception
    {
        // Oak answers getIdentifier() for such a node with its path, which no reference property holds
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        withNode(QUESTION_PATH, null);
        Assertions.assertEquals(BY_PATH, resolve(BY_PATH));
    }

    @Test
    public void aRepositoryFailureLeavesThePathAlone() throws Exception
    {
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        Mockito.when(this.session.nodeExists(QUESTION_PATH)).thenThrow(new RepositoryException("No"));
        Assertions.assertEquals(BY_PATH, resolve(BY_PATH));
    }

    @Test
    public void anUncheckedFailureLeavesThePathAlone() throws Exception
    {
        // A malformed path is the client's to send, and the repository may reject one without wrapping its complaint
        withType(ANSWER, QUESTION, PropertyType.REFERENCE);
        Mockito.when(this.session.nodeExists(QUESTION_PATH)).thenThrow(new IllegalArgumentException("Not a path"));
        Assertions.assertEquals(BY_PATH, resolve(BY_PATH));
    }

    private String resolve(final String statement) throws RepositoryException
    {
        return QueryPathResolver.resolveReferencePaths(this.session, statement);
    }

    /** Declares a node type holding a single property of the given required type. */
    private void withType(final String type, final String property, final int requiredType) throws RepositoryException
    {
        final PropertyDefinition definition = Mockito.mock(PropertyDefinition.class);
        Mockito.when(definition.getName()).thenReturn(property);
        Mockito.when(definition.getRequiredType()).thenReturn(requiredType);
        final NodeType nodeType = Mockito.mock(NodeType.class);
        Mockito.when(nodeType.getPropertyDefinitions()).thenReturn(new PropertyDefinition[] { definition });
        Mockito.when(this.nodeTypes.hasNodeType(type)).thenReturn(true);
        Mockito.when(this.nodeTypes.getNodeType(type)).thenReturn(nodeType);
    }

    /**
     * Puts a node at a path.
     *
     * @param path where the node is
     * @param uuid the identifier it reports, or {@code null} for a node that is not referenceable
     */
    private void withNode(final String path, final String uuid) throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.isNodeType(REFERENCEABLE)).thenReturn(uuid != null);
        Mockito.when(node.getIdentifier()).thenReturn(uuid);
        Mockito.when(this.session.nodeExists(path)).thenReturn(true);
        Mockito.when(this.session.getNode(path)).thenReturn(node);
    }
}
