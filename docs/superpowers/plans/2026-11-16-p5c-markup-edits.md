# P5c: circle-to-edit (multimodal follow-ups)

Branch: p5c-markup. Decision 8 in docs/decisions.md ("文字 + 在画面上圈选").

## Flow

1. Project page, "圈选修改": the platform asks the game frame for a snapshot. The frame is an opaque origin, so
   the parent cannot read its canvas; a small bridge (`/bridge.js`, injected into preview pages next to the hi-DPI
   shim) answers a `playforge:capture` message with the largest canvas as a PNG and its logical size. The reply is
   accepted only from that frame and with the request's nonce.
2. The frozen snapshot covers the stage; the player drags up to 3 boxes (numbered), then writes the request.
3. Sending: the page draws the numbered red boxes onto the snapshot and posts the follow-up with
   `markup = { image: data:image/png;base64, canvas: {width, height}, boxes: [{x, y, w, h} normalised 0..1] }`.
4. Backend validates (PNG signature, ≤ 4 MB, 1–3 boxes inside 0..1), stores the image at
   `<workspace-root>-attachments/<runId>.png` (outside every game repo), the boxes and canvas in `runs.markup`
   (Flyway V2), and serves it at `GET /api/runs/{id}/markup.png`. RunView carries the markup.
5. Before each CODE/FIX step of such a run, the orchestrator writes the image into the sandbox
   (`/tmp/playforge/marked.png`) and the prompt says where it is and gives every box in game-canvas pixels
   ("框 1：x 120–200，y 40–80，画布 480×320"). If the image cannot be written, the coordinates still go.
6. The story's "修改需求" entry shows the marked screenshot.

## Limits (accepted)

WebGL games without `preserveDrawingBuffer` and canvases tainted by cross-origin images cannot be captured: the
page says so and the player can still describe the change in words.

## Tasks

1. Backend: V2 migration, markup DTO + validation, image store, markup.png endpoint, RunView. Tests.
2. Orchestrator: write image into the sandbox, prompt section with pixel boxes for CODE and FIX. Tests.
3. Preview bridge + injection. Test (controller) + real check in Edge.
4. Web: capture in GameStage, MarkupLayer (draw/clear/cancel), composer chip, annotated PNG, API. Tests.
5. Story shows the marked screenshot; Edge screenshots desktop/phone.
6. Real end-to-end: mark an area on a real game, send, check Claude's change. Docs, merge, deploy.
