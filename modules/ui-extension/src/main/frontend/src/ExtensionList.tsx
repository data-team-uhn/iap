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

// An extension is the parsed JSON of one `iap:Extension`, with its `asset:` properties already
// resolved; its exact shape depends on the extension point, so it is an open string-keyed record.
type Extension = Record<string, unknown>;

// Renders each extension's `iap:extensionRender` component, in the order given, passing it the
// extension itself so node properties (`iap:data`, ...) are readable at runtime.
function ExtensionList({ extensions }: { extensions: Extension[] }) {
  return (
    <>
      {
        extensions.map((extension, index) => {
          const ExtensionContent = extension["iap:extensionRender"] as ComponentType<{ extension: Extension }>;
          return ExtensionContent ? <ExtensionContent extension={extension} key={"extension-" + index} /> : null;
        })
      }
    </>
  );
}

export { ExtensionList, type Extension };
