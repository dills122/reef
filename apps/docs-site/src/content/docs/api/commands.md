---
title: Command Status API
description: /api/v1/commands/{commandId} — poll durable command outcome after acceptance.
---

## GET /api/v1/commands/{commandId}

Looks up a previously submitted command by ID, across command-log and canonical-outcome storage depending on processing mode.

### Response Shape

```json
{
  "commandId": "...",
  "clientId": "...",
  "route": "/api/v1/orders/submit",
  "idempotencyKey": "...",
  "status": "RECEIVED | PROCESSING | COMPLETED | FAILED",
  "processingMode": "sync-result | captured-ack | stream-ack | accepted-async",
  "responseStatus": 200,
  "responsePayloadJson": "...",
  "lastError": "",
  "canonicalMaterialized": true,
  "engineResultStatus": "accepted | rejected",
  "resultStatus": "accepted | rejected",
  "batchId": "...",
  "shardId": "...",
  "partition": 0,
  "commandStream": "...",
  "eventStream": "...",
  "streamSequence": 0,
  "deliveredCount": 0,
  "commandType": "SubmitOrder",
  "payloadHash": "...",
  "instrumentId": "...",
  "orderId": "...",
  "rejectCode": "",
  "resultPayloadJson": "..."
}
```

`canonicalMaterialized` is `true` once the command's outcome has been durably materialized into compact canonical Postgres storage from the venue event batch (stream-ack mode) rather than only captured at intake.

### Accepted-Response Shape (Async Modes)

When a mutation returns `202 Accepted` in stream-ack/accepted-async mode, the immediate response is a smaller pointer, not the full status:

```json
{
  "commandId": "...",
  "status": "RECEIVED",
  "processingMode": "stream-ack",
  "statusUrl": "/api/v1/commands/{commandId}"
}
```

Poll `statusUrl` to get the full status view above once processing completes.

## Learn More

- [API Overview](/api/overview/) — processing modes and the durable-acceptance contract
- [Orders API](/api/orders/) — the mutation routes this status lookup tracks
