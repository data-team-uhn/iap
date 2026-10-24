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
import { useId, useState } from "react";

import ArrowDropDownIcon from "@mui/icons-material/ArrowDropDown";
import CheckIcon from "@mui/icons-material/Check";
import {
  Box,
  Button,
  Divider,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Typography,
} from "@mui/material";

import { countChanges } from "./comparisonText";
import { CHANGES, describeAmong } from "./pdfViewerModel";
import StepControls from "./StepControls";
import { TOUCH_TARGET } from "../touchTarget";

import type { RevisionChange } from "./revisionDiffModel";
import type { ComparisonView } from "./useComparison";

const VIEWS: { view: ComparisonView; label: string }[] = [
  { view: "off", label: "Off" },
  { view: "unified", label: "On the newer version" },
  { view: "split", label: "Side by side" },
];

interface ChangesMenuProps {
  // The view chosen, and the one shown, which a narrow viewer keeps from being side by side.
  view: ComparisonView;
  shownView: ComparisonView;
  onViewChange: (view: ComparisonView) => void;
  strict: boolean;
  onStrictChange: (strict: boolean) => void;
  // How many minor changes there are, and how many changes are shown, once known.
  minor: number | undefined;
  count: number | undefined;
  // A narrow viewer has no room side by side.
  narrow: boolean;
}

// A menu item's lines wrap on a narrow screen, rather than running out of it.
const WRAPPING = { whiteSpace: "normal" };

// Whether and how changes are shown, chosen from a menu, which also has minor changes included or not. Its
// label counts the changes shown, once there are some. The menu stays open as minor changes are included
// or left out.
function ChangesMenu(props: ChangesMenuProps) {
  const { view, shownView, onViewChange, strict, onStrictChange, minor, count, narrow } = props;
  const [menuAt, setMenuAt] = useState<HTMLElement | null>(null);
  const labelId = useId();
  const off = view === "off";
  const shownLabel = VIEWS.find(each => each.view === shownView)?.label ?? "";
  const open = (event: { currentTarget: HTMLElement }) => setMenuAt(event.currentTarget);

  return (
    <>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Typography id={labelId} variant="body2" noWrap>
          {view === "off" || count === undefined ? "Show changes" : `Show ${countChanges(count)}`}
        </Typography>
        <Button
          size="small"
          variant="outlined"
          color="inherit"
          endIcon={<ArrowDropDownIcon />}
          aria-labelledby={`${labelId} ${labelId}-value`}
          aria-haspopup="menu"
          aria-expanded={menuAt !== null}
          aria-controls={menuAt === null ? undefined : `${labelId}-menu`}
          onClick={open}
          sx={TOUCH_TARGET}
        >
          <span id={`${labelId}-value`}>{shownLabel}</span>
        </Button>
      </Box>
      <Menu
        id={`${labelId}-menu`}
        anchorEl={menuAt}
        open={menuAt !== null}
        onClose={() => setMenuAt(null)}
        // The keyboard reaches a choice there is no room for, to hear why.
        slotProps={{ list: { disabledItemsFocusable: true, "aria-labelledby": labelId } }}
      >
        {VIEWS.map(({ view: each, label }) => {
          const unavailable = each === "split" && narrow;
          return (
            <MenuItem
              key={each}
              role="menuitemradio"
              aria-checked={shownView === each}
              // The menu opens on the choice made
              selected={shownView === each}
              disabled={unavailable}
              sx={WRAPPING}
              onClick={() => {
                onViewChange(each);
                setMenuAt(null);
              }}
            >
              <ListItemIcon>{shownView === each ? <CheckIcon /> : null}</ListItemIcon>
              <ListItemText primary={label} secondary={unavailable ? "Needs more room" : undefined} />
            </MenuItem>
          );
        })}
        <Divider />
        <MenuItem
          role="menuitemcheckbox"
          aria-checked={strict}
          disabled={off || minor === 0}
          onClick={() => onStrictChange(!strict)}
          sx={WRAPPING}
        >
          <ListItemIcon>{strict ? <CheckIcon /> : null}</ListItemIcon>
          <ListItemText primary={describeMinorOption(minor)} secondary="Of punctuation or letter case" />
        </MenuItem>
      </Menu>
    </>
  );
}

function describeMinorOption(minor: number | undefined): string {
  if (minor === undefined) {
    return "Include minor changes";
  }
  if (minor === 0) {
    return "No minor changes";
  }
  return `Include ${minor} minor ${minor === 1 ? "change" : "changes"}`;
}

// The row above a document compared with an older version: the menu choosing how its changes are shown,
// side by side the controls stepping through them, and a caption saying how the comparison goes or what it
// leaves out. The caption follows on the same row where there is room, and goes under the menu where there
// is not.
export default function ComparisonControls(props: ChangesMenuProps & {
  // The changes to step through side by side, if any, and which is selected, -1 for none.
  changes: RevisionChange[] | undefined;
  selectedIndex: number;
  onShowChange: (id: string) => void;
  caption: string;
}) {
  const { changes, selectedIndex, onShowChange, caption, ...menu } = props;
  const count = changes?.length ?? 0;
  return (
    <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 2, minHeight: 40 }}>
      <ChangesMenu {...menu} />
      {changes === undefined || count === 0
        ? null
        : (
          <StepControls
            position={selectedIndex + 1}
            count={count}
            itemName="change"
            heading={selectedIndex < 0
              ? countChanges(count)
              : describeAmong(CHANGES, { index: selectedIndex + 1, count })}
            widest={[ countChanges(count), describeAmong(CHANGES, { index: count, count }) ]}
            onStep={next => onShowChange(changes[next - 1].id)}
          />
        )}
      {caption === "" ? null : <Typography variant="description">{caption}</Typography>}
    </Box>
  );
}
