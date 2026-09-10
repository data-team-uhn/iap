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

import {
  choiceLabel,
  currentValue,
  readOnlyReason,
  readProfile,
  saveProfile,
  type Profile,
  type ProfileField,
} from "@iap/user-profiles/profileApi";

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

const profile = (overrides: Partial<Profile> = {}): Profile => ({
  account: "jdoe",
  external: false,
  idp: "",
  principals: [],
  fields: [],
  ...overrides,
});

// Typed parameters, so that a test can read back the request the subject made: an untyped `vi.fn`
// records its calls as an empty tuple and indexing one is a type error.
const answering = (body: unknown, ok = true, status = 200) =>
  vi.fn((_url: string, _init?: RequestInit) =>
    Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as unknown as Response));

describe("readProfile", () => {
  it("returns the served document", async () => {
    const fetcher = answering(profile({ account: "jdoe" }));

    expect((await readProfile(fetcher)).account).toBe("jdoe");
    expect(fetcher).toHaveBeenCalledWith("/system/iap/profile.json");
  });

  it("reports a failure with its status rather than a blank page", async () => {
    await expect(readProfile(answering(null, false, 404))).rejects.toThrow("404");
  });
});

describe("saveProfile", () => {
  it("posts the changed fields as a form and returns the outcome", async () => {
    const fetcher = answering({ status: "success", forbidden: false, changed: [ "locale" ], refused: {} });

    const outcome = await saveProfile(fetcher, { locale: "fr" });

    expect(outcome.changed).toEqual([ "locale" ]);
    const init = fetcher.mock.calls[0][1]!;
    expect(init.method).toBe("POST");
    expect(init.body).toBe("locale=fr");
  });

  it("reports a response carrying no outcome at all as a failure", async () => {
    // A refusal still answers with an outcome; only a genuinely broken response has nothing to say
    const fetcher = vi.fn(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error("not json")),
    } as unknown as Response));

    await expect(saveProfile(fetcher, { locale: "fr" })).rejects.toThrow("500");
  });
});

describe("currentValue", () => {
  it("reads the single value a control edits", () => {
    expect(currentValue(field({ values: [ "a@b.c" ] }))).toBe("a@b.c");
  });

  it("is empty for a field with nothing recorded, and for one withheld", () => {
    expect(currentValue(field({ values: [] }))).toBe("");
    expect(currentValue(field({ values: undefined }))).toBe("");
  });
});

describe("choiceLabel", () => {
  it("labels a persona the way the persona switcher does", () => {
    expect(choiceLabel(field({ name: "persona" }), "reviewer")).toBe("Reviewer");
  });

  it("falls back for a persona value the platform does not know", () => {
    expect(choiceLabel(field({ name: "persona" }), "auditor")).toBe("Auditor");
  });

  it("names a language in itself", () => {
    expect(choiceLabel(field({ name: "locale" }), "fr")).toBe("français");
  });

  it("falls back to the tag when the runtime cannot name the language", () => {
    expect(choiceLabel(field({ name: "locale" }), "not a tag")).toBe("not a tag");
  });

  it("title-cases anything else", () => {
    expect(choiceLabel(field({ name: "emailFrequency" }), "weekly")).toBe("Weekly");
  });
});

describe("readOnlyReason", () => {
  it("says so when the definition itself is broken", () => {
    expect(readOnlyReason(field({ problems: [ "unknown data type" ] }), profile()))
      .toContain("not configured correctly");
  });

  it("sends the person to the identity provider that owns the value", () => {
    expect(readOnlyReason(field({ provenance: "idp" }), profile({ external: true, idp: "keycloak" })))
      .toContain("keycloak");
  });

  it("still names an identity provider it was not told the name of", () => {
    expect(readOnlyReason(field({ provenance: "idp" }), profile({ external: true })))
      .toContain("your identity provider");
  });

  it("distinguishes a platform-maintained field from an administrator-managed one", () => {
    expect(readOnlyReason(field({ provenance: "platform" }), profile())).toContain("platform");
    expect(readOnlyReason(field({ provenance: "unset" }), profile())).toContain("administrator");
  });
});
