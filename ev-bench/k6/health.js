import http from "k6/http";
import { check } from "k6";

export const options = {
  vus: 10,
  duration: "15s",
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  thresholds: {
    http_req_failed: ["rate==0"],
  },
};

export default function () {
  const target = __ENV.TARGET || "http://127.0.0.1:8080/actuator/health";
  const response = http.get(target);
  check(response, {
    "status is 200": (row) => row.status === 200,
  });
}
