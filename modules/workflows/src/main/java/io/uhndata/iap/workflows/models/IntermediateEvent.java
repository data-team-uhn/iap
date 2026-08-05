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
package io.uhndata.iap.workflows.models;

/**
 * The abstract base shared by the events happening in the middle of a workflow, once it has started and before it
 * has finished: an {@link IntermediateCatchingEvent} or an {@link IntermediateThrowingEvent}. Corresponds to the
 * {@code wf:IntermediateEvent} node type. Like the other bases here, it is not itself a registered Sling Model.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class IntermediateEvent extends Event
{
    /** The {@code sling:resourceType} of a {@code wf:IntermediateEvent} node. */
    public static final String RESOURCE_TYPE = "wf/IntermediateEvent";
}
