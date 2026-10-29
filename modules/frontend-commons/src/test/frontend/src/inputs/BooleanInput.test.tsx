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

import { fireEvent, render, screen, within } from "@testing-library/react";

import BooleanInput from "@iap/frontend-commons/inputs/BooleanInput";

const renderInput = (props: Partial<Parameters<typeof BooleanInput>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<BooleanInput label="Notify" values={[ "true" ]} onChange={onChange} onCommit={onCommit} {...props} />);
  return { onChange, onCommit };
};

const select = () => screen.getByRole("combobox", { name: /Notify/ });

const options = async () => {
  fireEvent.mouseDown(select());
  return within(await screen.findByRole("listbox")).getAllByRole("option").map(option => option.textContent);
};

describe("BooleanInput", () => {
  it("shows true and false as Yes and No, and reports a pick as finished", async () => {
    const { onChange, onCommit } = renderInput();

    expect(select()).toHaveTextContent("Yes");
    expect(await options()).toEqual([ "None", "Yes", "No" ]);
    fireEvent.click(screen.getByRole("option", { name: "No" }));

    expect(onChange).toHaveBeenCalledWith([ "false" ]);
    expect(onCommit).toHaveBeenCalledWith([ "false" ]);
  });

  it("shows the labels it is given", async () => {
    renderInput({ trueLabel: "Enabled", falseLabel: "Disabled" });
    expect(await options()).toEqual([ "None", "Enabled", "Disabled" ]);
  });

  it("offers no None when required", async () => {
    renderInput({ required: true });
    expect(await options()).toEqual([ "Yes", "No" ]);
  });

  it("offers no None when told not to, even when optional", async () => {
    renderInput({ allowNone: false });
    expect(await options()).toEqual([ "Yes", "No" ]);
  });

  it("keeps a stored value that is neither true nor false", async () => {
    renderInput({ values: [ "TRUE" ] });

    expect(select()).toHaveTextContent("TRUE");
    expect(await options()).toEqual([ "None", "Yes", "No", "TRUE" ]);
  });

  it("opens as soon as it appears when asked to", async () => {
    renderInput({ openOnMount: true });
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
  });

  it("shows its label when read-only", () => {
    renderInput({ readOnly: true, values: [ "false" ], falseLabel: "Disabled" });

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByText("Notify: Disabled")).toBeInTheDocument();
  });
});
