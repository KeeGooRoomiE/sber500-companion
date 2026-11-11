package api

import (
	"os"
	"path/filepath"
	"testing"
)

func writeCfg(t *testing.T, body string) {
	t.Helper()
	p := filepath.Join(t.TempDir(), "app_config.json")
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("APP_CONFIG_FILE", p)
}

func TestLoadConfigMissingFileGivesDefaults(t *testing.T) {
	t.Setenv("APP_CONFIG_FILE", filepath.Join(t.TempDir(), "nope.json"))
	c := loadConfig()
	if c.Midday.CenterMin != 840 || c.Midday.DeltaMin != 60 || !c.Midday.Enabled || c.Silence.Days != 3 {
		t.Fatalf("defaults wrong: %+v", c)
	}
}

func TestLoadConfigOverridesOnlyNamedKeys(t *testing.T) {
	writeCfg(t, `{"midday":{"delta_min":30},"silence":{"days":5},"update":{"latest":"v1.1.0"}}`)
	c := loadConfig()
	if c.Midday.DeltaMin != 30 || c.Silence.Days != 5 {
		t.Fatalf("override not applied: %+v", c)
	}
	// untouched keys keep defaults, including the bool inside the same object
	if c.Midday.CenterMin != 840 || !c.Midday.Enabled {
		t.Fatalf("defaults lost: %+v", c.Midday)
	}
	if c.Update.Latest != "1.1.0" {
		t.Fatalf("latest not normalised: %q", c.Update.Latest)
	}
}

func TestLoadConfigBadJSONAndOutOfRange(t *testing.T) {
	writeCfg(t, `{not json`)
	if c := loadConfig(); c.Midday.CenterMin != 840 {
		t.Fatalf("bad json must fall back: %+v", c)
	}
	writeCfg(t, `{"midday":{"center_min":5000,"delta_min":-1},"silence":{"days":0}}`)
	c := loadConfig()
	if c.Midday.CenterMin != 840 || c.Midday.DeltaMin != 60 || c.Silence.Days != 3 {
		t.Fatalf("out of range must reset: %+v", c)
	}
}
