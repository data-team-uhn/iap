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

import { type ComponentType } from "react";

// One `ext:Extension`, as the server serializes it. Each extension point gives its extensions
// properties of its own. Once `loadExtensions` has loaded them, the asset each `asset:` property names
// sits under that property's name without a final `URL`.
type Extension = Record<string, unknown>;

// Each extension's `ext:render` component, in the order given. A component gets its extension as the
// `extension` prop, to read properties such as `ext:data`. An extension with no `ext:render` shows
// nothing.
function ExtensionList({ extensions }: { extensions: Extension[] }) {
  return (
    <>
      {
        extensions.map((extension, index) => {
          const ExtensionContent = extension["ext:render"] as ComponentType<{ extension: Extension }> | undefined;
          return ExtensionContent ? <ExtensionContent extension={extension} key={"extension-" + index} /> : null;
        })
      }
    </>
  );
}

export { ExtensionList, type Extension };
