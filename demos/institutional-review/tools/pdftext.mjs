// Copyright 2026 DATA @ UHN. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Writes each PDF's text as PDF.js reads it, page by page, for check_content.py. PRETOTYPE.
// node pdftext.mjs <pdf.js build/pdf.mjs> <out dir> <pdf>...
const { getDocument } = await import(process.argv[2]);
import { readFileSync, writeFileSync } from "node:fs";
import { basename } from "node:path";
for (const f of process.argv.slice(4)) {
  const doc = await getDocument({ data: new Uint8Array(readFileSync(f)), verbosity: 0 }).promise;
  let out = "", words = 0;
  for (let i = 1; i <= doc.numPages; i++) {
    const tc = await (await doc.getPage(i)).getTextContent();
    const t = tc.items.map(x => x.str + (x.hasEOL ? "\n" : "")).join("");
    words += t.split(/\s+/).filter(Boolean).length;
    out += "\n=== page " + i + "\n" + t;
  }
  writeFileSync(process.argv[3] + "/" + basename(f, ".pdf") + ".txt", out);
  console.log(basename(f, ".pdf"), "pages=" + doc.numPages, "words=" + words);
}
