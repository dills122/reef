import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

function requiredText(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`missing trusted workflow_run metadata: ${label}`);
  }
  return value;
}

function isExactWorkflowRunMatch(pr, metadata) {
  return pr?.state === "open"
    && pr?.head?.repo?.full_name === metadata.headRepository
    && pr?.head?.ref === metadata.headBranch
    && pr?.head?.sha === metadata.headSha
    && pr?.base?.repo?.full_name === metadata.baseRepository;
}

export function resolveTrustedPullRequest({
  eventName,
  dispatchPrNumber,
  eventPrNumber,
  workflowHeadRepository,
  workflowHeadBranch,
  workflowHeadSha,
  expectedBaseRepository,
  candidates = [],
}) {
  if (eventName === "workflow_dispatch") {
    if (!/^[1-9][0-9]*$/.test(dispatchPrNumber ?? "")) {
      throw new Error("workflow_dispatch requires a valid pr_number");
    }
    return dispatchPrNumber;
  }

  if (eventName !== "workflow_run") {
    throw new Error(`unsupported workflow event: ${eventName || "missing"}`);
  }

  const metadata = {
    headRepository: requiredText(workflowHeadRepository, "head repository"),
    headBranch: requiredText(workflowHeadBranch, "head branch"),
    headSha: requiredText(workflowHeadSha, "head SHA"),
    baseRepository: requiredText(expectedBaseRepository, "base repository"),
  };
  const candidateList = Array.isArray(candidates) ? candidates : [candidates];
  const exactMatches = candidateList.filter((pr) => isExactWorkflowRunMatch(pr, metadata));

  if (eventPrNumber) {
    if (!/^[1-9][0-9]*$/.test(eventPrNumber)) {
      throw new Error("workflow_run contains an invalid pull request number");
    }
    const eventMatches = exactMatches.filter((pr) => String(pr?.number) === eventPrNumber);
    if (eventMatches.length !== 1) {
      throw new Error("workflow_run pull request does not exactly match trusted run metadata");
    }
    return eventPrNumber;
  }

  if (exactMatches.length !== 1) {
    throw new Error(`expected exactly one open pull request matching trusted run metadata; found ${exactMatches.length}`);
  }
  const resolvedNumber = String(exactMatches[0]?.number ?? "");
  if (!/^[1-9][0-9]*$/.test(resolvedNumber)) {
    throw new Error("matched pull request has an invalid number");
  }
  return resolvedNumber;
}

async function main() {
  const candidatePath = process.argv[2];
  const candidates = candidatePath
    ? JSON.parse(await readFile(candidatePath, "utf8"))
    : [];
  const prNumber = resolveTrustedPullRequest({
    eventName: process.env.GITHUB_EVENT_NAME,
    dispatchPrNumber: process.env.DISPATCH_PR_NUMBER,
    eventPrNumber: process.env.EVENT_PR_NUMBER,
    workflowHeadRepository: process.env.WORKFLOW_HEAD_REPOSITORY,
    workflowHeadBranch: process.env.WORKFLOW_HEAD_BRANCH,
    workflowHeadSha: process.env.WORKFLOW_HEAD_SHA,
    expectedBaseRepository: process.env.EXPECTED_BASE_REPOSITORY,
    candidates,
  });
  process.stdout.write(`pr-number=${prNumber}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`bot-submission-resolve-pr: ${error.message}`);
    process.exitCode = 1;
  });
}
