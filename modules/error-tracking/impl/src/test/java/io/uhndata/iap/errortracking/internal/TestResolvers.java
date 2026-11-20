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
package io.uhndata.iap.errortracking.internal;

import java.lang.reflect.Field;

import org.apache.sling.api.resource.ResourceResolverFactory;

/**
 * Wires the components under test by hand. The SCR metadata that would let osgi-mock inject their references exists
 * only once the bundle is packaged.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class TestResolvers
{
    /** Only static methods, no instances. */
    private TestResolvers()
    {
        // Utility class
    }

    /**
     * Injects a resolver factory into a component's {@code resolverFactory} field.
     *
     * @param component the component under test
     * @param factory logs the component's service user in
     * @throws ReflectiveOperationException if the component has no such field
     */
    public static void inject(final Object component, final ResourceResolverFactory factory)
        throws ReflectiveOperationException
    {
        set(component, "resolverFactory", factory);
    }

    /**
     * Sets one of a component's fields directly.
     *
     * @param component the component under test
     * @param name the field to set
     * @param value what to set it to
     * @throws ReflectiveOperationException if the component has no such field
     */
    public static void set(final Object component, final String name, final Object value)
        throws ReflectiveOperationException
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(component, value);
    }

    /**
     * Reads one of a component's fields directly, such as a collaborator the component made for itself.
     *
     * @param component the component under test
     * @param name the field to read
     * @return whatever is in it
     * @throws ReflectiveOperationException if the component has no such field
     */
    public static Object get(final Object component, final String name)
        throws ReflectiveOperationException
    {
        final Field field = component.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(component);
    }
}
