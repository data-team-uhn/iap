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
package io.uhndata.iap.workflows.internal;

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.commit.EditorProvider;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.osgi.service.component.annotations.Component;

/**
 * Provides a {@link BpmnXmlSyncEditor} for the commits that touch a homepage holding workflow definitions
 * — {@code /Workflows}, {@code /SystemWorkflows}, or any other a deployment adds.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class BpmnXmlSyncEditorProvider implements EditorProvider
{
    @Override
    public Editor getRootEditor(final NodeState before, final NodeState after, final NodeBuilder builder,
        final CommitInfo info)
    {
        // The roots this editor needs are passed in as the committed state to look them up from.
        // Oak only descends into children that actually changed, so a commit touching no workflow homepage
        // never asks.
        return new BpmnXmlSyncEditor(builder, after, info.getUserId());
    }
}
