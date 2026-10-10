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
import { Box, Chip, Collapse, IconButton, Popover, Stack, type Theme, Tooltip, Typography } from "@mui/material";
import { type SystemStyleObject } from "@mui/system";

import AppliesWhenLine from "@iap/conditions/AppliesWhenLine";
import { whenApplies } from "@iap/conditions/conditionModel";
import { useTagChoices } from "@iap/conditions/useTagChoices";
import { creatableOf } from "@iap/frontend-commons/fields/fieldsModel";
import { usePhone } from "@iap/frontend-commons/usePhone";

import CodePill from "./CodePill";
import { offeredOf, schemaSources } from "./conditionModel";
import { type JcrNode, nameIfAny, nameOf, pathOf } from "./schemaModel";
import {
  MoveMode, MoveSpot, useMoveHighlight,
} from "./schemaMove";
import SchemaNodeActions from "./SchemaNodeActions";
import { AddAtEnd } from "./SchemaNodeCreateAction";
import { type SchemaPartChip, schemaPartTypeOf } from "./schemaPartTypes";
import { ReloadTree } from "./schemaTree";
import { EXPANDER_COLUMN, ICON_COLUMN, TOUCH_REACH } from "./schemaTreeLayout";
import {
  conditionOf, detailOf, headingOf, indexQuestions, isQuestion, optionsOf, resourceTypeOf, partsOf,
} from "./schemaVersionTreeModel";
import { useConditionEditor } from "./useConditionEditor";
import { useOptionsFrom } from "./useOptionsFrom";
import { useVersionConditions, VersionConditionsContext } from "./versionConditions";

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
}

// One requirement, section or question, with what it contains nested inside. Containers start open and
// questions closed, so the outline of a version reads first and the detail is a click away. When it
// applies stays in view either way: it is what the outline is made of. While something is moving, a closed part
// still offers its end as a place to go, opening to show what went there, and opens to offer the rest.
function PartCard({ part, parent, siblings }: PartCardProps) {
  const type = schemaPartTypeOf(resourceTypeOf(part));
  const children = partsOf(part);
  const condition = conditionOf(part);
  const { sources } = useVersionConditions();
  const when = condition && whenApplies(condition, sources);
  const conditionEditor = useConditionEditor(part, type.label.toLowerCase());
  const description = detailOf(part, "description");
  const details = type.details?.(part) ?? null;
  const [ open, setOpen ] = useState(children.length > 0);
  const { ref, surface, content } = useMoveHighlight<HTMLLIElement>(part);
  const { Icon } = type;
  const phone = usePhone();
  const typeIcon = (style: SystemStyleObject<Theme>) => (
    <Tooltip title={type.label}>
      <Icon fontSize="small" titleAccess={type.label} sx={{ color: type.accent, ...style }} />
    </Tooltip>
  );
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
              sx={TOUCH_REACH}
              aria-label={`${open ? "Collapse" : "Expand"} ${headingOf(part)}`}
              aria-expanded={open}
              onClick={() => setOpen(current => !current)}
            >
              { open ? <ExpandMoreIcon fontSize="small" /> : <KeyboardArrowRightIcon fontSize="small" /> }
            </IconButton>
          ) }
        </Box>
        <Stack direction="row" sx={{ flex: 1, minWidth: 0, alignItems: "flex-start" }}>
          { !phone && (
            <Box sx={{ width: theme => theme.spacing(ICON_COLUMN), flexShrink: 0 }}>
              {typeIcon({ mt: 0.75, ...content })}
            </Box>
          ) }
          <Stack spacing={0.5} sx={{ flex: 1, minWidth: 0, pt: 0.5, ...content }}>
            <Stack direction="row" useFlexGap spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
              <Typography sx={{ overflowWrap: "anywhere", fontWeight: type.weight }}>
                {/* On a phone, at the start of the heading, which wraps under it */}
                { phone && typeIcon({ verticalAlign: "text-bottom", mr: 0.75 }) }
                {headingOf(part)}
              </Typography>
              <CodePill name={nameOf(part)} />
            </Stack>
            { type.chips && (
              <Stack direction="row" useFlexGap spacing={0.5} sx={{ flexWrap: "wrap" }}>
                { type.chips(part).map(chip => (
                  <FactChip key={typeof chip === "string" ? chip : chip.label} chip={chip} />
                )) }
              </Stack>
            ) }
            { when && <AppliesWhenLine onEdit={conditionEditor.edit}>{when}</AppliesWhenLine> }
          </Stack>
          <Stack direction="row" sx={{ flexShrink: 0, ml: 1 }}>
            <SchemaNodeActions node={part} parent={parent} siblings={siblings} what={type.label.toLowerCase()}
              editCondition={conditionEditor.edit} />
            {conditionEditor.dialog}
          </Stack>
        </Stack>
      </Stack>
      { !open && <MoveSpot parent={part} onChoose={() => setOpen(true)} /> }
      <Collapse in={open && hasMore} unmountOnExit sx={content}>
        <Stack spacing={1} sx={{ pl: { xs: 1, sm: EXPANDER_COLUMN + 1 }, pr: 1, pb: 1 }}>
          { description && <Typography variant="description">{description}</Typography> }
          {details}
          { children.length > 0
            ? <PartList parent={part} parts={children} />
            : <MoveSpot parent={part} /> }
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
}

// Parts one under the other, and, while something is moving, the places between them it may go
function PartList({ parent, parts }: PartListProps) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0 }}>
      { parts.map(part => (
        <Fragment key={pathOf(part)}>
          <MoveSpot parent={parent} before={part} item />
          <PartCard part={part} parent={parent} siblings={parts} />
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
  const tags = useTagChoices();
  const index = useMemo(() => indexQuestions(version), [ version ]);
  const items = useOptionsFrom(index);
  const conditions = useMemo(() => {
    const sources = schemaSources(index, tags, items);
    return { index, sources, offered: offeredOf(sources) };
  }, [ index, tags, items ]);
  const parts = partsOf(version);
  return (
    <ReloadTree value={reload}>
      <VersionConditionsContext value={conditions}>
        <MoveMode>
          <Stack spacing={1}>
            { parts.length === 0
              ? <Typography variant="placeholder">This version asks for nothing yet.</Typography>
              : <PartList parent={version} parts={parts} /> }
            <AddAtEnd parent={version} first={nameIfAny(parts.at(0))} indent={0} />
          </Stack>
        </MoveMode>
      </VersionConditionsContext>
    </ReloadTree>
  );
}

export default SchemaVersionTree;
