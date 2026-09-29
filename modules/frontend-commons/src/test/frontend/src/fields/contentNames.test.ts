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

import { suggestName } from "@iap/frontend-commons/fields/contentNames";
import type { CreatableType } from "@iap/frontend-commons/fields/fieldsModel";

const QUESTION: CreatableType = {
  type: "sch:Question", label: "Question", fields: [], defaultName: "question", named: true,
  namePattern: "^[A-Za-z0-9][A-Za-z0-9_-]*$",
};

// One that takes any name a node can have
const ANY: CreatableType = { ...QUESTION, namePattern: undefined };

describe("suggestName", () => {
  // The same examples as the server's ContentNamesTest, so that the two make the same names
  it("names new content after the first words of what it says, without accents", () => {
    expect(suggestName("Your date of birth, as on your passport", ANY, [])).toBe("yourDateOfBirthAs");
    expect(suggestName("Âge à l'entrée", ANY, [])).toBe("ageALEntree");
    expect(suggestName("2 things", ANY, [])).toBe("2Things");
    expect(suggestName("Возраст", ANY, [])).toBe("возраст");
    expect(suggestName(" - ", ANY, [])).toBe("question");
  });

  it("keeps to the type's pattern as a whole", () => {
    expect(suggestName("Возраст", QUESTION, [])).toBe("question");
    expect(suggestName("Your age", { ...QUESTION, namePattern: "[a-z]+" }, [])).toBe("question");
  });

  it("names it after its type when what it says makes no name the type may take", () => {
    expect(suggestName(" - ", QUESTION, [])).toBe("question");
    expect(suggestName("_under", { ...QUESTION, namePattern: "^[a-z]+$" }, [])).toBe("under");
    expect(suggestName("Mixed Case", { ...QUESTION, namePattern: "^[a-z]+$" }, [])).toBe("question");
    expect(suggestName("", { ...QUESTION, defaultName: undefined }, [])).toBe("");
    // A first field that holds no text yet
    expect(suggestName(undefined, QUESTION, [])).toBe("question");
  });



  it("takes a name nothing has yet where it goes", () => {
    expect(suggestName("Your age", QUESTION, [ "yourAge", "yourAge2" ])).toBe("yourAge3");
    expect(suggestName("", QUESTION, [ "question" ])).toBe("question2");
  });
});
