# Reef Brand Assets (Ready)

This folder contains the current theming-ready SVG icon set and Reef design tokens.

## Paths

- SVG icons: `extracted_assets/ready/svg`
- Theme tokens (CSS): `extracted_assets/ready/theme.tokens.css`
- Theme tokens (JSON): `extracted_assets/ready/theme.tokens.json`

## SVG Icons

Current icons:

- `icon_actors.svg`
- `icon_analytics.svg`
- `icon_execute.svg`
- `icon_reliability.svg`
- `icon_replay.svg`
- `icon_settle.svg`
- `icon_simulate.svg`

All SVG icons are authored with:
- `viewBox="0 0 24 24"`
- `stroke="currentColor"`
- `fill="none"`

This lets you theme through CSS `color` only.

### HTML Example

```html
<link rel="stylesheet" href="theme.tokens.css" />
<div data-theme="dark">
  <img class="icon-reef" src="./svg/icon_simulate.svg" alt="Simulate" width="24" height="24" />
</div>
```

### React Example (preferred)

```tsx
import { ReactComponent as SimulateIcon } from './svg/icon_simulate.svg';

export function Item() {
  return <SimulateIcon style={{ color: 'var(--reef-fg)' }} aria-label="Simulate" />;
}
```

If your bundler does not support SVG React components, inline SVG markup or use SVGR.

## Recommended Next Step

Create a small app-level icon wrapper component to enforce size/color consistency:
- `size`: 16 | 20 | 24
- `tone`: `primary | muted | accent`
- Maps to CSS vars in `theme.tokens.css`
