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
package io.uhndata.iap.submissions.internal.describers;

/**
 * The keys of the form's JSON that more than one of the classes writing it uses.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class FormKeys
{
    /** An item's node name. */
    public static final String NAME_KEY = "name";

    /** A path: a requirement's or a question's, or where an attachment downloads from. */
    public static final String PATH_KEY = "path";

    /** A submission's or an attachment's title. */
    public static final String TITLE_KEY = "title";

    /** A requirement's or a section's label. */
    public static final String LABEL_KEY = "label";

    /** A requirement's, a section's or a question's description. */
    public static final String DESCRIPTION_KEY = "description";

    /** An item's resource type, which is how a reader tells the kinds apart. */
    public static final String TYPE_KEY = "type";

    private FormKeys()
    {
        // Constants only
    }
}
