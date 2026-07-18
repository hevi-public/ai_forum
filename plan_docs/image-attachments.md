# Image attachments — upload on posts/comments, caption-only to the LLM

Status: **shipped** (2026-06).

Goal: let the owner attach images to the thread opening post and to comments/notes, and let an image
*selectively* inform the discussion — without assuming the generation models can see images. Local
router/persona models are usually **not** vision-capable, so the raw image is never sent to them. Instead
a separate vision model captions the image to **text**, and that caption is what reaches the room.

## The caption-only design (the important bit)

Images are caption-only to the model. A vision model (the `ImageDescriber` seam) turns an image into a
text caption; that caption is folded into the **owner's message body** at the firewall boundary
(`ContextAssembler`). Raw bytes never reach a generation model, so **any** model works.

- `DESCRIBED` → the owner's message body carries `\n\n[Image: <caption>]`.
- otherwise → `\n\n[Image attached (no description)]`, so the model knows an image exists even if it
  can't see it.

This is firewall-safe (§7/§13): it adds image text the **owner** authored — never a vote or owner-identity
signal — so the existing `+1` firewall is unchanged. Images are owner-only anyway (personas emit text),
so an attachment always hangs off an owner node. The generation clients (`OpenAiLlmClient`,
`ProcessLlmClient`) stay strictly text-only; the **only** place a multimodal payload is built is the
vision seam.

## Seams & components

- **`ImageStore`** (`com.aiforum.images`) — local-disk blob store. NOT a faked seam (plain filesystem IO,
  exercised at Tier 2 against a temp dir). Content-addressed: `sha256(bytes)` is the filename, sharded two
  levels (`images/ab/cd/<sha>.<ext>`) — automatic dedup, immutable cache-forever URL, and the on-disk path
  **never** derives from a user filename (no path-traversal surface). Ingest sanitises: type from **magic
  bytes** (not the Content-Type/filename), non-images and oversize rejected, jpeg/png re-encoded through
  ImageIO to strip EXIF/metadata.
- **`ImageDescriber`** (`com.aiforum.images`) — the **second** Tier-1 IO seam (sibling of `LlmClient`),
  the only place a vision payload exists. Real `OpenAiImageDescriber` posts an OpenAI-compatible
  `image_url` (base64 data-URI) content block; under `test` a scriptable fake stands in (reset by
  `DatabaseResetHooks`). Always loaded under `!test` so a bean always exists — when vision is unavailable
  it throws `VisionUnavailableException`, which the service turns into a graceful `FAILED` caption.
- **`AttachmentService`** — store-and-link on upload; the manual describe lifecycle.
- **`AttachmentController`** — `GET /attachments/{id}` (serve bytes by id → never a client path; immutable
  long cache) and `POST /attachments/{id}/describe` (manual caption, re-renders the gallery cell).

## Data — `V13__attachment.sql`

An `attachment` row hangs off exactly one owner node — `CHECK ((thread_id IS NOT NULL) <> (comment_id IS
NOT NULL))`. Columns: `sha256` + `storage_path` (disk), `mime_type`/`byte_size`/`original_filename`,
`caption`/`caption_model`/`caption_state` (`NONE → DESCRIBING → DESCRIBED | FAILED`, mirroring the comment
lifecycle), `sort_order`. Deletes are FK-safe: `CommentRepository.deleteSubtree`/`deleteByThread` and
`ThreadRepository.delete` clear attachment rows before the rows they reference (`foreign_keys=on`). The
content-addressed blobs on disk are left for a future dedup-aware GC.

## Upload wiring (multipart, additive)

Browser forms post `multipart/form-data`; the existing **urlencoded + JSON** handlers were *kept* and
multipart handlers *added* alongside, so the acceptance suite (which posts urlencoded/JSON directly) is
untouched. Covered: the new-thread OP (`/threads`), `/note`, and `/generate`. For `/generate` with an
image the owner node is created in the controller, the image attached to it, then the room summoned
*beneath* it (`postAsOwner=false`) — which sidesteps passing a `MultipartFile` across the async summon
worker. Multipart size caps live in `application.yml` (`spring.servlet.multipart.*`) plus the in-app
`aiforum.images.max-bytes` guard.

## Rendering

`AttachmentView` carries `src` (the serve endpoint), the raw `caption` (used as the `<img alt>` and what's
injected into context) and `captionHtml` — the caption run through `MarkdownRenderer` (see
[markdown-rendering.md](markdown-rendering.md)) and wrapped in a semantic `<blockquote
class="attachment__caption">`. So the caption reads as a **quote**, and a vision model that transcribes a
code screenshot into a fenced block renders it as a **real syntax-highlighted code block inside the
quote** — same XSS firewall as a body (`escapeHtml(true)`). The gallery (`fragments/attachments.kte` →
`fragments/attachment.kte`, included under the body in `replyNode.kte` and `threadOp.kte`) caps each image
(`max-width`/`max-height` 320px, `object-fit: contain`) so a large screenshot can't break the page.

## Configuration

```yaml
aiforum:
  images:
    dir: ${user.home}/.ai_forum/data/images   # prod; dev=data/images-dev, test=build/images-test (ProfileGuard-asserted)
    max-bytes: 10485760                    # 10 MiB
    describe:
      enabled: true                        # captioning on by default; needs a vision model served below
    vision:
      base-url: ${aiforum.llm.openai.base-url:...}   # falls back to the generation OpenAI server (one LM Studio serves both)
      model: ""                            # blank => the server's loaded model (LM Studio); set an id for hosted servers
      prompt: >-                           # asks the model to transcribe code into a fenced block
        Describe this image factually …
```

Describe is **manual** (the owner clicks "Describe") and synchronous — a deliberate single action, so the
htmx request waits on the model. The owner brings up a vision-capable model themselves; until one is
served, "Describe" surfaces a clear `FAILED` caption with a `WARN` log line rather than erroring.

## Testing

- **Tier 0** — `ImageCaptionInjectionTest`: caption folding (`[Image: …]` / marker), firewall unchanged.
- **Tier 2** — `ImageStoreTest`: magic-byte sniff/reject, oversize reject, content-address dedup.
- **Acceptance** — `image_attachments.feature`: upload renders a gallery; describe (scriptable vision
  fake) captions it; the caption — **not** the bytes — reaches the model (spy-asserted, no `data:image/`
  in context); a transcribed code caption renders as a highlighted code block inside the quote; deleting a
  note with an image is FK-clean.

## Deferred (not built)

Edit-time add/remove of images; server-side thumbnails; per-persona raw-image passthrough for genuinely
vision-capable models; dedup-aware on-disk blob GC; captions in the **router** context (generation context
has them, `PersonaRouter` does not yet). Backups must include `~/.ai_forum/data/images` (the blobs live
outside the SQLite file).
