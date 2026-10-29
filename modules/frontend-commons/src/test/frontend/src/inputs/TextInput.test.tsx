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

import TextInput from "@iap/frontend-commons/inputs/TextInput";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";

const renderInput = (props: Partial<Parameters<typeof TextInput>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<TextInput label="Name" values={[ "Ann" ]} onChange={onChange} onCommit={onCommit} {...props} />);
  return { onChange, onCommit, input: screen.queryByRole("textbox", { name: /Name/ }) };
};

describe("TextInput", () => {
  it("reports what is typed, and nothing once emptied", () => {
    const { onChange, input } = renderInput();

    fireEvent.change(input!, { target: { value: "Bo" } });
    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange.mock.calls).toEqual([ [ [ "Bo" ] ], [ [] ] ]);
  });

  it("commits when left or on Enter", () => {
    const { onCommit, input } = renderInput();

    fireEvent.keyDown(input!, { key: "Enter" });
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "Ann" ] ], [ [ "Ann" ] ] ]);
  });

  it("commits a multiline value only when left", () => {
    const { onCommit, input } = renderInput({ multiline: true });

    fireEvent.keyDown(input!, { key: "Enter" });
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.blur(input!);
    expect(onCommit).toHaveBeenCalledWith([ "Ann" ]);
  });

  it("finishes a value with no one listening", () => {
    const { input } = renderInput({ onCommit: undefined });

    fireEvent.blur(input!);

    expect(input).toHaveValue("Ann");
  });

  it("shows its description", () => {
    renderInput({ description: "Given name" });
    expect(screen.getByText("Given name")).toBeInTheDocument();
  });

  it("shows problems in place of the description", () => {
    const { input } = renderInput({ description: "Given name", problems: [ "Too short", "Taken" ] });

    expect(screen.getByText("Too short. Taken")).toBeInTheDocument();
    expect(screen.queryByText("Given name")).not.toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("flags a missing value only once the field is left", () => {
    const { input } = renderInput({ values: [], required: true });

    expect(screen.queryByText(REQUIRED_PROBLEM)).not.toBeInTheDocument();
    fireEvent.blur(input!);
    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
  });

  it("shows the value as text when read-only", () => {
    const { input } = renderInput({ readOnly: true });

    expect(input).toBeNull();
    expect(screen.getByText("Name: Ann")).toBeInTheDocument();
  });
});
