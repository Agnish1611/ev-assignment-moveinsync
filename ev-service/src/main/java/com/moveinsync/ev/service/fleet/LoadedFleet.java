package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.validator.ValidationInput;

/** Planner input and a separately mapped validation input from the same rows. */
public record LoadedFleet(PlannerInput planner, ValidationInput validation, long catalogVersion) {}
