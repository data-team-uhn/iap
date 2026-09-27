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

import { useMemo, useState } from "react";

import AltRouteOutlinedIcon from "@mui/icons-material/AltRouteOutlined";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import KeyboardArrowRightIcon from "@mui/icons-material/KeyboardArrowRight";
import { Box, Chip, Collapse, IconButton, Popover, Stack, Tooltip, Typography } from "@mui/material";

import { whenApplies } from "./conditionModel";
import { type JcrNode, pathOf } from "./schemaModel";
import SchemaNodeEditAction, { ReloadTree } from "./SchemaNodeEditAction";
import { type SchemaPartChip, schemaPartTypeOf } from "./schemaPartTypes";
import {
  conditionOf, detailOf, headingOf, indexQuestions, resourceTypeOf, partsOf, type QuestionIndex,
} from "./schemaVersionTreeModel";

// One of a part's chips; a chip with content shows it in a popover when clicked
function FactChip({ chip }: { chip: SchemaPartChip }) {
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  if (typeof chip === "string") {
    return <Chip label={chip} size="small" variant="outlined" />;
  }
  return (
    <>
      <Chip
        label={chip.label}
        size="small"
        variant="outlined"
        aria-haspopup="dialog"
        onClick={event => setAnchor(event.currentTarget)}
      />
      <Popover
        open={anchor !== null}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
      >
        <Box sx={{ p: 1.5, maxWidth: 360 }}>{chip.content}</Box>
      </Popover>
    </>
  );
}

// One requirement, section or question, with what it contains nested inside. Containers start open and
// questions closed, so the outline of a version reads first and the detail is a click away. When it
// applies stays in view either way: it is what the outline is made of.
function PartCard({ part, index }: { part: JcrNode; index: QuestionIndex }) {
  const type = schemaPartTypeOf(resourceTypeOf(part));
  const children = partsOf(part);
  const condition = conditionOf(part);
  const when = condition && whenApplies(condition, index);
  const description = detailOf(part, "description");
  const details = type.details?.(part) ?? null;
  const [ open, setOpen ] = useState(children.length > 0);
  const { Icon } = type;
  const hasMore = Boolean(description) || details !== null || children.length > 0;

  return (
    <Box
      component="li"
      sx={{
        listStyle: "none",
        mb: 1,
        bgcolor: "background.paper",
        border: 1,
        borderColor: "divider",
        borderLeft: 4,
        borderLeftColor: type.accent,
        borderRadius: 1,
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: "flex-start", p: 1 }}>
        { hasMore ? (
          <IconButton
            size="small"
            aria-label={`${open ? "Collapse" : "Expand"} ${headingOf(part)}`}
            aria-expanded={open}
            onClick={() => setOpen(current => !current)}
          >
            { open ? <ExpandMoreIcon fontSize="small" /> : <KeyboardArrowRightIcon fontSize="small" /> }
          </IconButton>
        ) : <Box sx={{ width: 30, flexShrink: 0 }} /> }
        <Tooltip title={type.label}>
          <Icon fontSize="small" titleAccess={type.label} sx={{ color: type.iconColor ?? type.accent, mt: 0.75 }} />
        </Tooltip>
        <Stack spacing={0.5} sx={{ flex: 1, minWidth: 0, pt: 0.5 }}>
          <Typography sx={{ overflowWrap: "anywhere" }}>{headingOf(part)}</Typography>
          { type.chips && (
            <Stack direction="row" useFlexGap spacing={0.5} sx={{ flexWrap: "wrap" }}>
              { type.chips(part).map(chip => (
                <FactChip key={typeof chip === "string" ? chip : chip.label} chip={chip} />
              )) }
            </Stack>
          ) }
          { when && (
            <Stack
              direction="row"
              spacing={0.75}
              sx={{ alignItems: "flex-start", alignSelf: "flex-start", bgcolor: "background.muted", borderRadius: 1,
                px: 1, py: 0.5 }}
            >
              <AltRouteOutlinedIcon fontSize="small" sx={{ color: "text.secondary", mt: 0.25 }} />
              <Typography variant="body2">{when}</Typography>
            </Stack>
          ) }
        </Stack>
        <SchemaNodeEditAction node={part} title={`Edit ${type.label.toLowerCase()}`} />
      </Stack>
      <Collapse in={open && hasMore} unmountOnExit>
        <Stack spacing={1} sx={{ pl: { xs: 1, sm: 5 }, pr: 1, pb: 1 }}>
          { description && <Typography variant="description">{description}</Typography> }
          {details}
          { children.length > 0 && <PartList parts={children} index={index} /> }
        </Stack>
      </Collapse>
    </Box>
  );
}

function PartList({ parts, index }: { parts: JcrNode[]; index: QuestionIndex }) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0 }}>
      { parts.map(part => <PartCard key={pathOf(part)} part={part} index={index} />) }
    </Box>
  );
}

// Everything a version asks of a submission, in the order it asks it.
function SchemaVersionTree({ version, reload }: { version: JcrNode; reload: () => void }) {
  const index = useMemo(() => indexQuestions(version), [ version ]);
  const parts = partsOf(version);
  if (parts.length === 0) {
    return <Typography variant="placeholder">This version asks for nothing yet.</Typography>;
  }
  return <ReloadTree value={reload}><PartList parts={parts} index={index} /></ReloadTree>;
}

export default SchemaVersionTree;
