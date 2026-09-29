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

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

// A stand-in server for the schema tool's tests: it serves the schemas, the lifecycle tag
// definitions and a live session, records every event posted, and answers each event as a test
// asks - completed, refused with the engine's reason, or redirected to what it created.

export interface EventAnswer {
  status?: number;
  error?: string;
  // Where the engine redirected to, which fetch has already followed
  redirect?: string;
}

export interface PostedEvent {
  url: string;
  params: URLSearchParams;
}

// Each node carries the `@events` the real guards would offer an administrator in its state
const OPEN_SCHEMA = [ "createVersion", "discard", "retire", "update" ];
const RETIRED_SCHEMA = [ "activate", "discard", "update" ];
const DRAFT = [ "activate", "discard", "update" ];
const ACTIVE = [ "discard", "retire", "update" ];
const RETIRED = [ "activate", "discard", "update" ];

// And the `@fields` their updates would change
const text = (name: string, label: string, mandatory: boolean, multiline = false) =>
  ({ name, label, kind: "text", multiple: false, mandatory, multiline });
const TITLE = [ text("title", "Title", true) ];
const DESCRIPTION = text("description", "Description", false, true);
const DRAFT_FIELDS = [
  text("version", "Label", true), DESCRIPTION,
  { name: "workflow", label: "Workflow", kind: "reference", multiple: false, mandatory: false, multiline: false,
    referenceType: "wf/WorkflowVersion", referenceRoot: "/Workflows" },
];

// The workflows a version may follow
export const WORKFLOWS = [
  { "@path": "/Workflows/review/v1", "version": "1.0" },
  { "@path": "/Workflows/fastTrack/v2", "title": "Fast track" },
];
const PUBLISHED_FIELDS = [ DESCRIPTION ];

export const HOMEPAGE = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "notASchema": { "jcr:primaryType": "nt:unstructured" },
  "study": {
    "jcr:primaryType": "sch:Schema",
    "title": "Clinical study",
    "jcr:lastModified": "2026-09-20T10:00:00.000-04:00",
    "@events": OPEN_SCHEMA, "@fields": TITLE,
    "v1": {
      "jcr:primaryType": "sch:SchemaVersion", "version": "1.0", "tags": ["retired"], "@events": RETIRED,
      "@fields": PUBLISHED_FIELDS, "@notice": "Only the wording of this version can be corrected.",
    },
    "v2": {
      "jcr:primaryType": "sch:SchemaVersion", "version": "2.0", "description": "Current", "tags": ["active"],
      "@events": ACTIVE, "@fields": PUBLISHED_FIELDS, "@notice": "Only the wording of this version can be corrected.",
    },
    "v3": {
      "jcr:primaryType": "sch:SchemaVersion", "version": "3.0", "tags": ["draft"], "@events": DRAFT,
      "@fields": DRAFT_FIELDS, "@notice": "Everything in this version can change until it is activated.",
    },
    "notes": { "jcr:primaryType": "nt:unstructured" },
  },
  "idea": {
    "jcr:primaryType": "sch:Schema",
    "title": "Idea",
    "@events": OPEN_SCHEMA, "@fields": TITLE,
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "0.1", "tags": ["draft"], "@events": DRAFT, "@fields": DRAFT_FIELDS },
  },
  "legacy": {
    "jcr:primaryType": "sch:Schema",
    "title": "Legacy",
    "tags": ["retired"],
    "@events": RETIRED_SCHEMA, "@fields": TITLE,
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "1.0", "tags": ["active"], "@events": ACTIVE, "@fields": PUBLISHED_FIELDS },
  },
};

// What versions ask of a submission, served when a version is read whole. A question and a requirement
// carry the supertype the server stores on them; the rest is what the tree has to show.
const question = (fields: Record<string, unknown>) => ({
  "jcr:primaryType": "sch:Question", "sling:resourceType": "sch/Question", "sling:resourceSuperType": "sch/FormItem",
  "dataType": "text", "minAnswers": 0, "maxAnswers": 1, ...fields,
});

const option = (value: string, label?: string) => ({
  "jcr:primaryType": "sch:AnswerOption", "sling:resourceType": "sch/AnswerOption", value, label,
});

const requirement = (type: string, fields: Record<string, unknown>) => ({
  "jcr:primaryType": `sch:${type}`, "sling:resourceType": `sch/${type}`, "sling:resourceSuperType": "sch/Requirement",
  ...fields,
});

const operand = (source: string, ...value: string[]) => ({ "jcr:primaryType": "cond:ConditionOperand", source, value });

