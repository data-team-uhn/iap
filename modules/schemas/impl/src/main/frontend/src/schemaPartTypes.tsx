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

import type { ComponentType, ReactNode } from "react";

import AssignmentOutlinedIcon from "@mui/icons-material/AssignmentOutlined";
import DescriptionOutlinedIcon from "@mui/icons-material/DescriptionOutlined";
import ExtensionOutlinedIcon from "@mui/icons-material/ExtensionOutlined";
import HelpOutlineOutlinedIcon from "@mui/icons-material/HelpOutlineOutlined";
import HowToRegOutlinedIcon from "@mui/icons-material/HowToRegOutlined";
import ViewAgendaOutlinedIcon from "@mui/icons-material/ViewAgendaOutlined";
import { Box, Stack, Typography, type SvgIconProps } from "@mui/material";

import CodePill from "./CodePill";
import { nameOf } from "./schemaModel";
import { useMoveHighlight } from "./schemaMove";
import SchemaNodeActions from "./SchemaNodeActions";
import { ICON_COLUMN } from "./schemaTreeLayout";
import {
  answerCountOf, boundsOf, dataTypeOf, detailOf, optionLabelOf, optionsOf, strings, QUESTION_TYPE,
} from "./schemaVersionTreeModel";


import type { JcrNode } from "./schemaModel";

// A fact shown as a chip; one naming what the part's details show opens and closes them
export type SchemaPartChip = string | { label: string; opensDetails: true };

// How one type of schema part is shown: what it is called, its icon and accent, the facts worth seeing
// at a glance, and what else it says once opened. A type not listed here is still shown, as itself.
export interface SchemaPartType {
  label: string;
  Icon: ComponentType<SvgIconProps>;
  // A palette key, for the card's edge and its icon
  accent: string;
  // How much its heading stands out, so that what holds reads above what it holds
  weight?: "fontWeightMedium" | "fontWeightBold";
  chips?: (part: JcrNode) => SchemaPartChip[];
  // What else it says, or null when there is nothing more
  details?: (part: JcrNode) => ReactNode;
}

function Detail({ children }: { children: string }) {
  return <Typography variant="description">{children}</Typography>;
}

// What an option says: what the submitter reads, and what an answer stores when it differs
function OptionText({ option }: { option: JcrNode }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
      <span>{optionLabelOf(option)}</span>
      { option.label !== undefined && option.label !== option.value && <CodePill name={String(option.value)} /> }
    </Stack>
  );
}

// One of a question's options in its details, with what may be done to it
function OptionRow({ option, question, options }: { option: JcrNode; question: JcrNode; options: JcrNode[] }) {
  const { ref, surface, content } = useMoveHighlight<HTMLLIElement>(option);
  const description = detailOf(option, "description");
  return (
    <Typography
      component="li"
      variant="body2"
      ref={ref}
      tabIndex={-1}
      // One row of the list's grid, so that every row's actions stand in the same column
      sx={[ { display: "grid", gridTemplateColumns: "subgrid", alignItems: "center", columnGap: 1 }, surface ]}
    >
      <Box sx={{ minWidth: 0, ...content }}>
        <OptionText option={option} />
        { description && <Typography variant="description">{description}</Typography> }
      </Box>
      <Stack direction="row">
        <SchemaNodeActions node={option} parent={question} siblings={options} what="option" />
      </Stack>
    </Typography>
  );
}

// A question's options in its details, each with what it describes and what may be done to it, the actions lined
// up just after the widest option
function OptionList({ options, question }: { options: JcrNode[]; question: JcrNode }) {
  return (
    <Box
      component="ul"
      // Under the question's chips, on a screen wide enough to spare it
      sx={{ mx: 0, my: 1, p: 0, pl: { sm: ICON_COLUMN }, display: "grid", rowGap: 1.5,
        gridTemplateColumns: "minmax(0, max-content) max-content",
        // Every row spans the grid, spaced by it alone
        "& > li": { gridColumn: "1 / -1", my: 0 } }}
    >
      { options.map(option => (
        <OptionRow key={nameOf(option)} option={option} question={question} options={options} />
      )) }
    </Box>
  );
}

function questionDetails(part: JcrNode): ReactNode {
  const options = optionsOf(part);
  const bounds = boundsOf(part);
  const pattern = detailOf(part, "pattern");
  const optionsFrom = detailOf(part, "optionsFrom");
  if (options.length === 0 && !bounds && !pattern && !optionsFrom) {
    return null;
  }
  return (
    <>
      { options.length > 0 && <OptionList options={options} question={part} /> }
      { optionsFrom && <Detail>{`The options are the items under ${optionsFrom}.`}</Detail> }
      { bounds && <Detail>{`${bounds}.`}</Detail> }
      { pattern && <Detail>{`Must match ${pattern}.`}</Detail> }
      { pattern && detailOf(part, "patternMessage") && (
        <Detail>{`Otherwise the submitter reads “${detailOf(part, "patternMessage")}”.`}</Detail>
      ) }
    </>
  );
}

function documentDetails(part: JcrNode): ReactNode {
  const types = strings(part.acceptedFileTypes);
  return types.length > 0 ? <Detail>{`Accepts ${types.join(", ")}.`}</Detail> : null;
}

function approvalDetails(part: JcrNode): ReactNode {
  const group = detailOf(part, "approverGroup");
  return <Detail>{group ? `Approved by ${group}.` : "No approver group is set."}</Detail>;
}

function questionChips(part: JcrNode): SchemaPartChip[] {
  const options = optionsOf(part);
  const displayMode = detailOf(part, "displayMode");
  return [
    dataTypeOf(part),
    ...displayMode ? [ `Shown as ${displayMode}` ] : [],
    ...answerCountOf(part),
    ...options.length > 0 ? [ {
      label: options.length === 1 ? "1 option" : `${options.length} options`,
      opensDetails: true as const,
    } ] : [],
  ];
}

const documentChips = (part: JcrNode): string[] => [
  part.required === false ? "Optional" : "Required",
  ...typeof part.template === "object" ? [ "Template provided" ] : [],
];

const PART_TYPES: Record<string, SchemaPartType> = {
  "sch/FormRequirement": {
    label: "Form", Icon: AssignmentOutlinedIcon, accent: "text.disabled", weight: "fontWeightBold",
  },
  "sch/DocumentRequirement": {
    label: "Document", Icon: DescriptionOutlinedIcon, accent: "info.main", weight: "fontWeightBold",
    chips: documentChips, details: documentDetails,
  },
  "sch/ApprovalRequirement": {
    label: "Approval", Icon: HowToRegOutlinedIcon, accent: "success.main", weight: "fontWeightBold",
    details: approvalDetails,
  },
  "sch/Section": {
    label: "Section", Icon: ViewAgendaOutlinedIcon, accent: "divider", weight: "fontWeightMedium",
  },
  [QUESTION_TYPE]: {
    label: "Question", Icon: HelpOutlineOutlinedIcon, accent: "primary.main", chips: questionChips,
    details: questionDetails,
  },
};

export const schemaPartTypeOf = (resourceType: string): SchemaPartType =>
  PART_TYPES[resourceType] ?? { label: resourceType, Icon: ExtensionOutlinedIcon, accent: "warning.main" };
