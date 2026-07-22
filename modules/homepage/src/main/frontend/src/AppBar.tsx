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

// The application bar: a toolbar row composed entirely of extensions registered on the
// `iap/appBar/entry` extension point, so that any module can contribute controls (branding,
// notifications, user menu, search, ...) without this component knowing about them. The bar
// itself is a `frameTop` extension, so it stays pinned to the top of the screen.
//
// An entry declares where it sits through `iap:appBarSection` — `start`, `middle`, or `end`
// (logical inline directions, so the whole row mirrors under a right-to-left locale) — and is
// ordered within its section by `iap:defaultOrder`. Entries render inline in the row; an entry
// that needs more room (a search field, ...) can flex-grow itself.
function AppBar() {
  const [ entries, setEntries ] = useState<Extension[]>([]);

  useEffect(() => {
    loadExtensions("AppBarEntry")
      .then(extensions => setEntries(extensions))
      .catch(err => console.error("Something went wrong loading the app bar entries", err));
  }, []);

  const section = (name: string) =>
    entries.filter(entry => (entry["iap:appBarSection"] ?? "start") === name);

  return (
    <Toolbar
      variant="dense"
      sx={{
        gap: 1,
        bgcolor: "background.muted",
        // The dense 48px bar suits narrow screens; from sm up, open to the standard 64px
        minHeight: { sm: 64 },
      }}
    >
      <ExtensionList extensions={section("start")} />
      { /* The spacers keep the middle section centered and push the end section to the far edge */ }
      <Box sx={{ flexGrow: 1 }} />
      <ExtensionList extensions={section("middle")} />
      <Box sx={{ flexGrow: 1 }} />
      <ExtensionList extensions={section("end")} />
    </Toolbar>
  );
}

export default AppBar;
