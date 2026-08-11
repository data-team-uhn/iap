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
package io.uhndata.iap.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * How the build enters {@link InitialContentCheck}, in the {@code verify} phase of every module build.
 *
 * <p>Kept apart from the check itself so that the check stays something one can call and test, and so that the one
 * class in this repository carrying a {@code main} says so in its name.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class InitialContentCheckMain
{
    private InitialContentCheckMain()
    {
        // Not instantiable: an entry point
    }

    /**
     * Checks a source tree's initial content, failing the build if the content loader could not read it.
     *
     * @param args the tree to check, defaulting to the working directory
     * @throws IOException if the tree cannot be walked
     * @throws IllegalStateException if any file cannot be read, or if there is no content to check at all
     */
    public static void main(final String[] args) throws IOException
    {
        final List<String> unreadable = InitialContentCheck.run(Path.of(args.length > 0 ? args[0] : "."));
        if (!unreadable.isEmpty()) {
            // Thrown rather than printed: this has to stop the build, and the message is the report
            throw new IllegalStateException("The content loader cannot read:\n" + String.join("\n", unreadable));
        }
    }
}
