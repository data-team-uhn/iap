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
package io.uhndata.iap.workflows.internal.types;

import java.util.function.Consumer;

import javax.jcr.PropertyType;

import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStateUtils;

import io.uhndata.iap.utils.NodeTypeUtils;

/**
 * The absolute path of another node, optionally of the node type the parameter's {@code referenceType} names.
 *
 * <p>Stored as the path it names rather than as a JCR reference: what it names may be content this repository has
 * yet to be given. For the same reason a path naming nothing, or something of the wrong type, is reported and still
 * stored. A path that is wrong is easier to repair than configuration silently lost.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ReferenceParameterType implements ParameterType
{
    private final String referenceType;

    private final NodeState root;

    /**
     * The type of the values one reference parameter takes.
     *
     * @param referenceType the node type every value must name a node of, as the declaration's {@code referenceType}
     *            gives it, or {@code null} when any type will do
     * @param root the committed repository root, which the values are looked up in
     */
    public ReferenceParameterType(final String referenceType, final NodeState root)
    {
        this.referenceType = referenceType;
        this.root = root;
    }

    @Override
    public int storageType()
    {
        return PropertyType.STRING;
    }

    @Override
    public Object convert(final String raw, final Consumer<String> report)
    {
        final NodeState target = PathUtils.isValid(raw) && PathUtils.isAbsolute(raw)
            ? NodeStateUtils.getNode(this.root, raw) : null;
        if (target == null || !target.exists()) {
            report.accept("a reference parameter names a node that does not exist");
        } else if (this.referenceType != null
            && !NodeTypeUtils.isOfType(target, this.referenceType, NodeTypeUtils.registry(this.root))) {
            report.accept("a reference parameter names a node of the wrong type");
        }
        return raw;
    }
}
