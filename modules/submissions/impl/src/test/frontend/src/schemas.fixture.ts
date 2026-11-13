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

// A /Schemas tree as the serializer returns it at depth 2: the homepage's own properties, its
// schemas under their node names, and each schema's versions under theirs.
export const SCHEMAS = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "@path": "/Schemas",
  "@name": "Schemas",
  "timeOffRequest": {
    "jcr:primaryType": "sch:Schema",
    "@path": "/Schemas/timeOffRequest",
    "@name": "timeOffRequest",
    "title": "Time off request",
    "v1": {
      "jcr:primaryType": "sch:SchemaVersion",
      "@path": "/Schemas/timeOffRequest/v1",
      "@name": "v1",
      "version": "1.0",
      "description": "Asking for a day off",
      "tags": ["active"],
    },
  },
  "expenses": {
    "jcr:primaryType": "sch:Schema",
    "@path": "/Schemas/expenses",
    "@name": "expenses",
    "title": "Expenses",
    "active": false,
    "v1": {
      "jcr:primaryType": "sch:SchemaVersion",
      "@path": "/Schemas/expenses/v1",
      "@name": "v1",
      "version": "1.0",
      "active": true,
    },
  },
};

// A /Categories tree, deep: a top category naming the open schema, one naming a schema that is not open,
// and a retired one. The schema version arrives inlined by the dereference processor.
export const CATEGORIES = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "@path": "/Categories",
  "away": {
    "jcr:primaryType": "cat:Category",
    "@path": "/Categories/away",
    "@name": "away",
    "label": "Time away",
    "description": "Any request to be away from work",
    "schemaVersion": { "@path": "/Schemas/timeOffRequest/v1", "version": "1.0" },
    "sick": {
      "jcr:primaryType": "cat:Category",
      "@path": "/Categories/away/sick",
      "label": "Sick leave",
    },
  },
  "closed": {
    "jcr:primaryType": "cat:Category",
    "@path": "/Categories/closed",
    "label": "Closed",
    "schemaVersion": { "@path": "/Schemas/expenses/v1", "version": "1.0" },
  },
  "retiredOne": {
    "jcr:primaryType": "cat:Category",
    "@path": "/Categories/retiredOne",
    "label": "Retired",
    "tags": [ "retired" ],
    "schemaVersion": { "@path": "/Schemas/timeOffRequest/v1", "version": "1.0" },
  },
};
