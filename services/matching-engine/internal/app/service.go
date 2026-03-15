package app

import (
	"fmt"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct{}

func NewService() *Service {
	return &Service{}
}

func (s *Service) SubmitOrder(cmd domain.SubmitOrder) domain.SubmitOrderResult {
	now := time.Now().UTC().Format(time.RFC3339)

	if cmd.OrderID == "" {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-missing-order-id",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "orderId is required",
				OccurredAt: now,
			},
		}
	}

	if cmd.InstrumentID == "" {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-missing-instrument",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "instrumentId is required",
				OccurredAt: now,
			},
		}
	}

	if cmd.Side != domain.SideBuy && cmd.Side != domain.SideSell {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-invalid-side",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "side must be BUY or SELL",
				OccurredAt: now,
			},
		}
	}

	return domain.SubmitOrderResult{
		Accepted: &domain.OrderAccepted{
			EventID:       fmt.Sprintf("evt-order-accepted-%s", cmd.OrderID),
			OrderID:       cmd.OrderID,
			EngineOrderID: fmt.Sprintf("eng-%s", cmd.OrderID),
			OccurredAt:    now,
		},
	}
}
