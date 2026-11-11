# Vikify artwork sources and license

Publisher: VictoriaV. Project artwork is distributed under the MIT license in
the repository root, to the extent that copyright or related rights apply.
No exclusive rights over machine-generated subject matter are asserted.

On 2026-11-11 the publisher confirmed that the older project artwork other than
the former launcher icon was free of third-party rights and allowed MIT after
replacement of that icon. The former `ic_launcher_colibri.jpg` had an unknown
origin. It has been removed from current source and builds; merely recoloring
it was not used as a substitute for establishing provenance.

| Source PNG | Created | Provenance | Used for |
|---|---|---|---|
| `source/launcher-hummingbird.png` | 2026-11-11 | New built-in OpenAI Image Generation, no reference image supplied | Launcher foreground and store icon |
| `source/lagoon-portrait.png` | 2026-11-03 | Built-in OpenAI Image Generation output `exec-7c393075-9862-44b2-b0e7-7a237b0ace4b.png` | Portrait lagoon WebP, including flowers |
| `source/lagoon-landscape.png` | 2026-11-03 | Built-in OpenAI Image Generation output `exec-6affa4af-bea6-4f0e-ad44-2670028d37b7.png` | Landscape lagoon WebP, including flowers |
| `source/hummingbird-parts.png` | 2026-11-03 | Built-in OpenAI Image Generation output `exec-2120794b-c04f-4356-a4c5-a13cbae563b1.png` | Body/wing cells of `hummingbird_flight.webp` |

The generated source files were retained by the tool in this project's image
generation directory and visually checked against the application assets.
The hummingbird's source and generation process were also recorded in the
project's November 3 UI report. Its prompt is `hummingbird-parts-prompt.txt`.
The older source used as its species/style reference was itself generated for
the same project, not the unknown-origin launcher icon.

Compose animation and vector scene drawing are project source code under MIT.
Store screenshots are captures of Vikify's own UI on the test phone; they
contain no subscription URL, token, UUID or working credentials. Upstream
native-library artwork and documentation remain covered by their own licenses.

## New launcher prompt (built-in tool, not API/CLI fallback)

Use case: logo-brand. Create a BRAND NEW, independently designed square Android launcher icon foreground for Vikify, an open source VPN app with a tropical sunset and hummingbird theme. Do not use or recreate any existing hummingbird logo or previously shown image. A single original elegant hummingbird in side view facing right, long delicate beak, compact curved body and one anatomically plausible pair of wings swept upward; smooth simplified overlapping feather shapes, friendly sophisticated silhouette, readable at 48 pixels. Palette: deep emerald and turquoise body, warm pale gold wing accents, cream breast, tiny dark eye. Refined flat raster illustration with gentle restrained shading, no heavy black outlines, no jagged feather pattern. Center the entire bird in the central 76% of a square with clear transparent margins so it works inside circular Android icon masks. Transparent background with real alpha, no white matte, no badge, no lettering, no text, no watermark, no flowers, no shadows outside the bird. This is a new design, not a recoloring or tracing of another image.
