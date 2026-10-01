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

import { useState, type ReactNode } from "react";

import AddBoxOutlinedIcon from "@mui/icons-material/AddBoxOutlined";
import AddOutlinedIcon from "@mui/icons-material/AddOutlined";
import { Button, Menu, MenuItem, Stack, ToggleButton, ToggleButtonGroup, Typography } from "@mui/material";

import { useInActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { suggestName } from "@iap/frontend-commons/fields/contentNames";
import FieldsDialog from "@iap/frontend-commons/fields/FieldsDialog";
import {
  type CreatableType, creatableOf, firstValueOf, newContentOf,
} from "@iap/frontend-commons/fields/fieldsModel";
import { patch } from "@iap/frontend-commons/workflowEvents";

import { childNamesOf, type JcrNode } from "./schemaModel";
import { useMoveMode } from "./schemaMove";
import { NewIdentifier } from "./SchemaNodeIdentifier";
import { useTreeEvent } from "./schemaTree";
import { headingOf, resourceTypeOf } from "./schemaVersionTreeModel";

interface SchemaNodeCreateActionProps {
  // What new content is created in, serialized with what may be created there
  parent: JcrNode;
  // The sibling new content goes before, or nothing to place it last
  before?: string;
  // The parent's first child; when given, the dialog offers to place new content ahead of it
  first?: string;
  // When given, the heading of the part new content goes right after
  after?: string;
  // Renders the control that offers the new content, given what is on offer
  trigger: (offer: Offer) => ReactNode;
}

interface Offer {
  // Opens the choice of a type, or the one type's dialog
  open: (anchor: HTMLElement) => void;
  // The only type offered, if there is one
  only?: CreatableType;
  types: CreatableType[];
  // Opens the given type's dialog
  add: (type: CreatableType) => void;
}

// Adds a part or an answer option to a parent. It offers every type the parent may create, then a dialog
// for the new content's fields. For a part, the dialog also asks for its identifier, suggested from what it
// says until one is given. A single type skips the menu. Nothing is added while something is moving.
function SchemaNodeCreateAction({ parent, before, first, after, trigger }: SchemaNodeCreateActionProps) {
  const [ menu, setMenu ] = useState<HTMLElement | null>(null);
  const [ chosen, setChosen ] = useState<CreatableType | null>(null);
  const [ atStart, setAtStart ] = useState(false);
  // The identifier the user typed. Until there is one, the suggestion is sent.
  const [ identifier, setIdentifier ] = useState<string>();
  const send = useTreeEvent();
  const { moving } = useMoveMode();
  const types = creatableOf(parent);
  if (types.length === 0 || moving) {
    return null;
  }
  const choose = (type: CreatableType) => {
    setIdentifier(undefined);
    setChosen(type);
  };
  const add = (type: CreatableType) => {
    setAtStart(false);
    choose(type);
  };
  const open = (anchor: HTMLElement) => {
    setAtStart(false);
    if (types.length === 1) {
      choose(types[0]);
    } else {
      setMenu(anchor);
    }
  };
  const placedBefore = atStart ? first : before;
  const suggestionFor = (type: CreatableType, heading: unknown) => suggestName(heading, type, childNamesOf(parent));
  const holder = resourceTypeOf(parent) === "sch/SchemaVersion" ? "this version" : `“${headingOf(parent)}”`;
  // Where it goes, going on from the title as one sentence, with the choice in it when there is one
  const choice = (
    <Stack direction="row" sx={{ alignItems: "center", columnGap: 1, rowGap: 1, flexWrap: "wrap" }}>
      <ToggleButtonGroup size="small" color="primary" exclusive value={atStart ? "start" : "end"}
        aria-label="Where it goes"
        onChange={(_event, value: string | null) => {
          if (value !== null) {
            setAtStart(value === "start");
          }
        }}>
        <ToggleButton value="start">at the start</ToggleButton>
        <ToggleButton value="end">at the end</ToggleButton>
      </ToggleButtonGroup>
      <Typography variant="description">{`of ${holder}`}</Typography>
    </Stack>
  );
  const placement = after !== undefined ? <Typography variant="description">{`after “${after}”`}</Typography>
    : first ? choice : <Typography variant="description">{`in ${holder}`}</Typography>;

  return (
    <>
      { trigger({ open, only: types.length === 1 ? types[0] : undefined, types, add }) }
      <Menu anchorEl={menu} open={menu !== null} onClose={() => setMenu(null)}>
        { types.map(type => (
          <MenuItem
            key={type.type}
            onClick={() => {
              setMenu(null);
              choose(type);
            }}
          >
            {type.label}
          </MenuItem>
        )) }
      </Menu>
      { chosen && (
        <FieldsDialog
          title={`Add ${chosen.label.toLowerCase()}`}
          node={newContentOf(chosen)}
          onSave={changes => send(parent, "create", {
            type: chosen.type,
            ...placedBefore ? { before: placedBefore } : {},
            ...chosen.named ? { name: identifier ?? suggestionFor(chosen, firstValueOf(chosen.fields, changes)) } : {},
            ...patch(changes),
          })}
          onClose={() => setChosen(null)}
          afterFirstField={chosen.named ? (heading, working) => (
            <NewIdentifier value={identifier} suggestion={suggestionFor(chosen, heading)} hint={chosen.nameHint}
              disabled={working} onChange={setIdentifier} />
          ) : undefined}
        >
          {placement}
        </FieldsDialog>
      ) }
    </>
  );
}

// Adds to the end of a container, or to its start once it holds something. It is indented by `indent`, in theme
// spacing, to line up with what it adds
export function AddAtEnd({ parent, first, indent }: { parent: JcrNode; first?: string; indent: number }) {
  return (
    <SchemaNodeCreateAction
      parent={parent}
      first={first}
      trigger={({ open, only }) => (
        <Button size="small" startIcon={<AddOutlinedIcon />}
          sx={{ alignSelf: "flex-start", ml: { sm: indent } }}
          onClick={event => open(event.currentTarget)}>
          { only ? `Add ${only.label.toLowerCase()}` : "Add" }
        </Button>
      )}
    />
  );
}

// Adds right after one of the parent's children: ahead of the next child, or last. In a menu, each type it
// may add is a line of its own.
export function AddBelow({ parent, after, next }: { parent: JcrNode; after: string; next?: string }) {
  const inMenu = useInActionsMenu();
  return (
    <SchemaNodeCreateAction
      parent={parent}
      before={next}
      after={after}
      trigger={({ open, types, add }) => (inMenu
        ? types.map(type => (
          <ActionIcon key={type.type} label={`Add ${type.label.toLowerCase()} below`}
            icon={<AddBoxOutlinedIcon fontSize="small" />} onClick={() => add(type)} />
        ))
        : <ActionIcon label="Add below" icon={<AddBoxOutlinedIcon fontSize="small" />} onClick={open} />)}
    />
  );
}

export default SchemaNodeCreateAction;
