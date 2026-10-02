-- Which build each phone is actually on.
--
-- The app is handed out as an APK link, so people update by hand and several versions are
-- live at once. Nothing reported the version, which meant a gap like «70% of check-ins got
-- the review notification» could not be told apart from «30% are on a build that predates
-- the feature». After RuStore the spread becomes permanent, so this stops being temporary.
ALTER TABLE users ADD COLUMN IF NOT EXISTS app_version TEXT;
