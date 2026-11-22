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
  catalogueNameFromRoute, cataloguePageUrl, descriptionOf, labelOf, listing, tagsOf, titleOf, versionNameFromRoute,
  versionPageUrl, versionsOf,
} from "@iap/data-requirement/admin/catalogueModel";

import { HOMEPAGE, withPaths } from "./catalogueServer.fixture";

const registry = withPaths("/Catalogues/registry", HOMEPAGE.registry);

describe("catalogueModel", () => {
  it("reads a catalogue's versions in the order it keeps them, leaving out other children", () => {
    expect(versionsOf({ ...registry, notes: { "jcr:primaryType": "nt:unstructured" } }).map(labelOf))
      .toEqual([ "2026-02", "2026-08", "2026-11" ]);
  });

  it("names a catalogue by its title and a version by its label, or else by their node names", () => {
    expect(titleOf(registry)).toBe("Clinical registry");
    expect(titleOf({ "@name": "untitled" })).toBe("untitled");
    expect(labelOf({ "@name": "v9" })).toBe("v9");
  });

  it("reads where a node stands and what it says about itself", () => {
    const current = registry.v2 as Record<string, unknown>;
    expect(tagsOf(current)).toEqual([ "active" ]);
    expect(tagsOf(registry)).toEqual([]);
    expect(descriptionOf(current)).toBe("Current");
    expect(descriptionOf(registry)).toBeUndefined();
  });

  it("lists to a depth, with the events the user may send", () => {
    expect(listing(2)).toBe("2.simple.events");
  });

  it("links each catalogue and version to its page, and reads the names back from either path", () => {
    expect(cataloguePageUrl("my registry")).toBe("/admin/catalogues/my%20registry");
    expect(versionPageUrl("registry", "v2")).toBe("/admin/catalogues/registry/v2");
    expect(catalogueNameFromRoute("/admin/catalogues/my%20registry")).toBe("my registry");
    expect(versionNameFromRoute("/admin/catalogues/registry/v2")).toBe("v2");
    expect(catalogueNameFromRoute("/Catalogues/registry/v2")).toBe("registry");
    expect(versionNameFromRoute("/Catalogues/registry")).toBeUndefined();
    expect(catalogueNameFromRoute("/admin/catalogues")).toBe("");
  });
});
