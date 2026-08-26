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

import type { Profile, ProfileField } from "@iap/user-profiles/profileApi";
import ProfileFieldControl from "@iap/user-profiles/ProfileFieldControl";

const field = (overrides: Partial<ProfileField> = {}): ProfileField => ({
  name: "email",
  label: "Email address",
  kind: "profile",
  dataType: "text",
  required: false,
  multiple: false,
  usable: true,
  readable: true,
  editable: true,
  provenance: "local",
  values: [],
  ...overrides,
});

const profile: Profile = { account: "jdoe", external: false, idp: "", principals: [], fields: [] };

const show = (subject: ProfileField, value = "", error?: string, onChange = vi.fn()) => {
  render(
    <ProfileFieldControl field={subject} profile={profile} value={value} error={error} onChange={onChange} />,
  );
  return onChange;
};

describe("ProfileFieldControl", () => {
  it("renders a closed set of choices as a select, labelled for reading", async () => {
    const onChange = show(field({ name: "locale", label: "Language", allowedValues: [ "en", "fr" ] }), "en");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Language" }));
    fireEvent.click(await screen.findByRole("option", { name: "français" }));

    expect(onChange).toHaveBeenCalledWith("fr");
  });

  it("offers a way back to no preference for an optional choice", async () => {
    show(field({ name: "locale", label: "Language", allowedValues: [ "en" ] }));

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Language" }));

    expect(await screen.findByRole("option", { name: "No preference" })).toBeInTheDocument();
  });

  it("offers no way back to nothing for a required choice", () => {
    show(field({ name: "locale", label: "Language", allowedValues: [ "en" ], required: true }), "en");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: /Language/ }));

    expect(screen.queryByRole("option", { name: "No preference" })).not.toBeInTheDocument();
  });

  it("renders anything else as a text box carrying its description and pattern", () => {
    const onChange = show(field({ description: "Where we email you.", pattern: ".+@.+" }));

    const input = screen.getByRole("textbox", { name: "Email address" });
    expect(input).toHaveAttribute("pattern", ".+@.+");
    expect(screen.getByText("Where we email you.")).toBeInTheDocument();

    fireEvent.change(input, { target: { value: "a@b.c" } });
    expect(onChange).toHaveBeenCalledWith("a@b.c");
  });

  it("shows the reason a save turned the field down instead of its description", () => {
    show(field({ description: "Where we email you." }), "nonsense", "Not a valid email address");

    expect(screen.getByText("Not a valid email address")).toBeInTheDocument();
    expect(screen.queryByText("Where we email you.")).not.toBeInTheDocument();
  });

  it("shows a field the person may not change as a value with its owner, not a dead input", () => {
    show(field({ editable: false, provenance: "idp", values: [ "jane@uhn.ca" ] }), "jane@uhn.ca");

    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByText("jane@uhn.ca")).toBeInTheDocument();
    expect(screen.getByLabelText("Read-only")).toBeInTheDocument();
  });

  it("says a read-only field has nothing recorded rather than showing a blank", () => {
    show(field({ editable: false, provenance: "platform" }));

    expect(screen.getByText("Not recorded")).toBeInTheDocument();
  });

  it("describes a field it may not read as if nothing were recorded", () => {
    show(field({ readable: false, values: undefined }));

    expect(screen.getByText("Email address")).toBeInTheDocument();
    expect(screen.getByText("Not shown to you.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
});
