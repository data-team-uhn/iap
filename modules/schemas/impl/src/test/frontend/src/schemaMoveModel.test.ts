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

import { isMoveSpot } from "@iap/schemas/schemaMoveModel";
import { optionsOf, partsOf } from "@iap/schemas/schemaVersionTreeModel";

import { CONTENT, HOMEPAGE, withPaths } from "./schemaServer.fixture";

const version = withPaths("/Schemas/study/v3", { ...HOMEPAGE.study.v3, ...CONTENT["study/v3"] });
const [ intake, followUp ] = partsOf(version);
const [ name, age ] = partsOf(intake);
const [ short, full ] = optionsOf(name);

describe("schemaMoveModel", () => {
  it("offers every place that would put a part somewhere new", () => {
    expect(isMoveSpot(age, intake, name)).toBe(true);
    expect(isMoveSpot(name, intake)).toBe(true);
    expect(isMoveSpot(age, followUp)).toBe(true);
  });

  it("leaves out where it already stands", () => {
    expect(isMoveSpot(name, intake, name)).toBe(false);
    expect(isMoveSpot(name, intake, age)).toBe(false);
    expect(isMoveSpot(age, intake)).toBe(false);
  });

  it("leaves out what cannot hold it, and what it holds", () => {
    expect(isMoveSpot(age, version)).toBe(false);
    expect(isMoveSpot(age, name)).toBe(false);
    expect(isMoveSpot(intake, intake)).toBe(false);
    expect(isMoveSpot(intake, name)).toBe(false);
  });

  it("places an option among the options of what holds it", () => {
    expect(isMoveSpot(full, name, short)).toBe(true);
    expect(isMoveSpot(short, name)).toBe(true);
    expect(isMoveSpot(short, name, full)).toBe(false);
    expect(isMoveSpot(full, name)).toBe(false);
    expect(isMoveSpot(short, intake)).toBe(false);
  });
});
