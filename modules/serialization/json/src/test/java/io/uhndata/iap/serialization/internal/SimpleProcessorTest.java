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

package io.uhndata.iap.serialization.internal;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimpleProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SimpleProcessorTest
{
    private final SimpleProcessor processor = new SimpleProcessor();

    @Test
    public void testMetadata()
    {
        Assertions.assertEquals("simple", this.processor.getName());
        Assertions.assertEquals(25, this.processor.getPriority());
        // Trimming the serialization is never assumed, only asked for
        Assertions.assertFalse(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void testContentPropertiesAreKept()
    {
        assertKept("label", "description", "retired", "schemaVersion", "version", "active");
    }

    @Test
    public void testIdentifyingJcrPropertiesAreKept()
    {
        assertKept("jcr:primaryType", "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:lastModified",
            "jcr:lastModifiedBy");
    }

    @Test
    public void testVersioningBookkeepingIsDropped()
    {
        // What every mix:versionable node repeats, and what a reader of the content never asked for
        assertDropped("jcr:mixinTypes", "jcr:versionHistory", "jcr:baseVersion", "jcr:predecessors",
            "jcr:isCheckedOut");
    }

    @Test
    public void testSlingPropertiesAreDropped()
    {
        assertDropped("sling:resourceType", "sling:resourceSuperType");
    }

    @Test
    public void testAPropertyAlreadyDroppedStaysDropped()
    {
        Assertions.assertNull(this.processor.processPropertyName(null, null, null));
    }

    private void assertKept(final String... names)
    {
        Stream.of(names).forEach(name -> Assertions.assertEquals(name,
            this.processor.processPropertyName(null, null, name), name));
    }

    private void assertDropped(final String... names)
    {
        Stream.of(names).forEach(name -> Assertions.assertNull(
            this.processor.processPropertyName(null, null, name), name));
    }
}
