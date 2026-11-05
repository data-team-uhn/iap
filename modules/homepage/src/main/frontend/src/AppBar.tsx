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

import { Box, Toolbar } from "@mui/material";

import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// The application bar: a row of the extensions on the `iap/appBar/entry` point.
//
// An entry's `ext:appBarSection` puts it in the `start`, `middle` or `end` section of the row. An
// entry without one is at the start, and an entry naming any other section is not shown. Within a
// section, entries follow their `defaultOrder`. An entry that needs more room can grow itself.
function AppBar() {
  const [ entries, setEntries ] = useState<Extension[]>([]);

  useEffect(() => {
    loadExtensions("AppBarEntry")
      .then(extensions => setEntries(extensions))
      .catch((err: unknown) => console.error("Something went wrong loading the app bar entries", err));
  }, []);

  const section = (name: string) =>
    entries.filter(entry => (entry["ext:appBarSection"] ?? "start") === name);

  return (
    <Toolbar
      variant="dense"
      sx={{
        gap: 1,
        bgcolor: "background.tinted",
        // 48px high below `sm`, as a dense toolbar is, and 64px from `sm` up
        minHeight: { sm: 64 },
      }}
    >
      <ExtensionList extensions={section("start")} />
      { /* The spacers share the free space, so that the middle section is centered between the
           other two, and the end section is at the far edge */ }
      <Box sx={{ flexGrow: 1 }} />
      <ExtensionList extensions={section("middle")} />
      <Box sx={{ flexGrow: 1 }} />
      <ExtensionList extensions={section("end")} />
    </Toolbar>
  );
}

export default AppBar;
