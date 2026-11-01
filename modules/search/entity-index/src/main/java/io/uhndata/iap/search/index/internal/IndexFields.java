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
package io.uhndata.iap.search.index.internal;

import javax.jcr.PropertyType;

/**
 * How a node is laid out as an index document. A property's values are kept under a field named after the property
 * and the JCR type they are stored in, such as {@code value|Long}, so that a condition can compare each value the way
 * a JCR query does: as its own type when the value compared against converts to it, and as text when it does not.
 * The separator cannot occur in a JCR name, and the document's own fields start with a colon, which no JCR name does.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class IndexFields
{
    /** The path of the entity a document belongs to, on the entity's own document and on each descendant's. */
    static final String ENTITY_FIELD = ":entity";

    /** The path of the node a document describes, stored so that a search can return it. */
    static final String PATH_FIELD = ":path";

    /** Which of {@link #ENTITY_KIND} or {@link #DESCENDANT_KIND} a document describes. */
    static final String KIND_FIELD = ":kind";

    /** The {@link #KIND_FIELD} of an entity's own document, which ends its block. */
    static final String ENTITY_KIND = "entity";

    /** The {@link #KIND_FIELD} of a descendant's document, which comes before its entity's in the block. */
    static final String DESCENDANT_KIND = "descendant";

    /** Every node type the node is of: its primary type, its mixins, and all their supertypes. */
    static final String TYPES_FIELD = ":types";

    /** The names of the properties the node has, so that a condition can ask whether one is set. */
    static final String PROPERTIES_FIELD = ":properties";

    /** The names of the properties whose text is longer than the index keeps, so it is matched only in part. */
    static final String TRUNCATED_FIELD = ":truncated";

    /** The {@link #typed typed fields} the node has values in, which says what types a property is stored in. */
    static final String TYPED_FIELD = ":typed";

    /** The names of the node's multi-valued properties. */
    static final String MULTIPLE_FIELD = ":multiple";

    private static final char TYPE_SEPARATOR = '|';

    private static final String LOWERCASE_SUFFIX = "lowercase";

    private static final String NUMBER_SUFFIX = "number";

    private IndexFields()
    {
        // Utility class
    }

    /**
     * The field holding the text of a property's values of one JCR type, which is what they are compared as when the
     * value compared against does not convert to their type.
     *
     * @param property the property's name
     * @param type the JCR type of the values, one of the {@link PropertyType} constants
     * @return the field's name, such as {@code value|Long}
     */
    static String typed(final String property, final int type)
    {
        return property + TYPE_SEPARATOR + PropertyType.nameFromValue(type);
    }

    /**
     * The field holding the whole numbers, numbers or dates of a typed field as points, compared and ordered as such.
     *
     * @param typedField a field named by {@link #typed}, for a property of type {@code LONG}, {@code DOUBLE} or
     *            {@code DATE}
     * @return the field's name, such as {@code value|Long|number}
     */
    static String number(final String typedField)
    {
        return typedField + TYPE_SEPARATOR + NUMBER_SUFFIX;
    }

    /**
     * The field holding a property's text values in lower case, which a case-insensitive match compares against.
     *
     * @param property the property's name
     * @return the field's name, such as {@code title|lowercase}
     */
    static String lowercase(final String property)
    {
        return property + TYPE_SEPARATOR + LOWERCASE_SUFFIX;
    }
}
