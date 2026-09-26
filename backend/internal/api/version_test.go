package api

import "testing"

func TestNewerVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"0.7.1", "0.6.1", true},
		{"v0.9.0", "0.7.1", true},
		{"0.10.0", "0.9.1", true},
		{"0.6.1", "0.7.1", false},
		{"0.7.1", "0.7.1", false},
		{"", "0.7.1", false},
		{"junk", "0.7.1", false},
		{"1.0", "0.9.9", true},
	}
	for _, c := range cases {
		if got := newerVersion(c.a, c.b); got != c.want {
			t.Errorf("newerVersion(%q, %q) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}
