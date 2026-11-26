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

import DifferenceOutlined from "@mui/icons-material/DifferenceOutlined";
import { Button } from "@mui/material";

// PRETOTYPE. Whether the review screen shows what changed since the submission was last sent back, on every part:
// documents compared with their version then, answers with what they were. Tinted while on, as the notes' filters
// are, rather than filled, being on most of the time.
export default function ChangesToggle({ since, on, onChange }: {
  // When it was last sent back, as said on the button
  since: string;
  on: boolean;
  onChange: (on: boolean) => void;
}) {
  return (
    <Button
      size="small" variant="outlined" aria-pressed={on} startIcon={<DifferenceOutlined />} onClick={() => onChange(!on)}
      sx={theme => {
        const tint = theme.alpha((theme.vars ?? theme).palette.primary.main, 0.08);
        return { flexShrink: 0, whiteSpace: "nowrap", bgcolor: "background.paper",
          ...on ? { color: "primary.main", borderColor: "primary.main", backgroundImage: `linear-gradient(${tint}, ${tint})` }
            : { color: "text.secondary", borderColor: "divider" } };
      }}
    >
      Changes since {since}
    </Button>
  );
}
