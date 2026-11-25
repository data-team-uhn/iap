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

// Helpers the value input tests share: rendering an input with spies on its callbacks, and listing the options a
// list offers.

import { type ComponentType } from "react";

import { fireEvent, render, screen, within } from "@testing-library/react";

import { type ValueInputProps } from "@iap/frontend-commons/inputs/valueInput";

// Renders a value input with spies for its callbacks, and returns them with the field its label names. `retype`
// renders the input again with other values, as a caller does once it accepts a change.
export function renderValueInput<Props extends ValueInputProps>(
  Input: ComponentType<Props>,
  defaults: Omit<Props, "onChange" | "onCommit">,
  props: Partial<Props> = {}
) {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  const element = (more: Partial<Props>) => {
    // The test's own props come last, so that it can leave out `onCommit`
    const all: Partial<Props> = { onChange, onCommit, ...defaults, ...more };
    return <Input {...all as Props} />;
  };
  const { rerender } = render(element(props));
  return {
    onChange, onCommit, input: screen.queryByLabelText(new RegExp(defaults.label)),
    retype: (values: string[]) => rerender(element({ ...props, values })),
  };
}

// The text of each option a list offers once opened from this element
export async function listedOptions(trigger: HTMLElement) {
  fireEvent.mouseDown(trigger);
  return within(await screen.findByRole("listbox")).getAllByRole("option").map(option => option.textContent);
}
