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

import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import KeyboardArrowRightIcon from "@mui/icons-material/KeyboardArrowRight";
import { Box, Chip, Collapse, IconButton, Popover, Stack, Tooltip, Typography } from "@mui/material";

import AppliesWhenLine from "@iap/conditions/AppliesWhenLine";
import { type OperandSource, whenApplies } from "@iap/conditions/conditionModel";
import { creatableOf } from "@iap/frontend-commons/fields/fieldsModel";

import CodePill from "./CodePill";
import { schemaSources } from "./conditionModel";
import { type JcrNode, nameIfAny, nameOf, pathOf } from "./schemaModel";
import {
  MoveMode, MoveSpot, useMoveHighlight,
} from "./schemaMove";
import SchemaNodeActions from "./SchemaNodeActions";
import { AddAtEnd } from "./SchemaNodeCreateAction";
import { type SchemaPartChip, schemaPartTypeOf } from "./schemaPartTypes";
import { ReloadTree } from "./schemaTree";
import { EXPANDER_COLUMN, ICON_COLUMN } from "./schemaTreeLayout";
import {
  conditionOf, detailOf, headingOf, indexQuestions, isQuestion, optionsOf, resourceTypeOf, partsOf,
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

interface PartCardProps {
  part: JcrNode;
  // What holds it, and everything it holds, in order
  parent: JcrNode;
  siblings: JcrNode[];
  // What the conditions of the version's parts read
  sources: OperandSource[];
}

// One requirement, section or question, with what it contains nested inside. Containers start open and
// questions closed, so the outline of a version reads first and the detail is a click away. When it
// applies stays in view either way: it is what the outline is made of. While something is moving, a closed part
// still offers its end as a place to go, opening to show what went there, and opens to offer the rest.
function PartCard({ part, parent, siblings, sources }: PartCardProps) {
  const type = schemaPartTypeOf(resourceTypeOf(part));
  const children = partsOf(part);
  const condition = conditionOf(part);
  const when = condition && whenApplies(condition, sources);
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
        <Stack direction="row" sx={{ flex: 1, minWidth: 0, alignItems: "flex-start" }}>
          <Box sx={{ width: theme => theme.spacing(ICON_COLUMN), flexShrink: 0 }}>
            <Tooltip title={type.label}>
              <Icon fontSize="small" titleAccess={type.label}
                sx={{ color: type.accent, mt: 0.75, ...content }} />
            </Tooltip>
          </Box>
          <Stack spacing={0.5} sx={{ flex: 1, minWidth: 0, pt: 0.5, ...content }}>
            <Stack direction="row" useFlexGap spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
              <Typography sx={{ overflowWrap: "anywhere", fontWeight: type.weight }}>{headingOf(part)}</Typography>
              <CodePill name={nameOf(part)} />
            </Stack>
            { type.chips && (
              <Stack direction="row" useFlexGap spacing={0.5} sx={{ flexWrap: "wrap" }}>
                { type.chips(part).map(chip => (
                  <FactChip key={typeof chip === "string" ? chip : chip.label} chip={chip} />
                )) }
              </Stack>
            ) }
            { when && <AppliesWhenLine>{when}</AppliesWhenLine> }
          </Stack>
          <Stack direction="row" sx={{ flexShrink: 0, ml: 1 }}>
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
            ? <PartList parent={part} parts={children} sources={sources} />
            // A question's options end with a place of their own
            : optionsOf(part).length === 0 && <MoveSpot parent={part} /> }
          <AddAtEnd parent={part} first={nameIfAny([ ...children, ...optionsOf(part) ].at(0))}
            indent={isQuestion(part) ? ICON_COLUMN : 0} />
        </Stack>
      </Collapse>
    </Box>
  );
}

interface PartListProps {
  parent: JcrNode;
  parts: JcrNode[];
  // What the conditions of the version's parts read
  sources: OperandSource[];
}

// Parts one under the other, and, while something is moving, the places between them it may go
function PartList({ parent, parts, sources }: PartListProps) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0 }}>
      { parts.map(part => (
        <Fragment key={pathOf(part)}>
          <MoveSpot parent={parent} before={part} item />
          <PartCard part={part} parent={parent} siblings={parts} sources={sources} />
        </Fragment>
      )) }
      <MoveSpot parent={parent} item />
    </Box>
  );
}

interface SchemaVersionTreeProps {
  version: JcrNode;
  reload: () => Promise<void>;
  // Where to say that a change is done
  report: (message: string) => void;
}

// Everything a version asks of a submission, in the order it asks it.
function SchemaVersionTree({ version, reload, report }: SchemaVersionTreeProps) {
  const sources = useMemo(() => schemaSources(indexQuestions(version)), [ version ]);
  const parts = partsOf(version);
  return (
    <ReloadTree value={reload}>
      <MoveMode report={report}>
        <Stack spacing={1}>
          { parts.length === 0
            ? <Typography variant="placeholder">This version asks for nothing yet.</Typography>
            : <PartList parent={version} parts={parts} sources={sources} /> }
          <AddAtEnd parent={version} first={nameIfAny(parts.at(0))} indent={0} />
        </Stack>
      </MoveMode>
    </ReloadTree>
  );
}

export default SchemaVersionTree;
