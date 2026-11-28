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
// PRETOTYPE. A citation as text in which each TCPS 2 reference leads to it: the copy kept on the platform opens over
// the page at that article, the passage marked, with the way to the same place on the official site.

import { Fragment, useMemo, useState } from "react";

import OpenInNewOutlined from "@mui/icons-material/OpenInNewOutlined";
import { Link } from "@mui/material";

import PdfViewer from "@iap/documents-pdf-viewer/PdfViewer";

import { type PolicyPlace, TCPS2_PDF, TCPS2_TITLE, citationParts } from "./tcps2";

export default function PolicyCitation({ citation }: { citation: string }) {
  const [ open, setOpen ] = useState<PolicyPlace>();
  return (
    <>
      {citationParts(citation).map((part, index) => (part.place ? (
        <Link key={index} component="button" variant="inherit" sx={{ verticalAlign: "baseline" }}
          onClick={() => setOpen(part.place)}>
          {part.text}
        </Link>
      ) : <Fragment key={index}>{part.text}</Fragment>))}
      {open && <PolicyViewer place={open} onClose={() => setOpen(undefined)} />}
    </>
  );
}

function PolicyViewer({ place, onClose }: { place: PolicyPlace; onClose: () => void }) {
  const marks = useMemo(() => (place.find
    ? [ { id: "policy", kind: "annotation" as const, quote: place.find, page: place.page } ] : []), [ place ]);
  return (
    <PdfViewer
      url={TCPS2_PDF} page={place.page} title={`${TCPS2_TITLE}, ${place.label}`} marks={marks}
      selectedMarkId={place.find ? "policy" : undefined} markName={{ one: "reference", many: "references" }}
      onClose={onClose}
      leadEnd={(
        <Link href={place.web} target="_blank" rel="noopener noreferrer" variant="body2"
          sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, whiteSpace: "nowrap" }}>
          On the official site<OpenInNewOutlined sx={{ fontSize: "1rem" }} />
        </Link>
      )}
    />
  );
}