const single = (comparator: string, operandA: unknown, operandB?: unknown) => ({
  "jcr:primaryType": "cond:SingleCondition", "sling:resourceSuperType": "cond/Condition", comparator, operandA,
  operandB,
});

const creatable = (type: string, label: string, ...fields: unknown[]) => ({
  type, label, fields, defaultName: label.charAt(0).toLowerCase() + label.slice(1), named: true,
  namePattern: "^[A-Za-z0-9][A-Za-z0-9_-]*$", nameHint: "Letters, digits, - and _.",
});

// An option takes no name of its own
const unnamed = (type: string, label: string, ...fields: unknown[]) => ({ type, label, fields, named: false });

// What a form or a section of a draft may hold
const FORM_ITEMS = [
  creatable("sch:Section", "Section", text("title", "Title", true)),
  creatable("sch:Question", "Question", text("text", "Question", true, true)),
];

export const CONTENT: Record<string, Record<string, unknown>> = {
  // A draft, where parts and options may be added, removed and moved
  "study/v3": {
    "sling:resourceType": "sch/SchemaVersion",
    "@creatable": [
      creatable("sch:FormRequirement", "Form", text("label", "Label", true)),
      creatable("sch:DocumentRequirement", "Document", text("label", "Label", true)),
    ],
    "intake": requirement("FormRequirement", {
      "label": "Intake", "@events": [ "create", "discard", "move", "rename", "update" ],
      "@creatable": FORM_ITEMS,
      "name": question({
        "jcr:uuid": "uuid-name", "text": "Your name", "@events": [ "create", "discard", "move", "rename", "update" ],
        "@fields": [ text("text", "Question", true) ],
        "@creatable": [ unnamed("sch:AnswerOption", "Option", text("value", "Value", true)) ],
        "short": { ...option("short", "Short"), "@events": [ "discard", "move", "update" ] },
        "full": { ...option("full", "Full"), "@events": [ "discard", "move", "update" ] },
      }),
      "age": question({ "jcr:uuid": "uuid-age", "text": "Your age", "@events": [ "condition", "discard", "move" ] }),
    }),
    "followUp": requirement("FormRequirement", {
      "label": "Follow-up", "@events": [ "condition", "create", "discard", "move", "update" ], "@creatable": FORM_ITEMS,
      "cond:condition": single("includes", operand("tags"), operand("literal", "draft")),
    }),
  },
  "study/v2": {
    "link:links": { "jcr:primaryType": "link:Links" },
    "basics": requirement("FormRequirement", {
      "label": "Basic information",
      "description": "About the study",
      "design": {
        "jcr:primaryType": "sch:Section", "sling:resourceType": "sch/Section",
        "sling:resourceSuperType": "sch/FormItem",
        "title": "Design",
        "arms": question({
          "jcr:uuid": "uuid-arms", "text": "Which arms does it have?", "minAnswers": 1, "maxAnswers": 0,
          "displayMode": "list", "@events": [ "update" ],
          "@fields": [ text("text", "Question", true, true) ],
          "placebo": { ...option("placebo", "Placebo"), "description": "A substance with no effect",
            "@events": [ "update" ],
            "@fields": [ text("label", "Label", false) ] },
          "drug": option("drug"),
        }),
        "age": question({
          "text": "Minimum age", "dataType": "long", "minAnswers": 2, "maxAnswers": 3, "minValue": 18, "maxValue": 99,
          "cond:condition": single("includes", operand("answer", "uuid-arms"), operand("literal", "placebo")),
        }),
        "code": question({
          "text": "Study code", "dataType": "exotic", "pattern": "^[A-Z]+$", "patternMessage": "Capitals only",
          "cond:condition": single("is not empty", operand("answer", "basics/design/arms")),
        }),
        "site": question({ "text": "Site", "optionsFrom": "/Sites", "minValue": 1 }),
        "lead": question({ "text": "Lead", "maxValue": 5, "chief": option("chief", "Chief") }),
        "note": question({ "text": "Anything else?" }),
      },
    }),
    "consent": requirement("DocumentRequirement", {
      "label": "Consent form", "required": false, "acceptedFileTypes": [ "application/pdf" ],
      "template": { "jcr:primaryType": "nt:file" },
      "cond:condition": {
        "jcr:primaryType": "cond:ConditionGroup", "requireAll": true,
        "first": single("equals", operand("tags"), operand("literal", "urgent", "7")),
        "nested": {
          "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition",
          "a": single("greater or equal", operand("property", "size"), operand("literal", "10")),
          "b": single("sounds like", operand("answer", "nowhere"), operand("literal")),
        },
      },
    }),
    "protocol": requirement("DocumentRequirement", { label: "Protocol" }),
    "reb": requirement("ApprovalRequirement", {
      "label": "Ethics approval", "approverGroup": "reb-members",
      "cond:condition": { "jcr:primaryType": "cond:ConditionGroup", "requireAll": false },
    }),
    "sign": requirement("ApprovalRequirement", {
      label: "Sign-off",
      "cond:condition": { "jcr:primaryType": "cond:ConditionGroup", "requireAll": true },
    }),
    "audit": requirement("AuditRequirement", {
      label: "Audit", "cond:condition": { "jcr:primaryType": "cond:Mystery" },
    }),
  },
};

