# Reef Astro Steering

## Scope

Astro is the framework for Reef's public-facing documentation and marketing site.

## Product Role

The Astro site should explain:

- what Reef is
- how the architecture fits together
- how simulations and workflows behave
- how to run and explore the platform

It is not the operational UI and should not absorb application behavior that belongs in Angular.

## Content Priorities

Early site content should focus on:

- project overview
- architecture diagrams and bounded contexts
- simulation concepts
- end-to-end lifecycle walkthroughs
- screenshots or workflow narratives as the platform matures

## Design Rules

### Explain the system clearly

The docs site should make it easy to understand the platform quickly, especially the relationship between:

- venue behavior
- post-trade workflows
- simulation control
- auditability

### Treat docs as product surface area

Architecture pages, scenario walkthroughs, and lifecycle explanations are part of the portfolio value of the project.

### Keep implementation honest

Do not market features that the repository does not support yet.
Use the site to document current capability and target direction distinctly.

## Site Structure

Prefer a structure like:

```text
apps/docs-site/src/content/
  docs/
    overview/
    architecture/
    workflows/
    simulation/
```

Use content collections or similarly structured content rather than one large pile of markdown files.

## Content Guidance

- prefer diagrams, timelines, and lifecycle explanations over generic feature lists
- explain both happy-path and exception-path workflows
- keep pages narrowly focused and link related topics clearly
- update docs when architecture decisions shift materially

## Avoid

- duplicating the operational app in the docs site
- mixing unstable implementation notes into user-facing pages without labeling them
- turning the site into a generic blog without project relevance
