package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.service.api.ProcessRole;
import com.moveinsync.ev.service.observe.ServiceMeters;
import com.moveinsync.ev.service.plan.JobSolve;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Starts the plan-trigger consumer on the worker. The web role does not solve. */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnExpression(ProcessRole.WORKER)
public class WorkerConfiguration {

  @Bean(destroyMethod = "close")
  PlannerWorker plannerWorker(MessageBus bus, Clock clock, JobSolve solve, ServiceMeters meters) {
    BusSettings settings = BusSettings.standard();
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, settings, meters);
    PlannerWorker worker = new PlannerWorker(bus, delivery, settings);
    worker.start(solve::accept);
    worker.watchDeadLetters(solve::dead);
    return worker;
  }
}
