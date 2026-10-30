package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.schedule.FleetPlan;

/** One seeded corruption: ground truth plus the candidate plan. */
record RuleProbe(ValidationInput input, FleetPlan plan) {}
