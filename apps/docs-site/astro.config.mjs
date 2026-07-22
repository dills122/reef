import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

const site = process.env.DOCS_SITE_URL;
const base = process.env.DOCS_SITE_BASE;
const repository = process.env.DOCS_SITE_REPOSITORY ?? process.env.GITHUB_REPOSITORY ?? "dills122/reef";
const editBranch = process.env.DOCS_SITE_EDIT_BRANCH ?? process.env.GITHUB_REF_NAME ?? "master";
const repositoryUrl = `https://github.com/${repository}`;

export default defineConfig({
  ...(site ? { site } : {}),
  ...(base ? { base } : {}),
  integrations: [
    starlight({
      title: "Reef",
      tagline: "Simulation-first institutional trading venue and post-trade platform",
      description:
        "Reef project overview, bot arena/game docs, trading API surface, and data schema reference. Pre-release, under heavy development.",
      customCss: ["./src/styles/reef.css"],
      social: [
        { icon: "github", label: "GitHub", href: repositoryUrl },
      ],
      editLink: {
        baseUrl: `${repositoryUrl}/edit/${editBranch}/apps/docs-site/`,
      },
      sidebar: [
        {
          label: "Overview",
          items: [
            { label: "What Is Reef", slug: "overview/what-is-reef" },
            { label: "Architecture", slug: "overview/architecture" },
            { label: "Current Status", slug: "overview/status" },
            { label: "Developer Setup", slug: "overview/developer-setup" },
          ],
        },
        {
          label: "Bot Arena (WIP)",
          items: [
            { label: "Overview", slug: "arena/overview" },
            { label: "How To Play", slug: "arena/how-to-play" },
            { label: "Bot SDK Quickstart", slug: "arena/bot-sdk-quickstart" },
            { label: "Bot SDK Reference", slug: "arena/bot-sdk-reference" },
            { label: "How The Game Works", slug: "arena/how-the-game-works" },
          ],
        },
        {
          label: "Trading API",
          items: [
            { label: "Overview", slug: "api/overview" },
            { label: "Orders", slug: "api/orders" },
            { label: "Market Data", slug: "api/market-data" },
            { label: "Settlement", slug: "api/settlement" },
            { label: "Command Status", slug: "api/commands" },
            { label: "Internal & Admin Routes", slug: "api/internal-admin" },
          ],
        },
        {
          label: "Data Schema",
          items: [
            { label: "Overview", slug: "schema/overview" },
            { label: "Runtime Schema", slug: "schema/runtime-schema" },
            { label: "Boundary, Auth & Admin Schema", slug: "schema/boundary-auth-admin-schema" },
            { label: "Planned Schema", slug: "schema/planned-schema" },
            { label: "Wire Contracts (Protobuf)", slug: "schema/contracts" },
          ],
        },
      ],
    }),
  ],
});
