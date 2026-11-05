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
package io.uhndata.iap.submissions.internal.transfer;

import java.util.Set;

/**
 * The manifest a submission travels between instances as: a JSON tree of its nodes, the binaries sent beside it.
 *
 * {@snippet :
 * {"version": 1, "root": NODE}
 * NODE     = {"name", "type", "mixins": [..], "created"?, "properties": {name: PROPERTY}, "children": [NODE]}
 * PROPERTY = {"type", "value" | "values", "email"?}
 * }
 *
 * <p>
 * A value is the JCR string form of its type, except for three kinds. A reference is the path of what it points at:
 * relative to the submission when it points inside it ({@value #SUBMISSION_ITSELF} for the submission itself),
 * absolute when it points elsewhere, since what lies outside has the same path on every instance but not the same
 * identifier. A binary is the name of the part that carries it. A user is their email when {@code "email": true},
 * since an id means nothing on another instance, and their id otherwise.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class TransferFormat
{
    /** The format this code writes and reads. A change that an older reader would misread needs a new number. */
    static final int FORMAT_VERSION = 1;

    static final String VERSION_KEY = "version";

    static final String ROOT_KEY = "root";

    static final String NAME_KEY = "name";

    static final String TYPE_KEY = "type";

    static final String MIXINS_KEY = "mixins";

    /** When the node was created on the instance it comes from. */
    static final String CREATED_KEY = "created";

    static final String PROPERTIES_KEY = "properties";

    static final String CHILDREN_KEY = "children";

    static final String VALUE_KEY = "value";

    static final String VALUES_KEY = "values";

    /** Marks a user's value as their email rather than their id. */
    static final String EMAIL_KEY = "email";

    /** How a reference names the submission itself. */
    static final String SUBMISSION_ITSELF = ".";

    /** Where a node keeps the date it was created on the instance it comes from. */
    static final String ORIGINAL_CREATED_PROPERTY = "originalCreated";

    /** Who the engine created a node for. */
    static final String CREATED_BY_PROPERTY = "createdBy";

    /** Who the repository saw write a node: the person only where the engine was not involved. */
    static final String WRITTEN_BY_PROPERTY = "jcr:createdBy";

    /** The properties that name a user. */
    static final Set<String> USER_PROPERTIES =
        Set.of(CREATED_BY_PROPERTY, "author", "reviewer", "checkedBy", "jcr:lastModifiedBy");

    private TransferFormat()
    {
        // Constants only
    }
}
