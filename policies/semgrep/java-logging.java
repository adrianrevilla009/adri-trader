// Test cases for java-logging.yaml (run: semgrep --test policies/semgrep). Not compiled.
class LoggingRuleCases {
    org.slf4j.Logger log;
    Object killSwitch;

    void cases(Throwable e, Object x) {
        // ruleid: java-log-string-concat
        log.info("value " + x);
        // ruleid: java-log-string-concat
        log.warn("failed for " + x, e);
        // ok: java-log-string-concat
        log.info("value {}", x);
        // ok: java-log-string-concat
        killSwitch.activate("manual via endpoint: " + x);
        // ruleid: java-print-stack-trace
        e.printStackTrace();
        // ruleid: java-system-out
        System.out.println("x");
        // ok: java-system-out
        log.info("x");
    }
}
