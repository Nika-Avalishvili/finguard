-- liquibase formatted sql

-- changeset nika.avalishvili:014-cakr-null-sentinels
-- comment: Migrate CAKR failure-sentinel 0.0 values to NULL so SQL AVG(...) skips failed evaluations.
--          See audit A-3: treating failures as 0.0 silently depressed the reported CAKR mean by
--          mixing "failed to score" with the valid score range [1, 5]. Post-014, scorers return
--          NULL on any parse/call failure, and historical rows are back-filled here.
-- runOnChange: false

UPDATE explanations SET cakr_completeness = NULL WHERE cakr_completeness = 0.0;
UPDATE explanations SET cakr_actionability = NULL WHERE cakr_actionability = 0.0;
UPDATE explanations SET cakr_correctness  = NULL WHERE cakr_correctness  = 0.0;
UPDATE explanations SET cakr_regulatory   = NULL WHERE cakr_regulatory   = 0.0;
