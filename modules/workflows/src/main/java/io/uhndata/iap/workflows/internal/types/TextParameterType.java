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

import org.apache.jackrabbit.oak.spi.state.NodeState;

/**
 * Free text, stored as written.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TextParameterType implements ParameterType
{
    @Override
    public int storageType()
    {
        return PropertyType.STRING;
    }

    @Override
    public Object convert(final String raw, final NodeState declaration, final NodeState root,
        final Consumer<String> report)
    {
        return raw;
    }
}
