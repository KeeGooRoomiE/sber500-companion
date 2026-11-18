package admin

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/push"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
)

type pushRequest struct {
	Title string `json:"title"`
	Body  string `json:"body"`
	// Audience: exactly one of UserIDs (named people, dev users allowed) or SilentDays
	// (everyone registered with no activity for that many days, dev users excluded).
	UserIDs    []string `json:"user_ids"`
	SilentDays int      `json:"silent_days"`
	// DryRun only counts the audience. It is the default for anything but named users, so a
	// typo cannot message the whole base: sending to a segment needs "dry_run": false.
	DryRun *bool `json:"dry_run"`
	// Trigger is "scheduled" for automatic jobs and the default "admin" for the owner's own.
	Trigger string `json:"trigger"`
}

type pushResponse struct {
	Audience int          `json:"audience"`
	DryRun   bool         `json:"dry_run"`
	Result   *push.Result `json:"result,omitempty"`
}

// sendPush: POST /admin/push. Sends a notification to named users or to the silent segment.
func (s *Server) sendPush(w http.ResponseWriter, r *http.Request) {
	if s.push == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "push not configured (RUSTORE_PUSH_PROJECT_ID / RUSTORE_PUSH_TOKEN)"})
		return
	}
	var req pushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid json"})
		return
	}
	req.Title, req.Body = strings.TrimSpace(req.Title), strings.TrimSpace(req.Body)
	if req.Title == "" || req.Body == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "title and body are required"})
		return
	}
	named, segment := len(req.UserIDs) > 0, req.SilentDays > 0
	if named == segment {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "give exactly one of user_ids or silent_days"})
		return
	}

	var (
		targets []repo.PushTarget
		err     error
	)
	if named {
		targets, err = s.users.PushTargetsByIDs(r.Context(), req.UserIDs)
	} else {
		targets, err = s.users.PushTargetsSilent(r.Context(), req.SilentDays)
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	dry := segment // segments are counted unless the caller says otherwise
	if req.DryRun != nil {
		dry = *req.DryRun
	}
	resp := pushResponse{Audience: len(targets), DryRun: dry}
	if !dry {
		trigger := analytics.TriggerAdmin
		if req.Trigger == "scheduled" {
			trigger = analytics.TriggerScheduled
		}
		res := s.push.Send(r.Context(), targets, push.Message{Title: req.Title, Body: req.Body}, trigger)
		resp.Result = &res
	}
	writeJSON(w, http.StatusOK, resp)
}
