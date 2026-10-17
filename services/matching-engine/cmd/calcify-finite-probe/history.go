package main

import (
	"encoding/base64"
	"errors"
	"strconv"

	"github.com/IBM/sarama"
)

type historyPhaseError struct {
	phase                                        string
	fetchCount, recordCount, payloadBytes, codec int
	position, end                                int64
	cause                                        error
}

func (e *historyPhaseError) Error() string { return "history phase refused" }

func (e *historyPhaseError) Unwrap() error { return e.cause }

func history(c sarama.Client, q request) (data any, err error) {
	diagnostic := historyPhaseError{phase: "offset_lookup", end: -1, codec: -1}
	defer func() {
		if err != nil {
			diagnostic.cause = err
			err = &diagnostic
		}
	}()
	lo, err := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
	if err != nil {
		return nil, err
	}
	if lo != 0 {
		diagnostic.phase = "lost_genesis"
		return nil, errors.New("lost genesis")
	}
	diagnostic.phase = "leader_lookup"
	broker, err := c.Leader(q.Topic, 0)
	if err != nil {
		return nil, err
	}
	position := int64(0)
	end := int64(-1)
	rows := []any{}
	aborted := map[int64]int64{}
	bytesRead := 0
	for fetch := 0; fetch < 64; fetch++ {
		fr := &sarama.FetchRequest{Version: 11, MaxWaitTime: 250, MinBytes: 1, MaxBytes: 8 * 1024 * 1024, Isolation: sarama.ReadCommitted}
		fr.AddBlock(q.Topic, 0, position, 8*1024*1024, -1)
		diagnostic.phase = "fetch_request"
		diagnostic.fetchCount = fetch + 1
		diagnostic.position = position
		diagnostic.end = end
		response, err := broker.Fetch(fr)
		if err != nil {
			return nil, err
		}
		block := response.GetBlock(q.Topic, 0)
		if block == nil {
			diagnostic.phase = "missing_block"
			return nil, errors.New("missing fetch block")
		}
		if block.Err != sarama.ErrNoError {
			diagnostic.phase = "kafka_error"
			return nil, block.Err
		}
		if end < 0 {
			end = block.LastStableOffset
			diagnostic.end = end
		} else if block.LastStableOffset != end {
			diagnostic.phase = "unstable_cut"
			return nil, errors.New("unstable history cut")
		}
		for _, t := range block.AbortedTransactions {
			if old, ok := aborted[t.ProducerID]; !ok || t.FirstOffset < old {
				aborted[t.ProducerID] = t.FirstOffset
			}
		}
		before := position
		for _, set := range block.RecordsSet {
			batch := set.RecordBatch
			diagnostic.phase = "missing_batch"
			if batch == nil {
				return nil, errors.New("unsupported/partial batch")
			}
			diagnostic.codec = int(batch.Codec)
			diagnostic.phase = "partial_batch"
			if batch.PartialTrailingRecord {
				return nil, errors.New("unsupported/partial batch")
			}
			diagnostic.phase = "unsupported_codec"
			if batch.Codec != sarama.CompressionNone {
				return nil, errors.New("unsupported/partial batch")
			}
			if batch.FirstOffset >= end {
				break
			}
			next := batch.LastOffset() + 1
			if next > position {
				position = next
			}
			if batch.Control {
				if first, ok := aborted[batch.ProducerID]; ok && batch.FirstOffset >= first {
					delete(aborted, batch.ProducerID)
				}
				continue
			}
			if first, ok := aborted[batch.ProducerID]; ok && batch.IsTransactional && batch.FirstOffset >= first {
				continue
			}
			for _, record := range batch.Records {
				offset := batch.FirstOffset + record.OffsetDelta
				if offset >= end || offset < before {
					continue
				}
				bytesRead += len(record.Value)
				diagnostic.recordCount = len(rows)
				diagnostic.payloadBytes = bytesRead
				if len(rows) >= 16 || len(record.Value) > 131072 || bytesRead > 2*1024*1024 {
					diagnostic.phase = "record_budget"
					return nil, errors.New("history budget")
				}
				headers := []map[string]string{}
				for _, h := range record.Headers {
					headers = append(headers, map[string]string{"keyBase64": base64.StdEncoding.EncodeToString(h.Key), "valueBase64": base64.StdEncoding.EncodeToString(h.Value)})
				}
				rows = append(rows, map[string]any{"offset": strconv.FormatInt(offset, 10), "keyBase64": base64.StdEncoding.EncodeToString(record.Key), "valueBase64": base64.StdEncoding.EncodeToString(record.Value), "payloadSha256": digest(record.Value), "headers": headers, "bytes": len(record.Value)})
			}
		}
		if position >= end {
			return map[string]any{"topic": q.Topic, "lowOffset": "0", "readCommittedEndOffset": strconv.FormatInt(end, 10), "scanNextOffset": strconv.FormatInt(position, 10), "records": rows, "payloadBytes": bytesRead}, nil
		}
		if position == before {
			diagnostic.phase = "no_progress"
			diagnostic.position = position
			diagnostic.recordCount = len(rows)
			diagnostic.payloadBytes = bytesRead
			return nil, errors.New("history scan no progress")
		}
	}
	diagnostic.phase = "fetch_budget"
	diagnostic.position = position
	diagnostic.recordCount = len(rows)
	diagnostic.payloadBytes = bytesRead
	return nil, errors.New("history fetch budget")
}
