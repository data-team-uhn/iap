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

import { fireEvent, render, screen } from "@testing-library/react";

import MultiValueInput from "@iap/frontend-commons/inputs/MultiValueInput";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";

const FORMATS = [ { value: "pdf", label: "PDF" }, { value: "doc", label: "Word" } ];

const renderInput = (props: Partial<Parameters<typeof MultiValueInput>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<MultiValueInput label="Tags" values={[ "age" ]} onChange={onChange} onCommit={onCommit} {...props} />);
  return { onChange, onCommit, input: screen.queryByRole("combobox", { name: /Tags/ }) };
};

const type = (input: HTMLElement, text: string) => {
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter" });
};

describe("MultiValueInput", () => {
  it("adds a typed value when there are no choices, and commits it", () => {
    const { onChange, onCommit, input } = renderInput();

    type(input!, "adult");

    expect(onChange).toHaveBeenCalledWith([ "age", "adult" ]);
    expect(onCommit).toHaveBeenCalledWith([ "age", "adult" ]);
  });

  it("keeps text typed and left as a value", () => {
    const { onChange, input } = renderInput();

    fireEvent.change(input!, { target: { value: "adult" } });
    fireEvent.blur(input!);

    expect(onChange).toHaveBeenCalledWith([ "age", "adult" ]);
  });

  it("splits a typed entry as asked", () => {
    const { onChange, input } = renderInput({ splitTyped: typed => typed.split(",") });

    type(input!, "a,b");

    expect(onChange).toHaveBeenCalledWith([ "age", "a", "b" ]);
  });

  it("picks among the choices, shown by their labels, and keeps nothing typed", async () => {
    const { onChange, input } = renderInput({ label: "Tags", choices: FORMATS, values: [ "pdf" ] });

    expect(screen.getByText("PDF")).toBeInTheDocument();
    fireEvent.change(input!, { target: { value: "odt" } });
    fireEvent.blur(input!);
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.mouseDown(input!);
    fireEvent.click(await screen.findByRole("option", { name: "Word" }));
    expect(onChange).toHaveBeenCalledWith([ "pdf", "doc" ]);
  });

  it("opens as soon as it appears when asked to", async () => {
    renderInput({ choices: FORMATS, openOnMount: true });

    expect(await screen.findByRole("listbox")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("marks the chips in error as asked", () => {
    renderInput({ values: [ "age", "bad" ], chipError: value => value === "bad" });

    expect(screen.getByRole("button", { name: "bad" })).toHaveClass("MuiChip-colorError");
    expect(screen.getByRole("button", { name: "age" })).not.toHaveClass("MuiChip-colorError");
  });

  it("flags a missing value once left", () => {
    const { input } = renderInput({ values: [], required: true });

    fireEvent.blur(input!);

    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
  });

  it("shows the values as text when read-only", () => {
    const { input } = renderInput({ choices: FORMATS, values: [ "pdf", "doc" ], readOnly: true });

    expect(input).toBeNull();
    expect(screen.getByText("Tags: PDF, Word")).toBeInTheDocument();
  });
});
