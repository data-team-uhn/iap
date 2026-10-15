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
package io.uhndata.iap.submissions.spi;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the empty parts of a submission for one kind of item its schema asks for. Every item that applies has a
 * part of its own, empty until its author fills it in, so that an item nobody has started still has a node to
 * judge and to mark.
 *
 * <p>Implementations are OSGi services. The {@code createParts} service task asks every one of them after each
 * change to a submission, so an item that starts to apply gets its part. A creator only adds what is missing, and
 * never removes a part.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface PartCreator
{
    /**
     * Creates the parts missing for the items of this creator's kind that apply to a submission.
     *
     * @param submission the submission, through the session that is writing it
     * @throws PersistenceException when a part cannot be created
     */
    void createMissing(@NotNull Resource submission) throws PersistenceException;
}
