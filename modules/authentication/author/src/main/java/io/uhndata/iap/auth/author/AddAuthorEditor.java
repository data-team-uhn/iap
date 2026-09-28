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
package io.uhndata.iap.auth.author;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.DefaultEditor;
import org.apache.jackrabbit.oak.spi.commit.Editor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * A commit editor responsible for adding an iap:lastAuthor field onto nodes.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class AddAuthorEditor extends DefaultEditor
{
    private final CommitInfo commitInfo;
    private final NodeBuilder node;

    AddAuthorEditor(final NodeBuilder node, final CommitInfo commitInfo)
    {
        this.node = node;
        this.commitInfo = commitInfo;
    }

    @Override
    public void enter(final NodeState before, final NodeState after)
    {
        String userId = this.commitInfo.getUserId();
        if (after.exists() && userId != null) {
            // Node was not deleted -- tack on user information
            this.node.setProperty("iap:lastAuthor", userId, Type.STRING);
        }
    }

    @Override
    public Editor childNodeAdded(final String name, final NodeState after)
    {
        return new AddAuthorEditor(this.node.getChildNode(name), this.commitInfo);
    }

    @Override
    public Editor childNodeChanged(final String name, final NodeState before, final NodeState after)
    {
        return new AddAuthorEditor(this.node.getChildNode(name), this.commitInfo);
    }
}
