# shared/ — contracts used by both apps

| File | What | Owner / generator |
|------|------|-------------------|
| `watch-plan.schema.json` | WatchPlan payload of `plan.put` (protocol §4) | hand-written, mirrors `docs/protocol.md` |
| `result.schema.json` | Part Result payload of `result.put` (§5) | hand-written |
| `session.schema.json` | SessionSummary payload of `session.put` (§5.4) | hand-written |
| `message.schema.json` | wire envelope + ack/hello/chunk payloads (§2–3) | hand-written |
| `workout.schema.json` | canonical Workout (phone-side, parser structured output) | produced by `android/domain` |
| `movements.json` | movement catalog, Appendix E (REQ-DM12/DM20) | `python3 tools/gen_catalog.py` |
| `benchmarks.json` | benchmark seed, Appendix G (REQ-DM13, v1) | `python3 tools/gen_catalog.py` |
| `fixtures/H1…H10/input.txt` | whiteboard text from Appendix H (REQ-AX12) | hand-written |
| `fixtures/wire/valid`, `fixtures/wire/invalid` | golden wire messages (REQ-TS08) | `cd tools && poetry run gen-wire-fixtures` |

Rules (REQ-NF10): a change that alters persisted or wire meaning bumps the relevant integer
version (`sv` for payload schemas, `v` for the protocol) and updates fixtures in the same
change. Check everything with `task contract:test`.
