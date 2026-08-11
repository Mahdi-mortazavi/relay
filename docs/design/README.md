# Design — Liquid Glass

Reference material for Relay's visual language: translucent, depth-layered glass surfaces; near-monochrome dark-first palette with a single accent; fluid spring-based motion; progressive disclosure (no technical fields in the default view).

- **Cross-platform tokens:** [`/shared/design-tokens.json`](../../shared/design-tokens.json)
- **Windows implementation:** [`Styles/Tokens.xaml`](../../windows/Relay.App/Styles/Tokens.xaml) — the
  live source of truth for the Windows app, with [`Services/Motion.cs`](../../windows/Relay.App/Services/Motion.cs)
  for its motion vocabulary.
- **Windows redesign changelog:** [`windows-redesign.md`](windows-redesign.md) — what changed in the
  popover and why, with before → after reasoning, plus what is and is not verified.
- **Mocks & screenshots:** added here from Phase 1 as the real UI lands.

## Principles

1. **Material honesty** — elevation via blur/translucency, not hard drop shadows; one consistent light source.
2. **Deference** — the interface recedes; state (idle/connecting/connected) is the content.
3. **Restraint** — one accent color, few weights, generous spacing, tight optical alignment.
4. **Legibility over translucency** — contrast requirements always win against the glass effect.
5. **True RTL** — Persian layouts are mirrored, not just translated; numerals and QR-screen copy are locale-correct.
