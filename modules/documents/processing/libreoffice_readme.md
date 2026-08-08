# LibreOffice document conversion in IAP

LibreOffice runs inside the **Python parsing service** (daemon or CLI).
`libreoffice_convert.py` shells out to headless `soffice` before Docling starts. **This**
**module is expected to mostly be run from Docker in deployments, but we provide non-Docker**
**installation instructions below for posterity.**

## Conversions (saved beside the source immediately)

| Incoming | LibreOffice writes               | Docling input    |
| -------- | -------------------------------- | ---------------- |
| `.doc`   | `{stem}.docx`, then `{stem}.pdf` | `{stem}.docx`    |
| `.docx`  | `{stem}.pdf`                     | original `.docx` |
| `.pdf`   | (none)                           | original `.pdf`  |

### Installation

1. If interested in using CLI install LibreOffice from https://www.libreoffice.org/download/ or:

   ```
   # Debian / Ubuntu
   sudo apt update
   sudo apt install libreoffice
   ```

   or (Dockerfile / non-interactive):

   ```
   apt-get update && \
     apt-get install -y libreoffice libreoffice-java-common default-jre-headless && \
     rm -rf /var/lib/apt/lists/*

   The Docling image already installs ``libreoffice-writer``, ``libreoffice-java-common``,
   and ``default-jre-headless`` — Writer PDF export needs a JVM even for headless
   ``docx`` → ``pdf``.
   ```

2. Ensure `soffice` is on the system PATH (or set `IAP_LIBREOFFICE_SOFFICE`). Common locations:
   - Windows: `C:/Program Files/LibreOffice/program/soffice.exe`
   - Windows: `C:/Program Files (x86)/LibreOffice/program/soffice.exe`
   - Linux: `/usr/bin/soffice`
   - Linux: `/usr/lib/libreoffice/program/soffice`
   - macOS: `/Applications/LibreOffice.app/Contents/MacOS/soffice`

   `soffice` must be on the PATH inside the Docling container (the Dockerfile installs
   `libreoffice-writer`) or on the host when using the CLI. Override the executable with:

   ```
   IAP_LIBREOFFICE_SOFFICE=/usr/bin/soffice
   # or
   LIBREOFFICE_PATH=/usr/bin/soffice
   ```

   On Windows (CLI / local daemon):

   ```
   set IAP_LIBREOFFICE_SOFFICE=C:\Program Files\LibreOffice\program\soffice.exe
   ```

3. Test:

   Windows:

   ```
   "C:\Program Files\LibreOffice\program\soffice.exe" --version
   "C:\Program Files\LibreOffice\program\soffice.exe" ^
     --headless ^
     --convert-to docx ^
     --outdir C:\output ^
     C:\input\test.doc
   ```

   Linux:

   ```
   soffice --version
   soffice --headless \
     --convert-to docx \
     --outdir output \
     input.doc
   ```

### Configuration (env)

| Variable                                       | Default        | Purpose                            |
| ---------------------------------------------- | -------------- | ---------------------------------- |
| `IAP_LIBREOFFICE_SOFFICE` / `LIBREOFFICE_PATH` | `soffice`      | LibreOffice executable             |
| `IAP_SHARED_DOCS`                              | `/shared-docs` | Shared staging + parse output root |

Nothing starts the daemon for you. When the daemon cannot be reached, parsing fails. There is no
fallback processor.

---

## Shared volume

The caller and the Docling daemon share **`/shared-docs`** (env `IAP_SHARED_DOCS`). Layout:

```
/shared-docs/{uuid}/
  {stem}.pdf|.docx|.doc     # staged by the caller
  {stem}.docx / {stem}.pdf  # LibreOffice conversions (Python)
  {stem}.md                 # write_chunk_files only
  Chunks/                   # write_chunk_files only
```
