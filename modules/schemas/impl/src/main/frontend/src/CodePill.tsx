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

import { Typography } from "@mui/material";

// A name the machine goes by, such as a part's identifier or the value an option stores, set apart from the words
// around it
function CodePill({ name }: { name: string }) {
  return (
    <Typography variant="code" sx={{ bgcolor: "background.muted", px: 0.75, borderRadius: 1, overflowWrap: "anywhere" }}>
      {name}
    </Typography>
  );
}

export default CodePill;