const LIFECYCLE = {
  tags: [
    { name: "draft", label: "Draft" },
    { name: "active", label: "Active" },
    { name: "retired", label: "Retired" },
  ],
};

const json = (url: string, body: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, url, redirected: false, json: () => Promise.resolve(body),
} as unknown as Response);

// A node as the serializer identifies it, with its children identified too
export function withPaths(path: string, node: Record<string, unknown>): Record<string, unknown> {
  const identified: Record<string, unknown> = { "@path": path, "@name": path.split("/").at(-1) };
  Object.entries(node).forEach(([ key, value ]) => {
    identified[key] = typeof value === "object" && value !== null && !Array.isArray(value)
      ? withPaths(`${path}/${key}`, value as Record<string, unknown>) : value;
  });
  return identified;
}

// Installs the server. `homepage` is what /Schemas serves; a schema is served from its own entry.
// `answers` picks the answer to an event by the URL it is posted to; `failReads` makes every read of
// schemas fail with that status, and `failContent` only the reads of a whole version.
export function serveSchemas(
  { homepage = HOMEPAGE, answers = {}, failReads, failContent }: {
    homepage?: Record<string, unknown>;
    answers?: Record<string, EventAnswer>;
    failReads?: number;
    failContent?: number;
  } = {},
): PostedEvent[] {
  const posted: PostedEvent[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    if (url === SESSION_INFO_URL) {
      return json(url, { userID: "admin" });
    }
    if (url.startsWith("/Tags.search.json")) {
      return json(url, LIFECYCLE);
    }
    if (url.startsWith("/search.json")) {
      return json(url, { rows: WORKFLOWS });
    }
    // Only the app's own requests: the data grid also posts its vendor's telemetry
    if (init?.method === "POST" && url.startsWith("/")) {
      posted.push({ url, params: new URLSearchParams(init.body as URLSearchParams) });
      const answer = answers[url] ?? {};
      if (answer.redirect) {
        return Promise.resolve({
          ok: true, status: 200, url: `http://localhost${answer.redirect}`, redirected: true,
        } as unknown as Response);
      }
      return answer.status && answer.status >= 400
        ? json(url, answer.error ? { error: answer.error } : {}, answer.status)
        : json(url, { status: "completed" });
    }
    if (failReads) {
      return json(url, {}, failReads);
    }
    if (url.startsWith("/Schemas.paginate.json")) {
      const rows = Object.entries(homepage)
        .filter(([, value]) => (value as Record<string, unknown>)["jcr:primaryType"] === "sch:Schema")
        .map(([name, value]) => withPaths(`/Schemas/${name}`, value as Record<string, unknown>));
      return json(url, { rows, offset: 0, limit: 25, returnedrows: rows.length, totalrows: rows.length,
        totalIsApproximate: false });
    }
    if (url.startsWith("/Schemas.")) {
      return json(url, withPaths("/Schemas", homepage));
    }
    const version = /^\/Schemas\/([^./]+)\/([^./]+)\.deep/.exec(url);
    if (version) {
      if (failContent) {
        return json(url, {}, failContent);
      }
      const [ , schema, name ] = version;
      const node = (homepage[schema] as Record<string, unknown> | undefined)?.[name];
      return node
        ? json(url, withPaths(`/Schemas/${schema}/${name}`, { ...node, ...CONTENT[`${schema}/${name}`] }))
        : json(url, {}, 404);
    }
    const name = /^\/Schemas\/([^./]+)\./.exec(url)?.[1] ?? "";
    return name in homepage
      ? json(url, withPaths(`/Schemas/${name}`, homepage[name] as Record<string, unknown>)) : json(url, {}, 404);
  }));
  return posted;
}
