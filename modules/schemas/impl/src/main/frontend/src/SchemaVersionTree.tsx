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

import { Fragment, useMemo, useState } from "react";

import AltRouteOutlinedIcon from "@mui/icons-material/AltRouteOutlined";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import KeyboardArrowRightIcon from "@mui/icons-material/KeyboardArrowRight";
import { Box, Chip, Collapse, IconButton, Popover, Stack, Tooltip, Typography } from "@mui/material";

import { creatableOf } from "@iap/frontend-commons/fields/fieldsModel";

import { whenApplies } from "./conditionModel";
import { type JcrNode, nameIfAny, pathOf } from "./schemaModel";
import {
  MoveMode, MoveSpot, useMoveHighlight,
} from "./schemaMove";
import SchemaNodeActions from "./SchemaNodeActions";
import { AddAtEnd } from "./SchemaNodeCreateAction";
import { type SchemaPartChip, schemaPartTypeOf } from "./schemaPartTypes";
import { ReloadTree } from "./schemaTree";
import {
  conditionOf, detailOf, headingOf, indexQuestions, optionsOf, resourceTypeOf, partsOf, type QuestionIndex,
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

// The width, in theme spacing, of the column a part's expand button stands in. On a wide screen, what the part holds
// is indented by as much, and by the card's padding, so its icon lines up with what is under it.
const EXPANDER_COLUMN = 4;

interface PartCardProps {
  part: JcrNode;
  // What holds it, and everything it holds, in order
  parent: JcrNode;
  siblings: JcrNode[];
  index: QuestionIndex;
}

// One requirement, section or question, with what it contains nested inside. Containers start open and
// questions closed, so the outline of a version reads first and the detail is a click away. When it
// applies stays in view either way: it is what the outline is made of. While something is moving, a closed part
// still offers its end as a place to go, opening to show what went there, and opens to offer the rest.
function PartCard({ part, parent, siblings, index }: PartCardProps) {
  const type = schemaPartTypeOf(resourceTypeOf(part));
  const children = partsOf(part);
  const condition = conditionOf(part);
  const when = condition && whenApplies(condition, index);
  const description = detailOf(part, "description");
  const details = type.details?.(part) ?? null;
  const [ open, setOpen ] = useState(children.length > 0);
  const { ref, surface, content } = useMoveHighlight<HTMLLIElement>(part, { bordered: true });
  const { Icon } = type;
  const hasMore = Boolean(description) || details !== null || children.length > 0 || creatableOf(part).length > 0;

  return (
    <Box
      component="li"
      ref={ref}
      tabIndex={-1}
      sx={[ {
        listStyle: "none",
        mb: 1,
        bgcolor: "background.paper",
        border: 1,
        borderColor: "divider",
        borderLeft: 4,
        borderLeftColor: type.accent,
        borderRadius: 1,
        // So that a place to move to under a closed part's heading runs to its rounded edges
        overflow: "hidden",
      }, surface ]}
    >
      <Stack direction="row" sx={{ alignItems: "flex-start", p: 1 }}>
        <Box sx={{ width: theme => theme.spacing(EXPANDER_COLUMN), flexShrink: 0 }}>
          { hasMore && (
            <IconButton
              size="small"
              aria-label={`${open ? "Collapse" : "Expand"} ${headingOf(part)}`}
              aria-expanded={open}
              onClick={() => setOpen(current => !current)}
            >
              { open ? <ExpandMoreIcon fontSize="small" /> : <KeyboardArrowRightIcon fontSize="small" /> }
            </IconButton>
          ) }
        </Box>
        <Stack direction="row" spacing={1} sx={{ flex: 1, minWidth: 0, alignItems: "flex-start" }}>
          <Tooltip title={type.label}>
            <Icon fontSize="small" titleAccess={type.label}
              sx={{ color: type.accent, mt: 0.75, ...content }} />
          </Tooltip>
          <Stack spacing={0.5} sx={{ flex: 1, minWidth: 0, pt: 0.5, ...content }}>
            <Typography sx={{ overflowWrap: "anywhere", fontWeight: type.weight }}>{headingOf(part)}</Typography>
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
          <Stack direction="row" sx={{ flexShrink: 0 }}>
            <SchemaNodeActions node={part} parent={parent} siblings={siblings} what={type.label.toLowerCase()} />
          </Stack>
        </Stack>
      </Stack>
      { !open && <MoveSpot parent={part} onChoose={() => setOpen(true)} /> }
      <Collapse in={open && hasMore} unmountOnExit sx={content}>
        <Stack spacing={1} sx={{ pl: { xs: 1, sm: EXPANDER_COLUMN + 1 }, pr: 1, pb: 1 }}>
          { description && <Typography variant="description">{description}</Typography> }
          {details}
          { children.length > 0
            ? <PartList parent={part} parts={children} index={index} />
            // A question's options end with a place of their own
            : optionsOf(part).length === 0 && <MoveSpot parent={part} /> }
          <AddAtEnd parent={part} first={nameIfAny([ ...children, ...optionsOf(part) ].at(0))} />
        </Stack>
      </Collapse>
    </Box>
  );
}

interface PartListProps {
  parent: JcrNode;
  parts: JcrNode[];
  index: QuestionIndex;
}

// Parts one under the other, and, while something is moving, the places between them it may go
function PartList({ parent, parts, index }: PartListProps) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0 }}>
      { parts.map(part => (
        <Fragment key={pathOf(part)}>
          <MoveSpot parent={parent} before={part} item />
          <PartCard part={part} parent={parent} siblings={parts} index={index} />
        </Fragment>
      )) }
      <MoveSpot parent={parent} item />
    </Box>
  );
}

interface SchemaVersionTreeProps {
  version: JcrNode;
  reload: () => Promise<void>;
}

// Everything a version asks of a submission, in the order it asks it.
function SchemaVersionTree({ version, reload }: SchemaVersionTreeProps) {
  const index = useMemo(() => indexQuestions(version), [ version ]);
  const parts = partsOf(version);
  return (
    <ReloadTree value={reload}>
      <MoveMode>
        <Stack spacing={1}>
          { parts.length === 0
            ? <Typography variant="placeholder">This version asks for nothing yet.</Typography>
            : <PartList parent={version} parts={parts} index={index} /> }
          <AddAtEnd parent={version} first={nameIfAny(parts.at(0))} />
        </Stack>
      </MoveMode>
    </ReloadTree>
  );
}

export default SchemaVersionTree;
