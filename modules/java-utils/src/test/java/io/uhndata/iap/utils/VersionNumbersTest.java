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
package io.uhndata.iap.utils;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link VersionNumbers}: a version is numbered past every number its siblings are named with.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class VersionNumbersTest
{
    private static final String VERSION = "test/Version";

    private static final String TYPE = "sling:resourceType";

    private final SlingContext context = new SlingContext();

    @Test
    void numbersTheFirstVersionOne()
    {
        assertEquals(1, VersionNumbers.next(this.parent(), VERSION));
    }

    @Test
    void numbersPastTheLargestNumberAVersionIsNamedWith()
    {
        final Resource parent = this.parent();
        this.context.create().resource("/parent/v1", TYPE, VERSION);
        this.context.create().resource("/parent/V4", TYPE, VERSION);
        this.context.create().resource("/parent/2.0", TYPE, VERSION);

        // The largest decides, so the numbers missing below it are never handed out again
        assertEquals(5, VersionNumbers.next(parent, VERSION));
    }

    @Test
    void readsNeitherLabelsNorAnythingButVersions()
    {
        final Resource parent = this.parent();
        this.context.create().resource("/parent/v1", TYPE, VERSION, "version", "2027");
        this.context.create().resource("/parent/importedByHand", TYPE, VERSION);
        this.context.create().resource("/parent/v9", TYPE, "test/Attachment");

        assertEquals(2, VersionNumbers.next(parent, VERSION));
    }

    @Test
    void namesAVersionAfterItsNumber()
    {
        final Resource parent = this.parent();

        assertEquals("v3", VersionNumbers.nodeName(parent, 3));
    }

    @Test
    void namesAVersionAroundWhateverTakesItsName()
    {
        final Resource parent = this.parent();
        this.context.create().resource("/parent/v3", TYPE, "test/Attachment");

        final String name = VersionNumbers.nodeName(parent, 3);

        assertNotEquals("v3", name);
        assertNull(parent.getChild(name));
    }

    @Test
    void labelsAVersionWithItsNumber()
    {
        assertEquals("3.0", VersionNumbers.defaultLabel(3));
    }

    private Resource parent()
    {
        return this.context.create().resource("/parent");
    }
}
