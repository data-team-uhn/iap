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
// Previous and Next buttons stepping through a list of marks, such as passages or changes.
import { useEffect, useRef } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import { Box, Typography } from "@mui/material";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";

import type { StepDirection } from "./pdfViewerModel";

// Steps through a list one item at a time, with Previous and Next buttons around the position, as in
// "2 of 5". Positions count from 1. Each button is disabled at its end of the list. Given a heading, it
// shows the heading between the buttons instead of the count. The text between the buttons keeps the
// width of the widest text it can take, so stepping never moves the buttons. That is the count at the
// last position, or else the `widest` texts given with the heading. The pressed button keeps the focus. When
// it reaches its end of the list and is disabled, the other button takes the focus.
export default function StepControls(props: {
  position: number;
  count: number;
  // The item's name in the button labels, as "passage" in "Next passage". Where the items are of more than
  // one sort, it gives each button the name of the item it goes to.
  itemName: string | { previous: string; next: string };
  heading?: string;
  // The widest texts the heading can take at any position.
  widest?: string[];
  // The text's style. By default a heading is a `subtitle2` and a bare count is a `caption`.
  variant?: "caption" | "subtitle2";
  // Lets the text between the buttons be cut short where the row has no room for it, as in a toolbar.
  shrinks?: boolean;
  // The button to focus when the controls first appear. It is for controls that a step draws anew, such as
  // those on the card drawn for the next item.
  focused?: StepDirection;
  onStep: (position: number, direction: StepDirection) => void;
}) {
  const { position, count, itemName, heading, widest, variant, shrinks, focused, onStep } = props;
  const names = typeof itemName === "string" ? { previous: itemName, next: itemName } : itemName;
  const previousButton = useRef<HTMLButtonElement>(null);
  const nextButton = useRef<HTMLButtonElement>(null);
  const pressed = useRef(focused);
  const step = (direction: StepDirection) => {
    pressed.current = direction;
    onStep(direction === "next" ? position + 1 : position - 1, direction);
  };
  // A button disabled under the focus loses it, so at an end the focus goes to the other button.
  useEffect(() => {
    const direction = pressed.current;
    pressed.current = undefined;
    if (direction === undefined) {
      return;
    }
    const atEnd = direction === "next" ? position >= count : position <= 1;
    const nextKeeps = (direction === "next") !== atEnd;
    (nextKeeps ? nextButton : previousButton).current?.focus();
  }, [position, count]);
  const [ text, sizes, style ] = heading === undefined
    ? [ `${position} of ${count}`, [ `${count} of ${count}` ], variant ?? "caption" ]
    : [ heading, widest ?? [], variant ?? "subtitle2" ];
  // Hidden copies of the widest texts hold the width. CSS draws them, so they are not in the page's text.
  const widthHolders = [ ...new Set(sizes) ].filter(size => size !== text).map(size => (
    <Typography
      key={size}
      variant={style}
      component="span"
      noWrap
      data-size={size}
      sx={{ visibility: "hidden", "&::before": { content: "attr(data-size)" } }}
    />
  ));
  return (
    <Box sx={{ display: "flex", alignItems: "center", minWidth: 0, flexShrink: shrinks ? 1 : 0, justifyContent: "space-between" }}>
      <ActionIcon
        ref={previousButton}
        label={`Previous ${names.previous}`}
        icon={<ChevronLeftIcon />}
        size="medium"
        disabled={position <= 1}
        onClick={() => step("previous")}
      />
      <Box
        component="p"
        sx={{
          display: "inline-grid",
          justifyItems: "center",
          m: 0,
          minWidth: 0,
          fontVariantNumeric: "tabular-nums",
          "& > *": { gridArea: "1 / 1" },
        }}
      >
        {widthHolders}
        <Typography variant={style} component="span" noWrap sx={{ maxWidth: "100%" }}>{text}</Typography>
      </Box>
      <ActionIcon
        ref={nextButton}
        label={`Next ${names.next}`}
        icon={<ChevronRightIcon />}
        size="medium"
        disabled={position >= count}
        onClick={() => step("next")}
      />
    </Box>
  );
}
