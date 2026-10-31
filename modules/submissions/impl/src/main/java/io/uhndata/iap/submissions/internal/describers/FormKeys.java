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
 * The keys shared by the objects of the form's JSON.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class FormKeys
{
    /** A node name. */
    public static final String NAME_KEY = "name";

    /** An absolute path, or for a question its path relative to the schema version. */
    public static final String PATH_KEY = "path";

    /** A title to display. */
    public static final String TITLE_KEY = "title";

    /** A label to display. */
    public static final String LABEL_KEY = "label";

    /** A description to display; empty when there is none. */
    public static final String DESCRIPTION_KEY = "description";

    /** A resource type, which is how a reader tells the kinds of objects apart. */
    public static final String TYPE_KEY = "type";

    private FormKeys()
    {
        // Constants only
    }
}
