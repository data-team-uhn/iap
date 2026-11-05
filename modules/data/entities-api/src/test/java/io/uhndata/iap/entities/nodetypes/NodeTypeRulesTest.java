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
package io.uhndata.iap.entities.nodetypes;

import java.io.StringReader;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NodeTypeRules#isComputedProperty}, the one rule the modification stamps do not use. The tests
 * of {@code LastModifiedEditor} cover the others.
 *
 * @version $Id$
 * @since 0.1.0
 */
class NodeTypeRulesTest
{
    private static final String TYPES = String.join("\n",
        "<test = 'https://iap.test/test'>",
        "[test:Thing] > nt:base",
        "  - kept (STRING)",
        "  - ignored (STRING) IGNORE",
        "  - derived (STRING) multiple COMPUTE");

    private final MemoryNodeStore store = new MemoryNodeStore();

    private Session session;

    private NodeState thing;

    private NodeTypeRules rules;

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = new Jcr(new Oak(this.store)).createRepository()
            .login(new SimpleCredentials("admin", "admin".toCharArray()));
        CndImporter.registerNodeTypes(new StringReader(TYPES), this.session);
        final Node node = this.session.getRootNode().addNode("thing", "test:Thing");
        node.setProperty("kept", "k");
        node.setProperty("ignored", "i");
        node.setProperty("derived", new String[] {"d"});
        this.session.save();
        final NodeState root = this.store.getRoot();
        this.thing = root.getChildNode("thing");
        this.rules = new NodeTypeRules(root);
    }

    @AfterEach
    void tearDown()
    {
        this.session.logout();
    }

    @Test
    void recognisesADerivedProperty()
    {
        assertTrue(this.rules.isComputedProperty(this.thing, this.thing.getProperty("derived")));
    }

    @Test
    void doesNotTakeAPropertyAVersionMerelyIgnoresForADerivedOne()
    {
        assertFalse(this.rules.isComputedProperty(this.thing, this.thing.getProperty("ignored")));
        assertTrue(this.rules.isUnversionedProperty(this.thing, this.thing.getProperty("ignored")));
    }

    @Test
    void doesNotTakeContentForADerivedProperty()
    {
        assertFalse(this.rules.isComputedProperty(this.thing, this.thing.getProperty("kept")));
    }
}
