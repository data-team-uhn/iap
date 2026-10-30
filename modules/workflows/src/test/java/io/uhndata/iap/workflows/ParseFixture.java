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
package io.uhndata.iap.workflows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Parses a process written inline in a test, against a vocabulary the test built.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ParseFixture
{
    /** The id of the service task {@link #serviceTask(String)} writes. */
    public static final String TASK = "task1";

    private static final String DEFS_OPEN =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
        + " xmlns:iap=\"https://iap.uhndata.io/bpmn\" id=\"defs1\">\n"
        + "  <bpmn:process id=\"process1\">\n";

    private static final String DEFS_CLOSE = "  </bpmn:process>\n</bpmn:definitions>\n";

    private ParseFixture()
    {
        // Utility class, no instances allowed
    }

    /**
     * A service task with id {@link #TASK}.
     *
     * @param extensions what its {@code extensionElements} holds
     * @return the element's XML
     */
    public static String serviceTask(final String extensions)
    {
        return serviceTask(TASK, extensions);
    }

    /**
     * A service task.
     *
     * @param id its id, which is also the name of the node it is parsed into
     * @param extensions what its {@code extensionElements} holds
     * @return the element's XML
     */
    public static String serviceTask(final String id, final String extensions)
    {
        return "    <bpmn:serviceTask id=\"" + id + "\">\n"
            + "      <bpmn:extensionElements>\n"
            + "        " + extensions + "\n"
            + "      </bpmn:extensionElements>\n"
            + "    </bpmn:serviceTask>\n";
    }

    /**
     * Parses a process holding the given elements.
     *
     * @param root the repository root, holding the vocabulary and the node type registry
     * @param elements the process's contents
     * @return the version the flow nodes were written into
     * @throws Exception if the parse fails outright
     */
    public static NodeState parse(final NodeBuilder root, final String elements) throws Exception
    {
        final NodeBuilder version = EmptyNodeState.EMPTY_NODE.builder();
        final String xml = DEFS_OPEN + elements + DEFS_CLOSE;
        WorkflowDefinitionUtils.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), version,
            root.getNodeState(), "admin", "/Workflows/Approval/1.0");
        return version.getNodeState();
    }
}
