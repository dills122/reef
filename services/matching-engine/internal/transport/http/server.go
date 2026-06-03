package http

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

const maxRequestBodyBytes int64 = 1 << 20

type Server struct {
	service *app.Service
}

func NewServer(service *app.Service) *Server {
	return &Server{service: service}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/orders/submit", s.handleSubmitOrder)
	mux.HandleFunc("/orders/cancel", s.handleCancelOrder)
	mux.HandleFunc("/orders/modify", s.handleModifyOrder)
	return mux
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"service": "matching-engine",
		"status":  "ok",
	})
}

func (s *Server) handleSubmitOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	defer r.Body.Close()

	var cmd domain.SubmitOrder
	if !decodeJSONBody(w, r, &cmd) {
		return
	}

	result := s.service.SubmitOrder(cmd)
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleCancelOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	defer r.Body.Close()

	var cmd domain.CancelOrder
	if !decodeJSONBody(w, r, &cmd) {
		return
	}

	result := s.service.CancelOrder(cmd)
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) handleModifyOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	defer r.Body.Close()

	var cmd domain.ModifyOrder
	if !decodeJSONBody(w, r, &cmd) {
		return
	}

	result := s.service.ModifyOrder(cmd)
	writeJSON(w, http.StatusOK, result)
}

func decodeJSONBody(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodyBytes)

	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{
				"error": "request body too large",
			})
			return false
		}
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "invalid json payload",
		})
		return false
	}

	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "invalid json payload",
		})
		return false
	}

	return true
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
