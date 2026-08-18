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
package io.uhndata.iap.deletion.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Who, if anybody, may destroy a resource irreversibly, whether outright or out of the archive.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Permanent deletion policy",
    description = "Whether resources may be destroyed irreversibly - outright, or out of the archive - and who may "
        + "do it.")
public @interface PermanentDeletionConfiguration
{
    /**
     * Whether irreversible deletion is banned for everyone outside the allowlist.
     *
     * @return {@code true} to ban it, {@code false}, the default, to leave it to access control alone
     */
    @AttributeDefinition(name = "Prevent permanent deletion",
        description = "Refuse the two deletions that leave nothing to restore: permanent deletion, which never "
            + "reaches the archive, and purging, which removes what is already in it. Guarding only the first would "
            + "leave the ban defeatable by archiving and then purging. Off by default, leaving the decision to "
            + "access control alone. Archiving is unaffected: a user who is refused here can still delete the "
            + "resource in the ordinary, recoverable way.")
    boolean preventPermanentDeletion() default false;

    /**
     * The principals exempt from the ban.
     *
     * @return user ids and principal names, empty to exempt nobody
     */
    @AttributeDefinition(name = "Allowed principals",
        description = "User ids and principal names exempt from the ban. Group principals work as well as user "
            + "ones, and identity-provider roles as well as local groups, since a principal name is checked without "
            + "asking where the principal came from. Empty, the default, exempts nobody: with the ban on, "
            + "irreversible deletion is then refused to everyone. This list only ever lifts the ban above - it "
            + "grants no rights of its own, so a member still needs the access rights the deletion itself requires.")
    String[] allowedPrincipals() default {};
}
