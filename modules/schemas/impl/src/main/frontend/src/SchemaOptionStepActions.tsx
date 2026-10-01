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

import { useLayoutEffect, useRef, useState } from "react";

import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";

import { ActionIcon } from "./EventAction";
import { type JcrNode, offers, pathOf } from "./schemaModel";
import { useMoveMode, type Way } from "./schemaMove";

interface SchemaOptionStepActionsProps {
  option: JcrNode;
  question: JcrNode;
  // The question's options, in order
  options: JcrNode[];
}

// Moves an answer option of a draft one place up or down. The button pressed stays under the pointer, keeping the
// focus, unless the option has reached an end, where the other button takes it.
function SchemaOptionStepActions({ option, question, options }: SchemaOptionStepActionsProps) {
  const { step, sending, moved, steps, stepping, landing } = useMoveMode();
  const up = useRef<HTMLButtonElement>(null);
  const down = useRef<HTMLButtonElement>(null);
  const [ asked, setAsked ] = useState<Way>();
  const at = options.findIndex(other => pathOf(other) === pathOf(option));
  const first = at === 0;
  const last = at === options.length - 1;
  const justMoved = moved === pathOf(option);

  // Before painting, so that the button lands back under the pointer without a jump
  useLayoutEffect(() => {
    const [ pressed, other ] = asked === "up" ? [ up.current, down.current ] : [ down.current, up.current ];
    if (!justMoved || !pressed || landing === undefined) {
      return;
    }
    window.scrollBy(0, pressed.getBoundingClientRect().top - landing);
    if (pressed.disabled) {
      other?.focus();
    }
  }, [ justMoved, steps, asked, landing ]);

  if (!offers(option, "move")) {
    return null;
  }
  const stepTo = (way: Way, before?: JcrNode) => (trigger: HTMLElement) => {
    setAsked(way);
    step(option, question, before, way, trigger);
  };
  const busy = stepping === pathOf(option);
  return (
    <>
      <ActionIcon ref={up} label="Move up" icon={<ArrowUpwardIcon fontSize="small" />} disabled={first || sending}
        loading={busy && asked === "up"} onClick={stepTo("up", options.at(at - 1))} />
      <ActionIcon ref={down} label="Move down" icon={<ArrowDownwardIcon fontSize="small" />}
        disabled={last || sending} loading={busy && asked === "down"} onClick={stepTo("down", options.at(at + 2))} />
    </>
  );
}

export default SchemaOptionStepActions;
