package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const testDailyToken = "0123456789abcdef0123456789abcdef0123456789abcdef"

func TestParseDailyRange(t *testing.T) {
	if _, _, err := parseDailyRange("2026-11-08", "2026-11-14"); err != nil {
		t.Fatalf("valid range rejected: %v", err)
	}
	// 90 days inclusive is the limit, 91 is over it
	if _, _, err := parseDailyRange("2026-08-17", "2026-11-14"); err != nil {
		t.Fatalf("90 days must pass: %v", err)
	}
	for name, c := range map[string][2]string{
		"missing from": {"", "2026-11-14"},
		"missing to":   {"2026-11-08", ""},
		"bad format":   {"08.11.2026", "2026-11-14"},
		"no such day":  {"2026-02-30", "2026-03-01"},
		"reversed":     {"2026-11-14", "2026-11-08"},
		"91 days":      {"2026-08-16", "2026-11-14"},
	} {
		if _, _, err := parseDailyRange(c[0], c[1]); err == nil {
			t.Errorf("%s: must be rejected", name)
		}
	}
}

func dailyRequest(m *DailyMetrics, auth, query string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodGet, "/api/v1/metrics/daily"+query, nil)
	if auth != "" {
		r.Header.Set("Authorization", auth)
	}
	w := httptest.NewRecorder()
	m.ServeHTTP(w, r)
	return w
}

func errorBody(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var body map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || body["error"] == "" {
		t.Fatalf(`body must be {"error": "…"}, got %q`, w.Body.String())
	}
	return body["error"]
}

func TestDailyAuth(t *testing.T) {
	m := &DailyMetrics{token: testDailyToken}
	q := "?date_from=2026-11-08&date_to=2026-11-14"
	for name, auth := range map[string]string{
		"no header":    "",
		"wrong token":  "Bearer " + strings.Repeat("x", 48),
		"not a bearer": "Basic " + testDailyToken,
		"prefix only":  "Bearer " + testDailyToken[:40],
	} {
		w := dailyRequest(m, auth, q)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s: got %d, want 401", name, w.Code)
		}
		errorBody(t, w)
	}
	// with no token configured the method is closed to everyone, an empty bearer included
	if w := dailyRequest(&DailyMetrics{}, "Bearer ", q); w.Code != http.StatusUnauthorized {
		t.Errorf("unconfigured: got %d, want 401", w.Code)
	}
}

func TestDailyBadDatesAre400AfterAuth(t *testing.T) {
	m := &DailyMetrics{token: testDailyToken}
	for _, q := range []string{"", "?date_from=2026-11-08", "?date_from=x&date_to=y", "?date_from=2026-01-01&date_to=2026-11-14"} {
		w := dailyRequest(m, "Bearer "+testDailyToken, q)
		if w.Code != http.StatusBadRequest {
			t.Errorf("%q: got %d, want 400", q, w.Code)
		}
		errorBody(t, w)
	}
}

func TestDailyTokenTooShortStaysClosed(t *testing.T) {
	t.Setenv("METRICS_DAILY_TOKEN", "short")
	m := NewDailyMetrics(nil, nil)
	if w := dailyRequest(m, "Bearer short", "?date_from=2026-11-08&date_to=2026-11-14"); w.Code != http.StatusUnauthorized {
		t.Fatalf("got %d, want 401", w.Code)
	}
}

func TestDailyRecordFieldNames(t *testing.T) {
	b, _ := json.Marshal(DailyMetricsResponse{Data: []DailyRecord{{}}})
	for _, f := range []string{`"startup_id"`, `"timezone"`, `"generated_at"`, `"data"`, `"date"`, `"dau":0`,
		`"llm_requests":0`, `"tool_requests":0`, `"total_users":0`, `"new_users":0`, `"is_final":false`,
		`"retained_d1":null`, `"retained_d3":null`, `"retained_d7":null`, `"retained_d14":null`} {
		if !strings.Contains(string(b), f) {
			t.Errorf("missing %s in %s", f, b)
		}
	}
}
