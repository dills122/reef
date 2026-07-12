export function expectedRolesForRunProfile(runProfile) {
  switch (runProfile) {
    case "materializer-soak":
      return {
        workers: "stopped or disabled",
        materializers: "online",
        projectors: "optional unless read-model freshness is being measured",
      };
    case "direct-nodb":
      return {
        workers: "not expected",
        materializers: "not expected",
        projectors: "not expected",
      };
    case "stream-ack":
      return {
        workers: "enabled",
        materializers: "not expected",
        projectors: "running",
      };
    default:
      return {
        workers: "profile-specific",
        materializers: "profile-specific",
        projectors: "profile-specific",
      };
  }
}
