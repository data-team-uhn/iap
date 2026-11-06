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

import { useEffect, useState } from "react";

import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// The `iap/coreUI/frameTop` extensions marked with `ext:visibleBeforeLogin: true`, such as a
// maintenance notice that must reach users before they sign in. The others, such as the app
// bar, need a session, and are not shown.
export default function PreLoginExtensions() {
  const [ extensions, setExtensions ] = useState<Extension[]>([]);

  useEffect(() => {
    loadExtensions("FrameTop")
      .then(list => setExtensions(list.filter(extension => extension["ext:visibleBeforeLogin"] === true)))
      .catch((err: unknown) => console.error("Something went wrong loading the pre-login extensions", err));
  }, []);

  return <ExtensionList extensions={extensions} />;
}
