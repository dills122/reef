// Promise-based delay shared by dev scripts. Dependency-free so any script can import it.
export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
