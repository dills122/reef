# Reef Brand Assets (Ready)

This folder contains cleaned logo/app-icon crops and a theming-ready SVG icon set.

## Paths

- PNG assets: `/Users/dsteele/repos/reef/extracted_assets/ready/png`
- SVG icons: `/Users/dsteele/repos/reef/extracted_assets/ready/svg`
- Theme tokens (CSS): `/Users/dsteele/repos/reef/extracted_assets/ready/theme.tokens.css`
- Theme tokens (JSON): `/Users/dsteele/repos/reef/extracted_assets/ready/theme.tokens.json`

## Cleaned Logo + App Assets

Logos (transparent variants included):
- `logo_primary_full_transparent.png`
- `logo_variant_square_transparent.png`
- `logo_variant_mark_transparent.png`
- `logo_variant_wordmark_horizontal_transparent.png`
- `logo_social_og_square_transparent.png`

Banners:
- `logo_github_banner.png`

App icons:
- `app_icon_round_dark.png`
- `app_icon_round_alt.png`
- `app_icon_light.png`
- `app_icon_gradient.png`
- `app_icon_square_dark.png`

## SVG Icon Theming

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
