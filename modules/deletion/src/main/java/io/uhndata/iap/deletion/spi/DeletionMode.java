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
package io.uhndata.iap.deletion.spi;

/**
 * The kind of deletion a {@link DeletionVeto} is asked about.
 *
 * @version $Id$
 * @since 0.1.0
 */
public enum DeletionMode
{
    /** The resource would be moved into the archive, from where it could still be restored. */
    ARCHIVE,

    /** The resource would be removed from the repository for good. */
    PERMANENT,

    /** The resource is already archived, and the archive entry holding it would be removed for good. */
    PURGE
}
