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
import { Button, FormControlLabel, Menu, MenuItem, Radio, RadioGroup } from "@mui/material";

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

interface SchemaNodeCreateActionProps {
  // What new content is created in, serialized with what may be created there
  parent: JcrNode;
  // The sibling new content goes before, or nothing to place it last
  before?: string;
  // When given, the name of what the parent holds first, which new content may then go ahead of instead
  first?: string;
  // The control that offers it, given what opens the choice, and the only type offered if there is one
  trigger: (open: (anchor: HTMLElement) => void, only?: CreatableType) => ReactNode;
}

// Adds a part or an answer option where the parent may hold one: every type it may create, with a dialog for
// what the new content starts with, and for a part the identifier it is created with, suggested from what it says
// until it is given one. A single type needs no menu. Nothing is added while something is moving.
function SchemaNodeCreateAction({ parent, before, first, trigger }: SchemaNodeCreateActionProps) {
  const [ menu, setMenu ] = useState<HTMLElement | null>(null);
  const [ chosen, setChosen ] = useState<CreatableType | null>(null);
  const [ atStart, setAtStart ] = useState(false);
  // The identifier asked for, once it is given one rather than the suggestion
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

  return (
    <>
      { trigger(open, types.length === 1 ? types[0] : undefined) }
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
          title={`New ${chosen.label.toLowerCase()}`}
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
          { first && (
            <RadioGroup
              row
              aria-label="Where it goes"
              value={atStart ? "start" : "end"}
              onChange={event => setAtStart(event.target.value === "start")}
            >
              <FormControlLabel value="end" control={<Radio />} label="At the end" />
              <FormControlLabel value="start" control={<Radio />} label="At the start" />
            </RadioGroup>
          ) }
        </FieldsDialog>
      ) }
    </>
  );
}

// Adds what a container may hold, at its end or, once it holds something, at its start, standing in by as much as
// what it adds does, in theme spacing
export function AddAtEnd({ parent, first, indent }: { parent: JcrNode; first?: string; indent: number }) {
  return (
    <SchemaNodeCreateAction
      parent={parent}
      first={first}
      trigger={(open, only) => (
        <Button size="small" startIcon={<AddOutlinedIcon />}
          sx={{ alignSelf: "flex-start", ml: { sm: indent } }}
          onClick={event => open(event.currentTarget)}>
          { only ? `Add ${only.label.toLowerCase()}` : "Add" }
        </Button>
      )}
    />
  );
}

// Adds what the parent may hold right after one of its children, ahead of the next one if there is one
export function AddBelow({ parent, next }: { parent: JcrNode; next?: string }) {
  return (
    <SchemaNodeCreateAction
      parent={parent}
      before={next}
      trigger={open => (
        <ActionIcon
          label="Add below"
          icon={<AddBoxOutlinedIcon fontSize="small" />}
          onClick={event => open(event.currentTarget)}
        />
      )}
    />
  );
}

export default SchemaNodeCreateAction;
