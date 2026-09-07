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

import type { KeyboardEvent } from "react";

export interface RowButtonProps {
  role: "button";
  tabIndex: 0;
  onClick: () => void;
  onKeyDown: (event: KeyboardEvent<HTMLElement>) => void;
}

/**
 * What makes a whole tree row behave like the button it looks like.
 *
 * A row is a plain element because it holds a checkbox and a caret, and nesting those inside a
 * button is not allowed. That leaves the row owing everything a button would have got for free: a
 * role, a place in the tab order, and the two keys that press it.
 */
export function rowButtonProps(onActivate: () => void): RowButtonProps {
  return {
    role: "button",
    tabIndex: 0,
    onClick: onActivate,
    onKeyDown: event => {
      if (event.key === "Enter" || event.key === " ") {
        // Space would otherwise scroll the page out from under whoever pressed it
        event.preventDefault();
        onActivate();
      }
    },
  };
}
